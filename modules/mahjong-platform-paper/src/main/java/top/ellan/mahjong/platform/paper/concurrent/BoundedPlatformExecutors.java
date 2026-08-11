package top.ellan.mahjong.platform.paper.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded executors; tables never own threads or queues. */
public final class BoundedPlatformExecutors implements AutoCloseable {
    private final ThreadPoolExecutor actor;
    private final ThreadPoolExecutor render;
    private final ThreadPoolExecutor io;

    public BoundedPlatformExecutors(int processors) {
        if (processors < 1) {
            throw new IllegalArgumentException("processors must be positive");
        }
        actor = pool(Math.max(2, Math.min(processors, 8)), 1_024, "mahjong-actor");
        render = pool(Math.max(1, Math.min(processors / 2, 4)), 256, "mahjong-scene");
        io = pool(Math.max(2, Math.min(processors * 2, 16)), 512, "mahjong-io");
    }

    public Executor actor() {
        return actor;
    }

    public Executor render() {
        return render;
    }

    public Executor io() {
        return io;
    }

    public int actorQueueDepth() {
        return actor.getQueue().size();
    }

    public int renderQueueDepth() {
        return render.getQueue().size();
    }

    public int ioQueueDepth() {
        return io.getQueue().size();
    }

    public boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        actor.shutdown();
        render.shutdown();
        io.shutdown();
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean clean = await(actor, deadline) & await(render, deadline) & await(io, deadline);
        if (!clean) {
            actor.shutdownNow();
            render.shutdownNow();
            io.shutdownNow();
        }
        return clean;
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(10));
    }

    private static ThreadPoolExecutor pool(int threads, int queueCapacity, String name) {
        RejectedExecutionHandler rejection = new ThreadPoolExecutor.AbortPolicy();
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        threads,
                        threads,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(queueCapacity),
                        namedThreads(name),
                        rejection);
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task ->
                Thread.ofPlatform()
                        .name(prefix + '-' + sequence.getAndIncrement())
                        .daemon(true)
                        .unstarted(task);
    }

    private static boolean await(ThreadPoolExecutor executor, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
