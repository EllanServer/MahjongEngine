package top.ellan.mahjong.presentation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import top.ellan.mahjong.application.SceneProjectionPort;
import top.ellan.mahjong.application.TableProjection;
import top.ellan.mahjong.application.TaskScheduler;
import top.ellan.mahjong.domain.TableId;

/** Per-table latest-only scene precomputation. Superseded frames are discarded before mapping. */
public final class LatestSceneProjector implements SceneProjectionPort {
    private final ConcurrentHashMap<TableId, Slot> slots = new ConcurrentHashMap<>();
    private final Executor precomputeExecutor;
    private final TableSceneMapper mapper;
    private final SceneBackendPort backend;
    private final SceneGraphDiffer differ;
    private final TaskScheduler retries;

    public LatestSceneProjector(
            Executor precomputeExecutor,
            TableSceneMapper mapper,
            SceneBackendPort backend,
            SceneGraphDiffer differ,
            TaskScheduler retries) {
        this.precomputeExecutor = Objects.requireNonNull(precomputeExecutor, "precomputeExecutor");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.differ = Objects.requireNonNull(differ, "differ");
        this.retries = Objects.requireNonNull(retries, "retries");
    }

    @Override
    public void publish(TableProjection projection) {
        Objects.requireNonNull(projection, "projection");
        Slot slot = slots.computeIfAbsent(projection.tableId(), ignored -> new Slot());
        long previous = slot.acceptedRevision.get();
        while (projection.revision() >= previous) {
            if (slot.acceptedRevision.compareAndSet(previous, projection.revision())) {
                slot.latest.set(projection);
                schedule(projection.tableId(), slot);
                return;
            }
            previous = slot.acceptedRevision.get();
        }
    }

    public void remove(TableId tableId) {
        Slot slot = slots.remove(Objects.requireNonNull(tableId, "tableId"));
        if (slot == null) {
            return;
        }
        SceneGraph previous;
        synchronized (slot) {
            previous = slot.applied;
            slot.latest.set(null);
        }
        if (previous != null) {
            backend.submit(differ.diff(previous, SceneGraph.empty(tableId, previous.revision() + 1)));
        }
    }

    public int trackedTables() {
        return slots.size();
    }

    private void schedule(TableId tableId, Slot slot) {
        if (!slot.running.compareAndSet(false, true)) {
            return;
        }
        try {
            precomputeExecutor.execute(() -> processOne(tableId, slot));
        } catch (RejectedExecutionException failure) {
            slot.running.set(false);
            scheduleRetry(tableId, slot);
        }
    }

    private void scheduleRetry(TableId tableId, Slot slot) {
        if (slot.latest.get() == null
                || slots.get(tableId) != slot
                || !slot.retryScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            retries.schedule(
                    () -> {
                        slot.retryScheduled.set(false);
                        if (slot.latest.get() != null && slots.get(tableId) == slot) {
                            schedule(tableId, slot);
                        }
                    },
                    Duration.ofMillis(10));
        } catch (RejectedExecutionException failure) {
            slot.retryScheduled.set(false);
        }
    }

    private void processOne(TableId tableId, Slot slot) {
        try {
            TableProjection projection = slot.latest.getAndSet(null);
            if (projection == null || slots.get(tableId) != slot) {
                return;
            }
            SceneGraph next = mapper.map(projection);
            SceneDiff diff;
            synchronized (slot) {
                SceneGraph previous =
                        slot.applied == null
                                ? SceneGraph.empty(tableId, -1 + 1)
                                : slot.applied;
                if (slot.applied != null && next.revision() < slot.applied.revision()) {
                    return;
                }
                diff =
                        slot.applied == null
                                ? new SceneDiff(
                                        tableId,
                                        -1,
                                        next.revision(),
                                        java.util.List.of(),
                                        next.nodes().values().stream()
                                                .sorted(java.util.Comparator.comparing(SceneNode::id))
                                                .toList(),
                                        next.interactionBindings())
                                : differ.diff(previous, next);
                slot.applied = next;
            }
            backend.submit(diff);
        } finally {
            slot.running.set(false);
            if (slot.latest.get() != null && slots.get(tableId) == slot) {
                schedule(tableId, slot);
            }
        }
    }

    private static final class Slot {
        private final AtomicReference<TableProjection> latest = new AtomicReference<>();
        private final AtomicLong acceptedRevision = new AtomicLong(-1);
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean retryScheduled = new AtomicBoolean();
        private SceneGraph applied;
    }
}
