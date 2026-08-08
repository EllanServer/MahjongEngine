package top.ellan.mahjong.application;

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
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Deadline> deadlines =
            new PriorityQueue<>(Comparator.comparingLong(Deadline::dueNanos).thenComparingLong(Deadline::order));
    private final java.util.concurrent.Executor target;
    private final int capacity;
    private final AtomicLong order = new AtomicLong();
    private final Thread timerThread;
    private boolean running = true;

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
            if (deadlines.size() >= capacity) {
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
            return deadlines.size();
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
            boolean removed = deadlines.remove(deadline);
            changed.signal();
            return removed;
        } finally {
            lock.unlock();
        }
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
            try {
                target.execute(due.task());
            } catch (RejectedExecutionException ignored) {
                // Shutdown races are intentionally isolated from the timer thread.
            }
        }
        return true;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            running = false;
            deadlines.forEach(item -> item.cancelled().set(true));
            deadlines.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        timerThread.interrupt();
    }

    private record Deadline(
            long dueNanos, long order, Runnable task, AtomicBoolean cancelled) {
        private Deadline(long dueNanos, long order, Runnable task) {
            this(dueNanos, order, task, new AtomicBoolean());
        }
    }
}
