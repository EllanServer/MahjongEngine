package top.ellan.mahjong.application.concurrent;

import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** A single timer thread backed by a manually bounded heap. Tasks execute on a supplied executor. */
public final class BoundedDeadlineScheduler implements TaskScheduler, AutoCloseable {
    private static final long INITIAL_RETRY_NANOS = 1_000_000L;
    private static final long MAX_RETRY_NANOS = 250_000_000L;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Deadline> deadlines =
            new PriorityQueue<>(Comparator.comparingLong(Deadline::dueNanos).thenComparingLong(Deadline::order));
    private final java.util.concurrent.Executor target;
    private final int capacity;
    private final AtomicLong order = new AtomicLong();
    private final Thread timerThread;
    private boolean running = true;
    private int cancelledCount;

    public BoundedDeadlineScheduler(
            int capacity, java.util.concurrent.Executor target, String threadName) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.target = Objects.requireNonNull(target, "target");
        timerThread =
                Thread.ofPlatform()
                        .name(Objects.requireNonNull(threadName, "threadName"))
                        .daemon(true)
                        .unstarted(this::runTimer);
        timerThread.start();
    }

    @Override
    public Cancellable schedule(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay cannot be negative");
        }
        long delayNanos;
        try {
            delayNanos = delay.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("delay is too large", exception);
        }
        long now = System.nanoTime();
        long due = delayNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delayNanos;
        Deadline deadline = new Deadline(due, order.getAndIncrement(), task);
        lock.lock();
        try {
            if (!running) {
                throw new RejectedExecutionException("deadline scheduler is closed");
            }
            if (deadlines.size() - cancelledCount >= capacity) {
                throw new RejectedExecutionException("deadline scheduler capacity exceeded");
            }
            deadlines.add(deadline);
            changed.signal();
        } finally {
            lock.unlock();
        }
        return () -> cancel(deadline);
    }

    public int pendingTasks() {
        lock.lock();
        try {
            return deadlines.size() - cancelledCount;
        } finally {
            lock.unlock();
        }
    }

    private boolean cancel(Deadline deadline) {
        if (!deadline.cancelled().compareAndSet(false, true)) {
            return false;
        }
        lock.lock();
        try {
            // Lazy cancel: the timer skips cancelled entries when they reach the head, so a
            // cancellation is O(1) and never scans the heap. Rebuild once half the capacity is
            // garbage so producers are never stalled by cancelled entries.
            cancelledCount++;
            changed.signal();
            if (cancelledCount >= Math.max(1, capacity / 2)) {
                compact();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void compact() {
        if (cancelledCount == 0) {
            return;
        }
        PriorityQueue<Deadline> survivors =
                new PriorityQueue<>(
                        Comparator.comparingLong(Deadline::dueNanos)
                                .thenComparingLong(Deadline::order));
        for (Deadline deadline : deadlines) {
            if (!deadline.isCancelled()) {
                survivors.add(deadline);
            }
        }
        deadlines.clear();
        deadlines.addAll(survivors);
        cancelledCount = 0;
    }

    private void runTimer() {
        while (takeAndDispatch()) {
            // Work is performed by the target executor, never by the timer thread.
        }
    }

    private boolean takeAndDispatch() {
        Deadline due;
        lock.lock();
        try {
            while (true) {
                if (!running && deadlines.isEmpty()) {
                    return false;
                }
                Deadline head = deadlines.peek();
                if (head == null) {
                    changed.await();
                    continue;
                }
                if (head.isCancelled()) {
                    deadlines.remove();
                    cancelledCount--;
                    continue;
                }
                long remaining = head.dueNanos() - System.nanoTime();
                if (remaining > 0) {
                    changed.awaitNanos(remaining);
                    continue;
                }
                due = deadlines.remove();
                break;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
        if (!due.cancelled().get()) {
            AtomicBoolean started = new AtomicBoolean();
            try {
                target.execute(
                        () -> {
                            started.set(true);
                            if (!due.cancelled().get()) {
                                due.task().run();
                            }
                        });
            } catch (RuntimeException rejected) {
                if (!started.get()) {
                    retry(due);
                }
            }
        }
        return true;
    }

    private void retry(Deadline deadline) {
        lock.lock();
        try {
            if (!running || deadline.cancelled().get()) {
                return;
            }
            deadline.backOffFrom(System.nanoTime());
            // The timer owns at most one removed deadline, so this internal retry slot keeps the
            // heap bounded by capacity + 1 even if producers fill the public capacity meanwhile.
            deadlines.add(deadline);
            changed.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            running = false;
            deadlines.forEach(item -> item.cancelled().set(true));
            deadlines.clear();
            cancelledCount = 0;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        timerThread.interrupt();
    }

    private static final class Deadline {
        private long dueNanos;
        private final long order;
        private final Runnable task;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private long retryNanos = INITIAL_RETRY_NANOS;

        private Deadline(long dueNanos, long order, Runnable task) {
            this.dueNanos = dueNanos;
            this.order = order;
            this.task = task;
        }

        private long dueNanos() {
            return dueNanos;
        }

        private long order() {
            return order;
        }

        private Runnable task() {
            return task;
        }

        private AtomicBoolean cancelled() {
            return cancelled;
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private void backOffFrom(long now) {
            dueNanos = retryNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + retryNanos;
            retryNanos = Math.min(MAX_RETRY_NANOS, retryNanos * 2);
        }
    }
}
