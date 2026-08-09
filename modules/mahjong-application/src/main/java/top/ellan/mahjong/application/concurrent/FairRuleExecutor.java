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
    private final int maxWorkersPerPack;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int queuedTasks;

    public FairRuleExecutor(int workerCount, int queueCapacity, String threadPrefix) {
        if (workerCount < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("workerCount and queueCapacity must be positive");
        }
        this.queueCapacity = queueCapacity;
        maxWorkersPerPack = Math.max(1, workerCount / 2);
        workers = new Thread[workerCount];
        for (int index = 0; index < workerCount; index++) {
            workers[index] =
                    Thread.ofPlatform()
                            .name(Objects.requireNonNull(threadPrefix, "threadPrefix") + '-' + index)
                            .daemon(true)
                            .unstarted(this::workerLoop);
            workers[index].start();
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
        return maxWorkersPerPack;
    }

    private void workerLoop() {
        while (true) {
            ClaimedOperation claimed = claim();
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

    private ClaimedOperation claim() {
        lock.lock();
        try {
            while (readyPacks.isEmpty()) {
                if (closed.get()) {
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
            makeReady(ruleId, pack);
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void makeReady(RuleId ruleId, PackQueue pack) {
        if (!pack.ready()
                && !pack.operations().isEmpty()
                && pack.activeWorkers() < maxWorkersPerPack) {
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
