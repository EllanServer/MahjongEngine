package top.ellan.mahjong.craftengine.opening;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.opening.TableOpeningBatch;
import top.ellan.mahjong.application.opening.TableOpeningEffectPort;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.craftengine.scene.CraftEngineSceneBackend;
import top.ellan.mahjong.domain.table.TableId;

/**
 * Schedules only a few declarative CE variant changes per physical roll. There is no per-tick
 * scan, entity loop, custom model renderer, or blocking wait.
 */
public final class CraftEngineOpeningPresenter
        implements TableOpeningPresentationPort, AutoCloseable {
    private final TaskScheduler scheduler;
    private final OpeningOverlaySink overlays;
    private final CraftEngineOpeningAnimationConfig config;
    private final TableOpeningEffectPort effects;
    private final OpeningDiceFrameFactory frames;
    private final ConcurrentHashMap<TableId, Animation> active = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    public CraftEngineOpeningPresenter(
            TaskScheduler scheduler,
            CraftEngineSceneBackend backend,
            CraftEngineOpeningAnimationConfig config) {
        this(
                scheduler,
                Objects.requireNonNull(backend, "backend")::replaceTransient,
                config,
                TableOpeningEffectPort.NONE);
    }

    public CraftEngineOpeningPresenter(
            TaskScheduler scheduler,
            CraftEngineSceneBackend backend,
            CraftEngineOpeningAnimationConfig config,
            TableOpeningEffectPort effects) {
        this(
                scheduler,
                Objects.requireNonNull(backend, "backend")::replaceTransient,
                config,
                effects);
    }

    CraftEngineOpeningPresenter(
            TaskScheduler scheduler,
            OpeningOverlaySink overlays,
            CraftEngineOpeningAnimationConfig config) {
        this(scheduler, overlays, config, TableOpeningEffectPort.NONE);
    }

    CraftEngineOpeningPresenter(
            TaskScheduler scheduler,
            OpeningOverlaySink overlays,
            CraftEngineOpeningAnimationConfig config,
            TableOpeningEffectPort effects) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.overlays = Objects.requireNonNull(overlays, "overlays");
        this.config = Objects.requireNonNull(config, "config");
        this.effects = Objects.requireNonNull(effects, "effects");
        frames = new OpeningDiceFrameFactory(config);
    }

    @Override
    public void present(TableOpeningBatch batch) {
        Objects.requireNonNull(batch, "batch");
        Animation animation = new Animation(generations.getAndIncrement());
        Animation replaced = active.put(batch.tableId(), animation);
        if (replaced != null) {
            replaced.cancel();
        }
        try {
            show(batch, animation, 0, 0, false);
            signalRoll(batch, 0);
            Duration perRoll = config.rollDuration().plus(config.revealDuration());
            for (int roll = 0; roll < batch.opening().rolls().size(); roll++) {
                Duration base = perRoll.multipliedBy(roll);
                if (roll > 0) {
                    scheduleRollStart(batch, animation, base, roll);
                }
                for (int preview = 1; preview < config.previewFrames(); preview++) {
                    Duration offset = scaled(config.rollDuration(), preview, config.previewFrames());
                    schedule(batch, animation, base.plus(offset), roll, preview, false);
                }
                schedule(
                        batch,
                        animation,
                        base.plus(config.rollDuration()),
                        roll,
                        config.previewFrames(),
                        true);
            }
            animation.add(scheduler.schedule(
                    () -> finish(batch, animation),
                    perRoll.multipliedBy(batch.opening().rolls().size())));
        } catch (RuntimeException failure) {
            abort(batch.tableId(), animation);
        }
    }

    public void clear(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        Animation removed = active.remove(tableId);
        if (removed != null) {
            removed.cancel();
        }
        overlays.replace(
                tableId,
                generations.getAndIncrement(),
                frames.managedIds(),
                java.util.List.of());
    }

    private void schedule(
            TableOpeningBatch batch,
            Animation animation,
            Duration delay,
            int roll,
            int preview,
            boolean revealed) {
        animation.add(scheduler.schedule(
                () -> show(batch, animation, roll, preview, revealed), delay));
    }

    private void scheduleRollStart(
            TableOpeningBatch batch, Animation animation, Duration delay, int roll) {
        animation.add(scheduler.schedule(
                () -> {
                    if (active.get(batch.tableId()) != animation) {
                        return;
                    }
                    show(batch, animation, roll, 0, false);
                    signalRoll(batch, roll);
                },
                delay));
    }

    private void show(
            TableOpeningBatch batch,
            Animation animation,
            int roll,
            int preview,
            boolean revealed) {
        if (active.get(batch.tableId()) != animation) {
            return;
        }
        overlays.replace(
                batch.tableId(),
                animation.generation,
                frames.managedIds(),
                frames.frame(batch.opening(), roll, preview, revealed));
    }

    private void finish(TableOpeningBatch batch, Animation animation) {
        if (!active.remove(batch.tableId(), animation)) {
            return;
        }
        signalWallOpened(batch);
        overlays.replace(
                batch.tableId(), animation.generation, frames.managedIds(), java.util.List.of());
    }

    private void signalRoll(TableOpeningBatch batch, int roll) {
        try {
            effects.rollStarted(batch, roll);
        } catch (RuntimeException ignored) {
            // Audio or HUD feedback cannot abort the CE visual sequence.
        }
    }

    private void signalWallOpened(TableOpeningBatch batch) {
        try {
            effects.wallOpened(batch);
        } catch (RuntimeException ignored) {
            // Opening effects are cosmetic and isolated from the table.
        }
    }

    private void abort(TableId tableId, Animation animation) {
        if (active.remove(tableId, animation)) {
            animation.cancel();
            overlays.replace(
                    tableId,
                    animation.generation,
                    frames.managedIds(),
                    java.util.List.of());
        }
    }

    private static Duration scaled(Duration duration, int numerator, int denominator) {
        return Duration.ofNanos(duration.toNanos() / denominator * numerator);
    }

    @Override
    public void close() {
        active.forEach((ignored, animation) -> animation.cancel());
        active.clear();
    }

    private static final class Animation {
        private final long generation;
        private final ArrayList<Cancellable> tasks = new ArrayList<>(9);
        private boolean cancelled;

        private Animation(long generation) {
            this.generation = generation;
        }

        private synchronized void add(Cancellable task) {
            if (cancelled) {
                task.cancel();
                return;
            }
            tasks.add(task);
        }

        private synchronized void cancel() {
            cancelled = true;
            tasks.forEach(Cancellable::cancel);
            tasks.clear();
        }
    }
}
