package top.ellan.mahjong.application.concurrent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import top.ellan.mahjong.spi.RuleId;

/**
 * Bounded rule CPU pool with round-robin pack selection and a per-pack concurrency ceiling.
 * Waiting workers never occupy execution slots while a pack is at its ceiling.
 */
public final class FairRuleExecutor implements AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Map<RuleId, PackQueue> packs = new HashMap<>();
    private final ArrayDeque<RuleId> readyPacks = new ArrayDeque<>();
    private final Thread[] workers;
    private final int queueCapacity;
    private final int workerCount;
    private final String threadPrefix;
    private final ClassLoader hostContextClassLoader;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int queuedTasks;
    private int activePacks;
    private int renewTarget;

    public FairRuleExecutor(int workerCount, int queueCapacity, String threadPrefix) {
        if (workerCount < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("workerCount and queueCapacity must be positive");
        }
        this.queueCapacity = queueCapacity;
        this.workerCount = workerCount;
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        // Captured from the host thread that builds the pool so replacement workers never inherit a
        // rule-pack loader as their context classloader.
        hostContextClassLoader = FairRuleExecutor.class.getClassLoader();
        workers = new Thread[workerCount];
        for (int index = 0; index < workerCount; index++) {
            workers[index] = startWorker(index, 0);
        }
    }

    private Thread startWorker(int index, int generation) {
        Thread worker =
                Thread.ofPlatform()
                        .name(threadPrefix + '-' + index + (generation == 0 ? "" : "-r" + generation))
                        .daemon(true)
                        .unstarted(() -> workerLoop(index, generation));
        worker.setContextClassLoader(hostContextClassLoader);
        worker.start();
        return worker;
    }

    /** Retires one worker generation and starts its replacement. Must hold {@link #lock}. */
    private void replaceWorker(int index, int generation) {
        workers[index] = startWorker(index, generation + 1);
    }

    /**
     * Replaces every idle worker thread.
     *
     * <p>Called after a rule pack is unloaded. A worker that ran rule code may still hold thread
     * locals whose values were loaded by that pack's classloader, which would keep the loader
     * reachable and defeat the unload. Retiring the threads is the only reliable fix; clearing the
     * thread-local map reflectively is not thread safe.</p>
     */
    public void renewWorkers() {
        lock.lock();
        try {
            if (closed.get()) {
                return;
            }
            renewTarget++;
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public <T> CompletableFuture<T> submit(RuleId ruleId, Supplier<T> operation) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(operation, "operation");
        ScheduledOperation<T> scheduled = new ScheduledOperation<>(operation);
        lock.lock();
        try {
            if (closed.get()) {
                throw new RejectedExecutionException("rule executor is closed");
            }
            if (queuedTasks >= queueCapacity) {
                throw new RejectedExecutionException("rule executor queue is full");
            }
            PackQueue pack = packs.computeIfAbsent(ruleId, ignored -> new PackQueue());
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
                claimed.operation().run();
            } finally {
                release(claimed.ruleId());
            }
        }
    }

    private ClaimedOperation claim(int index, int generation) {
        lock.lock();
        try {
            while (readyPacks.isEmpty()) {
                if (closed.get()) {
                    return null;
                }
                if (generation < renewTarget) {
                    // Retire this thread and start a fresh one so no rule-pack thread local
                    // survives an unload. The replacement inherits the host context classloader.
                    replaceWorker(index, generation);
                    return null;
                }
                available.await();
            }
            RuleId ruleId = readyPacks.removeFirst();
            PackQueue pack = packs.get(ruleId);
            pack.ready(false);
            ScheduledOperation<?> operation = pack.operations().removeFirst();
            queuedTasks--;
            pack.activeWorkers(pack.activeWorkers() + 1);
            makeReady(ruleId, pack);
            // Cascade: wake exactly one more waiter when ready work remains, so a single signal
            // fans out without a thundering herd of signalAll.
            if (!readyPacks.isEmpty()) {
                available.signal();
            }
            return new ClaimedOperation(ruleId, operation);
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
            PackQueue pack = packs.get(ruleId);
            pack.activeWorkers(pack.activeWorkers() - 1);
            if (pack.idle()) {
                activePacks--;
            }
            makeReady(ruleId, pack);
            // A released slot admits at most one more worker; the claim cascade fans out further.
            if (!readyPacks.isEmpty()) {
                available.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    private int ceiling() {
        // A single active pack may use the whole pool; several packs share it fairly.
        return Math.max(1, workerCount / Math.max(1, activePacks));
    }

    private void makeReady(RuleId ruleId, PackQueue pack) {
        if (!pack.ready()
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
    }

    private static final class PackQueue {
        private final ArrayDeque<ScheduledOperation<?>> operations = new ArrayDeque<>();
        private int activeWorkers;
        private boolean ready;

        ArrayDeque<ScheduledOperation<?>> operations() {
            return operations;
        }

        int activeWorkers() {
            return activeWorkers;
        }

        void activeWorkers(int value) {
            activeWorkers = value;
        }

        boolean ready() {
            return ready;
        }

        void ready(boolean value) {
            ready = value;
        }

        /** A pack is idle only when it has neither queued operations nor running workers. */
        boolean idle() {
            return operations.isEmpty() && activeWorkers == 0;
        }
    }

    private record ClaimedOperation(RuleId ruleId, ScheduledOperation<?> operation) {}

    private record ScheduledOperation<T>(Supplier<T> supplier, CompletableFuture<T> future) {
        private ScheduledOperation(Supplier<T> supplier) {
            this(supplier, new CompletableFuture<>());
        }

        private void run() {
            if (future.isCancelled()) {
                return;
            }
            try {
                future.complete(supplier.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }
    }
}
