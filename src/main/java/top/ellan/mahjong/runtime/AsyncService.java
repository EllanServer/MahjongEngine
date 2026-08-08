package top.ellan.mahjong.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.application.BoundedDeadlineScheduler;

public final class AsyncService implements AutoCloseable {
    private static final int MAX_CPU_WORKERS = 8;
    private static final int MAX_IO_WORKERS = 16;
    private static final int IO_QUEUE_CAPACITY = 512;
    private static final int CPU_QUEUE_CAPACITY = 256;
    private static final int RETRY_DEADLINE_CAPACITY = 512;

    private final Logger logger;
    private final ExecutorService ioExecutor;
    private final ExecutorService cpuExecutor;
    private final BoundedDeadlineScheduler retryScheduler;
    private final Set<CompletableFuture<Void>> activeOperations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean acceptingTasks = new AtomicBoolean(true);

    public AsyncService(Logger logger) {
        this.logger = logger;
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int ioWorkers = Math.max(2, Math.min(MAX_IO_WORKERS, processors * 2));
        this.ioExecutor = createBoundedExecutor(ioWorkers, IO_QUEUE_CAPACITY, "MahjongPaper-IO-");
        int cpuWorkers = Math.max(1, Math.min(MAX_CPU_WORKERS, Runtime.getRuntime().availableProcessors()));
        this.cpuExecutor = createBoundedExecutor(
            cpuWorkers,
            CPU_QUEUE_CAPACITY,
            "MahjongPaper-CPU-"
        );
        this.retryScheduler = new BoundedDeadlineScheduler(
            RETRY_DEADLINE_CAPACITY,
            this.ioExecutor,
            "MahjongPaper-Async-Retry"
        );
    }

