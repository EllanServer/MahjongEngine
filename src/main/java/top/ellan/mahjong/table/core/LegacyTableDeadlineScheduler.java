package top.ellan.mahjong.table.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import top.ellan.mahjong.runtime.PluginTask;
import top.ellan.mahjong.runtime.ServerScheduler;

/**
 * Temporary scheduling boundary for legacy sessions while they migrate to {@code TableActor}.
 *
 * <p>Every table owns one region-bound, one-shot callback. There is deliberately no global task
 * that walks all tables: a delayed or failed callback can therefore affect only its own table and
 * region. New actor-backed matches use the application deadline scheduler instead of this class.
 */
final class LegacyTableDeadlineScheduler implements AutoCloseable {
    private static final long CHECK_DELAY_TICKS = 20L;

    private final ServerScheduler scheduler;
    private final Consumer<MahjongTableSession> callback;
    private final BiConsumer<MahjongTableSession, RuntimeException> failureSink;
    private final Map<String, TableLoop> loops = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    LegacyTableDeadlineScheduler(
            ServerScheduler scheduler,
            Consumer<MahjongTableSession> callback,
            BiConsumer<MahjongTableSession, RuntimeException> failureSink) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.callback = Objects.requireNonNull(callback, "callback");
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    void register(MahjongTableSession session) {
        Objects.requireNonNull(session, "session");
        if (closed.get()) {
            return;
        }
        TableLoop replacement = new TableLoop(session);
        TableLoop previous = loops.put(session.id(), replacement);
        if (previous != null) {
            previous.close();
        }
        replacement.scheduleNext();
    }

    void unregister(MahjongTableSession session) {
        if (session == null) {
            return;
        }
        TableLoop loop = loops.remove(session.id());
        if (loop != null) {
            loop.close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        loops.values().forEach(TableLoop::close);
        loops.clear();
    }

    private final class TableLoop {
        private final MahjongTableSession session;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<PluginTask> scheduledTask = new AtomicReference<>();

        private TableLoop(MahjongTableSession session) {
            this.session = session;
        }

        private void scheduleNext() {
            if (closed.get() || stopped.get()) {
                return;
            }
            PluginTask next = scheduler.runRegionDelayed(
                    session.center(), this::runOnce, CHECK_DELAY_TICKS);
            PluginTask previous = scheduledTask.getAndSet(next);
            if (previous != null && previous != next) {
                previous.cancel();
            }
            if ((closed.get() || stopped.get()) && scheduledTask.compareAndSet(next, null)) {
                next.cancel();
            }
        }

        private void runOnce() {
            scheduledTask.set(null);
            if (closed.get() || stopped.get() || loops.get(session.id()) != this) {
                return;
            }
            try {
                callback.accept(session);
            } catch (RuntimeException failure) {
                stopped.set(true);
                loops.remove(session.id(), this);
                failureSink.accept(session, failure);
                return;
            }
            scheduleNext();
        }

        private void close() {
            stopped.set(true);
            PluginTask task = scheduledTask.getAndSet(null);
            if (task != null) {
                task.cancel();
            }
        }
    }
}
