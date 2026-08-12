package top.ellan.mahjong.application.concurrent;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import top.ellan.mahjong.spi.RuleExecutionBudget;
import top.ellan.mahjong.spi.RuleId;

/** Bounded, fair rule CPU pool with per-pack quotas, deadlines and circuit breaking. */
public final class FairRuleExecutor implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final int FAILURE_THRESHOLD = 3;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Map<RuleId, RulePackWorkQueue> packs = new HashMap<>();
    private final ArrayDeque<RuleId> readyPacks = new ArrayDeque<>();
    private final Thread[] workers;
    private final int queueCapacity;
    private final int perPackQueueCapacity;
    private final int workerCount;
    private final String threadPrefix;
    private final ClassLoader hostContextClassLoader;
    private final ScheduledThreadPoolExecutor watchdog;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int queuedTasks;
    private int activePacks;
    private int renewTarget;
    public FairRuleExecutor(int workers, int capacity, String prefix) {
        this(workers, capacity, Math.max(1, capacity / 2), prefix);
    }
    public FairRuleExecutor(int workerCount, int queueCapacity, int perPackQueueCapacity,
            String threadPrefix) {
        if (workerCount < 1 || queueCapacity < 1 || perPackQueueCapacity < 1) {
            throw new IllegalArgumentException("worker and queue capacities must be positive");
        }
        if (perPackQueueCapacity > queueCapacity) {
            throw new IllegalArgumentException("per-pack capacity exceeds global capacity");
        }
        this.queueCapacity = queueCapacity;
        this.perPackQueueCapacity = perPackQueueCapacity;
        this.workerCount = workerCount;
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        hostContextClassLoader = FairRuleExecutor.class.getClassLoader();
        watchdog = createWatchdog(threadPrefix, hostContextClassLoader);
        workers = new Thread[workerCount];
        for (int index = 0; index < workerCount; index++) {
            workers[index] = startWorker(index, 0);
        }
    }
    private static ScheduledThreadPoolExecutor createWatchdog(
            String prefix, ClassLoader contextClassLoader) {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
                1,
                task -> {
                    Thread thread = Thread.ofPlatform()
                            .name(prefix + "-watchdog")
                            .daemon(true)
                            .unstarted(task);
                    thread.setContextClassLoader(contextClassLoader);
                    return thread;
                });
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return timer;
    }
    private Thread startWorker(int index, int generation) {
        Thread worker = Thread.ofPlatform()
                .name(threadPrefix + '-' + index + (generation == 0 ? "" : "-r" + generation))
                .daemon(true)
                .unstarted(() -> workerLoop(index, generation));
        worker.setContextClassLoader(hostContextClassLoader);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        worker.start();
        return worker;
    }
    private void replaceWorker(int index, int generation) {
        workers[index] = startWorker(index, generation + 1);
    }
    public void renewWorkers() {
        lock.lock();
        try {
            if (!closed.get()) {
                renewTarget++;
                available.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }
    public <T> CompletableFuture<T> submit(RuleId ruleId, Supplier<T> operation) {
        return submit(ruleId, DEFAULT_TIMEOUT, operation);
    }
    public <T> CompletableFuture<T> submit(
            RuleId ruleId, Duration timeout, Supplier<T> operation) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("rule execution timeout must be within five minutes");
        }
        ScheduledOperation<T> scheduled = new ScheduledOperation<>(timeout, operation);
        lock.lock();
        try {
            if (closed.get()) {
                throw new RejectedExecutionException("rule executor is closed");
            }
            RulePackWorkQueue pack = packs.computeIfAbsent(ruleId, ignored -> new RulePackWorkQueue());
            if (pack.circuitOpen()) {
                throw new RulePackCircuitOpenException(ruleId);
            }
            if (pack.operations().size() >= perPackQueueCapacity) {
                throw new RejectedExecutionException("rule-pack queue is full: " + ruleId);
            }
            if (queuedTasks >= queueCapacity) {
                throw new RejectedExecutionException("rule executor queue is full");
            }
            if (pack.idle()) {
                activePacks++;
            }
            pack.operations().addLast(scheduled);
            queuedTasks++;
            makeReady(ruleId, pack);
            available.signal();
        } finally {
            lock.unlock();
        }
        return scheduled.future();
    }
    public void resetCircuit(RuleId ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        lock.lock();
        try {
            RulePackWorkQueue pack = packs.get(ruleId);
            if (pack != null) {
                pack.circuitOpen(false);
                pack.consecutiveFailures(0);
            }
        } finally {
            lock.unlock();
        }
    }
    public int queuedTasks() {
        lock.lock();
        try {
            return queuedTasks;
        } finally {
            lock.unlock();
        }
    }
    public int maxWorkersPerPack() {
        lock.lock();
        try {
            return ceiling();
        } finally {
            lock.unlock();
        }
    }
    private void workerLoop(int index, int generation) {
        while (true) {
            ClaimedOperation claimed = claim(index, generation);
            if (claimed == null) {
                return;
            }
            try {
                claimed.operation().run(this, claimed.ruleId());
            } finally {
                release(claimed.ruleId());
            }
        }
    }
    private ClaimedOperation claim(int index, int generation) {
        lock.lock();
        try {
            while (true) {
                while (!closed.get()
                        && generation >= renewTarget
                        && readyPacks.isEmpty()) {
                    available.await();
                }
                if (closed.get()) {
                    return null;
                }
                if (generation < renewTarget) {
                    replaceWorker(index, generation);
                    return null;
                }
                RuleId ruleId = readyPacks.removeFirst();
                RulePackWorkQueue pack = packs.get(ruleId);
                pack.ready(false);
                if (pack.circuitOpen()
                        || pack.operations().isEmpty()
                        || pack.activeWorkers() >= ceiling()) {
                    continue;
                }
                ScheduledOperation<?> operation = pack.operations().removeFirst();
                queuedTasks--;
                pack.activeWorkers(pack.activeWorkers() + 1);
                makeReady(ruleId, pack);
                if (!readyPacks.isEmpty()) {
                    available.signal();
                }
                return new ClaimedOperation(ruleId, operation);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }
    private void release(RuleId ruleId) {
        lock.lock();
        try {
            RulePackWorkQueue pack = packs.get(ruleId);
            pack.activeWorkers(pack.activeWorkers() - 1);
            if (pack.idle() && activePacks > 0) {
                activePacks--;
                packs.forEach(this::makeReady);
            } else {
                makeReady(ruleId, pack);
            }
            if (!readyPacks.isEmpty()) {
                available.signal();
            }
        } finally {
            lock.unlock();
        }
    }
    private void recordSuccess(RuleId ruleId) {
        lock.lock();
        try {
            RulePackWorkQueue pack = packs.get(ruleId);
            if (pack != null && !pack.circuitOpen()) {
                pack.consecutiveFailures(0);
            }
        } finally {
            lock.unlock();
        }
    }
    private void recordFailure(RuleId ruleId, Throwable failure) {
        List<ScheduledOperation<?>> rejected = List.of();
        lock.lock();
        try {
            RulePackWorkQueue pack = packs.get(ruleId);
            if (pack == null || pack.circuitOpen()) {
                return;
            }
            int failures = pack.consecutiveFailures() + 1;
            pack.consecutiveFailures(failures);
            if (failure instanceof Error
                    || RuleExecutionBudget.exceeded(failure)
                    || failures >= FAILURE_THRESHOLD) {
                rejected = openCircuit(ruleId, pack);
            }
        } finally {
            lock.unlock();
        }
        reject(rejected, new RulePackCircuitOpenException(ruleId, failure));
    }
    private void timeout(RuleId ruleId, ScheduledOperation<?> operation, Thread runner) {
        RuleExecutionTimeoutException failure = new RuleExecutionTimeoutException(ruleId);
        if (!operation.claimTimeout()) {
            return;
        }
        List<ScheduledOperation<?>> rejected;
        lock.lock();
        try {
            RulePackWorkQueue pack = packs.get(ruleId);
            rejected = pack == null || pack.circuitOpen()
                    ? List.of()
                    : openCircuit(ruleId, pack);
        } finally {
            lock.unlock();
        }
        reject(rejected, new RulePackCircuitOpenException(ruleId, failure));
        operation.future().completeExceptionally(failure);
        runner.interrupt();
    }
    private List<ScheduledOperation<?>> openCircuit(RuleId ruleId, RulePackWorkQueue pack) {
        pack.circuitOpen(true);
        pack.consecutiveFailures(0);
        if (pack.ready()) {
            readyPacks.remove(ruleId);
            pack.ready(false);
        }
        List<ScheduledOperation<?>> rejected = new ArrayList<>(pack.operations());
        queuedTasks -= rejected.size();
        pack.operations().clear();
        if (pack.idle()) {
            activePacks = Math.max(0, activePacks - 1);
        }
        return rejected;
    }
    private static void reject(List<ScheduledOperation<?>> operations,
            RulePackCircuitOpenException failure) {
        operations.forEach(operation -> operation.future().completeExceptionally(failure));
    }
    private int ceiling() {
        return Math.max(1, workerCount / Math.max(1, activePacks));
    }
    private void makeReady(RuleId ruleId, RulePackWorkQueue pack) {
        if (!pack.circuitOpen()
                && !pack.ready()
                && !pack.operations().isEmpty()
                && pack.activeWorkers() < ceiling()) {
            pack.ready(true);
            readyPacks.addLast(ruleId);
        }
    }
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lock.lock();
        try {
            RejectedExecutionException failure =
                    new RejectedExecutionException("rule executor closed before execution");
            packs.values().stream()
                    .flatMap(pack -> pack.operations().stream())
                    .forEach(operation -> operation.future().completeExceptionally(failure));
            packs.values().forEach(pack -> pack.operations().clear());
            readyPacks.clear();
            queuedTasks = 0;
            activePacks = 0;
            available.signalAll();
        } finally {
            lock.unlock();
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        watchdog.shutdownNow();
    }
    static final class ScheduledOperation<T> {
        private final Duration timeout;
        private final Supplier<T> supplier;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private ScheduledOperation(Duration timeout, Supplier<T> supplier) {
            this.timeout = timeout;
            this.supplier = supplier;
        }
        private CompletableFuture<T> future() {
            return future;
        }
        private void run(FairRuleExecutor owner, RuleId ruleId) {
            if (future.isCancelled()) {
                return;
            }
            Thread runner = Thread.currentThread();
            ScheduledFuture<?> deadline = null;
            try {
                deadline = owner.watchdog.schedule(
                        () -> owner.timeout(ruleId, this, runner),
                        timeout.toNanos(),
                        TimeUnit.NANOSECONDS);
                T result = RuleExecutionBudget.call(timeout, supplier);
                if (finished.compareAndSet(false, true)) {
                    owner.recordSuccess(ruleId);
                    future.complete(result);
                }
            } catch (Throwable failure) {
                if (finished.compareAndSet(false, true)) {
                    owner.recordFailure(ruleId, failure);
                    future.completeExceptionally(failure);
                }
            } finally {
                if (deadline != null) {
                    deadline.cancel(false);
                }
                Thread.interrupted();
            }
        }
        private boolean claimTimeout() {
            return finished.compareAndSet(false, true);
        }
    }
    private record ClaimedOperation(RuleId ruleId, ScheduledOperation<?> operation) {}
}