    private static ExecutorService createBoundedExecutor(int workers, int capacity, String namePrefix) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            workers,
            workers,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity),
            namedDaemonThreadFactory(namePrefix),
            new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static ThreadFactory namedDaemonThreadFactory(String namePrefix) {
        AtomicLong sequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public void execute(String taskName, Runnable task) {
        this.execute(this.ioExecutor, taskName, task);
    }

    /** Runs CPU-bound precomputation with bounded parallelism so it cannot starve server ticks. */
    public void executeCpu(String taskName, Runnable task) {
        this.execute(this.cpuExecutor, taskName, task);
    }

    private void execute(ExecutorService executor, String taskName, Runnable task) {
        if (!this.acceptingTasks.get()) {
            this.logger.fine("Ignoring async task after executor shutdown: " + taskName);
            return;
        }
        CompletableFuture<Void> result = this.trackOperation();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                    result.complete(null);
                } catch (Throwable throwable) {
                    this.logger.log(Level.WARNING, "Async task failed: " + taskName, throwable);
                    result.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException ignored) {
            this.logger.fine("Ignoring async task after executor shutdown: " + taskName);
            result.completeExceptionally(ignored);
        }
    }

    /**
     * Executes a checked task with a bounded deterministic exponential backoff.
     * A retry is scheduled only after the preceding attempt has failed, so an
     * operation never has more than one attempt in flight.
     */
    public CompletableFuture<Void> executeWithRetry(String taskName, CheckedRunnable task, RetryPolicy policy) {
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(policy, "policy");
        if (!this.acceptingTasks.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Async service is shutting down"));
        }

        CompletableFuture<Void> result = this.trackOperation();
        this.submitRetryAttempt(taskName, task, policy, 1, result);
        return result;
    }

    private CompletableFuture<Void> trackOperation() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        this.activeOperations.add(result);
        result.whenComplete((ignored, failure) -> this.activeOperations.remove(result));
        return result;
    }

    private void submitRetryAttempt(
        String taskName,
        CheckedRunnable task,
        RetryPolicy policy,
        int attempt,
        CompletableFuture<Void> result
    ) {
        if (result.isDone()) {
            return;
        }
        try {
            this.ioExecutor.execute(() -> this.runRetryAttempt(taskName, task, policy, attempt, result));
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
    }

    private void runRetryAttempt(
        String taskName,
        CheckedRunnable task,
        RetryPolicy policy,
        int attempt,
        CompletableFuture<Void> result
    ) {
        if (result.isDone()) {
            return;
        }
        try {
            task.run();
            result.complete(null);
        } catch (Throwable failure) {
            if (attempt >= policy.maxAttempts()) {
                this.logger.log(
                    Level.WARNING,
                    "Async task failed after " + attempt + " attempts: " + taskName,
                    failure
                );
                result.completeExceptionally(failure);
                return;
            }
            int nextAttempt = attempt + 1;
            long delayMillis = policy.delayMillisBeforeAttempt(nextAttempt);
            this.logger.log(
                Level.WARNING,
                "Async task failed: " + taskName + "; retrying attempt " + nextAttempt
                    + "/" + policy.maxAttempts() + " in " + delayMillis + " ms",
                failure
            );
            try {
                this.retryScheduler.schedule(
                    () -> this.submitRetryAttempt(taskName, task, policy, nextAttempt, result),
                    Duration.ofMillis(delayMillis)
                );
            } catch (RejectedExecutionException rejected) {
                result.completeExceptionally(rejected);
            }
        }
    }

    @Override
    public void close() {
        if (!this.acceptingTasks.compareAndSet(true, false)) {
            return;
        }
        CompletableFuture<?>[] operations = this.activeOperations.toArray(CompletableFuture<?>[]::new);
        if (operations.length > 0) {
            try {
                CompletableFuture.allOf(operations).get(5L, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                for (CompletableFuture<?> operation : operations) {
                    operation.completeExceptionally(timeout);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                for (CompletableFuture<?> operation : operations) {
                    operation.completeExceptionally(exception);
                }
            } catch (java.util.concurrent.ExecutionException ignored) {
                // Individual failures have already been reported by their task.
            }
        }
        this.retryScheduler.close();
        this.ioExecutor.shutdown();
        this.cpuExecutor.shutdown();
        try {
            this.awaitTermination(this.ioExecutor);
            this.awaitTermination(this.cpuExecutor);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.ioExecutor.shutdownNow();
            this.cpuExecutor.shutdownNow();
        }
    }

    private void awaitTermination(ExecutorService executor) throws InterruptedException {
        if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    public record RetryPolicy(int maxAttempts, long initialDelayMillis, long maxDelayMillis) {
        public RetryPolicy {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            if (initialDelayMillis < 0L) {
                throw new IllegalArgumentException("initialDelayMillis must not be negative");
            }
            if (maxDelayMillis < initialDelayMillis) {
                throw new IllegalArgumentException("maxDelayMillis must be at least initialDelayMillis");
            }
        }

        public long delayMillisBeforeAttempt(int attemptNumber) {
            if (attemptNumber <= 1) {
                return 0L;
            }
            long delay = Math.min(this.initialDelayMillis, this.maxDelayMillis);
            for (int retry = 2; retry < attemptNumber && delay < this.maxDelayMillis; retry++) {
                if (delay > this.maxDelayMillis / 2L) {
                    return this.maxDelayMillis;
                }
                delay = Math.min(this.maxDelayMillis, delay * 2L);
            }
            return delay;
        }
    }

    /**
     * Waits for every operation accepted before this method took its snapshot,
     * including retry operations that are currently in their backoff delay.
     * The executor remains usable after the wait.
     *
     * <p>Caller must not hold any lock that submitted tasks could need.</p>
     *
     * @param timeoutSeconds maximum wait; must be positive.
     * @return {@code true} when the snapshot completed, otherwise {@code false}.
     */
    public boolean awaitQuiescence(long timeoutSeconds) {
        if (timeoutSeconds <= 0L) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        CompletableFuture<?>[] operations = this.activeOperations.toArray(CompletableFuture<?>[]::new);
        if (operations.length == 0) {
            return true;
        }
        try {
            CompletableFuture.allOf(operations).get(timeoutSeconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException timeout) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException ignored) {
            // A failed operation is complete and no longer uses its resources.
            return true;
        }
    }
}
