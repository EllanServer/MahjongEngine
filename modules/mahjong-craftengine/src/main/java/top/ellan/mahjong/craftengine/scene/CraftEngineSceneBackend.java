package top.ellan.mahjong.craftengine.scene;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import top.ellan.mahjong.application.interaction.InteractionRouteBinding;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.craftengine.port.CraftEngineMutationGateway;
import top.ellan.mahjong.craftengine.port.RegionKey;
import top.ellan.mahjong.craftengine.port.RegionSchedulerPort;
import top.ellan.mahjong.craftengine.port.TableRegionResolver;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.presentation.SceneBackendPort;
import top.ellan.mahjong.presentation.SceneDiff;
import top.ellan.mahjong.presentation.SceneNode;
import top.ellan.mahjong.presentation.SceneNodeId;

/**
 * Latest-state CE backend. Region tasks round-robin dirty tables under one shared nanosecond budget;
 * failed mutations remain dirty for that table and cannot stop another table's progress.
 */
public final class CraftEngineSceneBackend implements SceneBackendPort {
    private final ConcurrentHashMap<TableId, TableState> tables = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, RegionState> regions = new ConcurrentHashMap<>();
    private final CraftEngineMutationGateway gateway;
    private final RegionSchedulerPort scheduler;
    private final TableRegionResolver regionsByTable;
    private final InteractionRouter interactions;
    private final CraftEngineBackendConfig config;
    private final Consumer<CraftEngineTableFailure> failureSink;
    private final AtomicBoolean ready = new AtomicBoolean();

    public CraftEngineSceneBackend(
            CraftEngineMutationGateway gateway,
            RegionSchedulerPort scheduler,
            TableRegionResolver regionsByTable,
            InteractionRouter interactions,
            CraftEngineBackendConfig config,
            Consumer<CraftEngineTableFailure> failureSink) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.regionsByTable = Objects.requireNonNull(regionsByTable, "regionsByTable");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.config = Objects.requireNonNull(config, "config");
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    @Override
    public void submit(SceneDiff diff) {
        Objects.requireNonNull(diff, "diff");
        TableState table = tables.computeIfAbsent(diff.tableId(), ignored -> new TableState());
        synchronized (table) {
            if (diff.toRevision() < table.desiredRevision) {
                return;
            }
            if (diff.toRevision() > table.desiredRevision) {
                table.failed = false;
            }
            table.bindingsInstalled = false;
            interactions.replaceBindings(diff.tableId(), List.of());
            for (SceneNodeId removal : diff.removals()) {
                table.desired.remove(removal);
                table.dirty.add(removal);
            }
            for (SceneNode upsert : diff.upserts()) {
                table.desired.put(upsert.id(), upsert);
                table.dirty.add(upsert.id());
            }
            if (table.desired.size() > config.maxNodesPerTable()) {
                table.failed = true;
                table.desiredRevision = diff.toRevision();
                failureSink.accept(
                        new CraftEngineTableFailure(
                                diff.tableId(), diff.toRevision(), "scene-node-capacity"));
                return;
            }
            table.desiredRevision = diff.toRevision();
            table.bindings =
                    diff.interactionBindings().stream()
                            .map(
                                    binding ->
                                            new InteractionRouteBinding(
                                                    binding.handle(),
                                                    binding.playerId(),
                                                    binding.revision(),
                                                    binding.purpose(),
                                                    binding.actionToken(),
                                                    binding.targetTile()))
                            .toList();
        }
        markReady(diff.tableId(), table);
        installBindingsIfReady(diff.tableId(), table);
    }

    /** Called only after CraftEngineReloadEvent confirms the atomically installed bundle is live. */
    public void onCraftEngineReloaded() {
        ready.set(true);
        tables.forEach(
                (tableId, table) -> {
                    synchronized (table) {
                        table.forced.addAll(table.actual.keySet());
                        table.forced.addAll(table.desired.keySet());
                        table.actual.clear();
                        table.dirty.addAll(table.forced);
                        table.failed = false;
                        table.applyEpoch++;
                        table.bindingsInstalled = false;
                        interactions.replaceBindings(tableId, List.of());
                    }
                    markReady(tableId, table);
                });
    }

    public void onCraftEngineReloadStarted() {
        ready.set(false);
        regions.forEach((ignored, region) -> {
            synchronized (region) {
                for (TableId tableId : region.ready) {
                    TableState table = tables.get(tableId);
                    if (table != null) {
                        synchronized (table) {
                            table.regionQueued = false;
                        }
                    }
                }
                region.ready.clear();
                region.scheduled = false;
            }
        });
        tables.forEach(
                (tableId, table) -> {
                    synchronized (table) {
                        table.applyEpoch++;
                        table.bindingsInstalled = false;
                        interactions.replaceBindings(tableId, List.of());
                    }
                });
    }

    public boolean ready() {
        return ready.get();
    }

    public void removeTable(TableId tableId) {
        TableState table = tables.get(Objects.requireNonNull(tableId, "tableId"));
        if (table == null) {
            return;
        }
        synchronized (table) {
            table.closed = true;
            table.failed = false;
            table.desired.clear();
            table.dirty.addAll(table.actual.keySet());
            table.bindingsInstalled = false;
            interactions.replaceBindings(tableId, List.of());
        }
        markReady(tableId, table);
    }

    public int trackedTables() {
        return tables.size();
    }

    private void markReady(TableId tableId, TableState table) {
        if (!ready.get()) {
            return;
        }
        RegionKey regionKey = regionsByTable.regionFor(tableId);
        RegionState region = regions.computeIfAbsent(regionKey, ignored -> new RegionState());
        synchronized (region) {
            synchronized (table) {
                if (table.regionQueued || table.dirty.isEmpty() || table.failed) {
                    return;
                }
                if (region.ready.size() >= config.maxTablesPerRegion()) {
                    table.failed = true;
                    failureSink.accept(
                            new CraftEngineTableFailure(
                                    tableId, table.desiredRevision, "region-table-capacity"));
                    return;
                }
                table.regionQueued = true;
            }
            region.ready.addLast(tableId);
            scheduleRegion(regionKey, region);
        }
    }

    private void scheduleRegion(RegionKey key, RegionState region) {
        if (region.scheduled) {
            return;
        }
        region.scheduled = true;
        try {
            scheduler.nextTick(key, () -> runRegion(key, region));
        } catch (RuntimeException failure) {
            region.scheduled = false;
            List<TableId> affected = List.copyOf(region.ready);
            region.ready.clear();
            for (TableId tableId : affected) {
                TableState table = tables.get(tableId);
                if (table != null) {
                    synchronized (table) {
                        table.regionQueued = false;
                        table.failed = true;
                    }
                    failureSink.accept(
                            new CraftEngineTableFailure(
                                    tableId,
                                    Math.max(0, table.desiredRevision),
                                    "region-scheduler-" + failure.getClass().getSimpleName()));
                }
            }
        }
    }

    private void runRegion(RegionKey key, RegionState region) {
        long started = System.nanoTime();
        long deadline =
                config.regionBudgetNanos() > Long.MAX_VALUE - started
                        ? Long.MAX_VALUE
                        : started + config.regionBudgetNanos();
        Map<TableId, Integer> perTable = new HashMap<>();
        ArrayDeque<TableId> nextTick = new ArrayDeque<>();
        synchronized (region) {
            region.scheduled = false;
            if (!ready.get()) {
                for (TableId tableId : region.ready) {
                    TableState table = tables.get(tableId);
                    if (table != null) {
                        synchronized (table) {
                            table.regionQueued = false;
                        }
                    }
                }
                region.ready.clear();
                return;
            }
            while (ready.get()
                    && !region.ready.isEmpty()
                    && System.nanoTime() < deadline) {
                TableId tableId = region.ready.removeFirst();
                TableState table = tables.get(tableId);
                if (table == null) {
                    continue;
                }
                synchronized (table) {
                    table.regionQueued = false;
                }
                int used = perTable.getOrDefault(tableId, 0);
                if (used >= config.maxMutationsPerTablePerTick()) {
                    deferToNextTick(nextTick, tableId, table);
                    continue;
                }
                Mutation mutation = nextMutation(table);
                if (mutation == null) {
                    installBindingsIfReady(tableId, table);
                    finishClosedTable(tableId, table);
                    continue;
                }
                boolean succeeded = apply(tableId, table, mutation);
                int nextUsed =
                        succeeded ? used + 1 : config.maxMutationsPerTablePerTick();
                perTable.put(tableId, nextUsed);
                if (nextUsed >= config.maxMutationsPerTablePerTick()) {
                    deferToNextTick(nextTick, tableId, table);
                } else {
                    requeue(region, tableId, table);
                }
            }
            region.ready.addAll(nextTick);
            region.scheduled = false;
            if (ready.get() && !region.ready.isEmpty()) {
                scheduleRegion(key, region);
            }
        }
    }

    private Mutation nextMutation(TableState table) {
        synchronized (table) {
            Iterator<SceneNodeId> iterator = table.dirty.iterator();
            while (iterator.hasNext()) {
                SceneNodeId id = iterator.next();
                SceneNode desired = table.desired.get(id);
                SceneNode actual = table.actual.get(id);
                boolean forced = table.forced.contains(id);
                if (!forced && Objects.equals(desired, actual)) {
                    iterator.remove();
                    continue;
                }
                return new Mutation(id, desired, table.applyEpoch);
            }
            return null;
        }
    }

    private boolean apply(TableId tableId, TableState table, Mutation mutation) {
        try {
            if (mutation.desired() == null) {
                gateway.remove(tableId, mutation.id());
            } else {
                gateway.upsert(tableId, mutation.desired());
            }
            synchronized (table) {
                if (mutation.applyEpoch() != table.applyEpoch) {
                    return true;
                }
                if (mutation.desired() == null) {
                    table.actual.remove(mutation.id());
                } else {
                    table.actual.put(mutation.id(), mutation.desired());
                }
                table.forced.remove(mutation.id());
                if (Objects.equals(table.desired.get(mutation.id()), mutation.desired())) {
                    table.dirty.remove(mutation.id());
                } else {
                    table.dirty.add(mutation.id());
                }
            }
            return true;
        } catch (RuntimeException failure) {
            synchronized (table) {
                if (mutation.applyEpoch() != table.applyEpoch) {
                    return true;
                }
                table.failed = true;
            }
            failureSink.accept(
                    new CraftEngineTableFailure(
                            tableId,
                            Math.max(0, table.desiredRevision),
                            failure.getClass().getSimpleName()));
            return false;
        }
    }

    private void requeue(RegionState region, TableId tableId, TableState table) {
        synchronized (table) {
            if (table.dirty.isEmpty() || table.failed || table.regionQueued) {
                finishClosedTable(tableId, table);
                return;
            }
            table.regionQueued = true;
        }
        region.ready.addLast(tableId);
    }

    private void deferToNextTick(
            ArrayDeque<TableId> nextTick, TableId tableId, TableState table) {
        synchronized (table) {
            if (table.dirty.isEmpty() || table.failed || table.regionQueued) {
                finishClosedTable(tableId, table);
                return;
            }
            table.regionQueued = true;
        }
        nextTick.addLast(tableId);
    }

    private void finishClosedTable(TableId tableId, TableState table) {
        installBindingsIfReady(tableId, table);
        synchronized (table) {
            if (table.closed && table.actual.isEmpty() && table.dirty.isEmpty()) {
                tables.remove(tableId, table);
            }
        }
    }

    private void installBindingsIfReady(TableId tableId, TableState table) {
        synchronized (table) {
            if (!ready.get()
                    || table.closed
                    || table.failed
                    || !table.dirty.isEmpty()
                    || table.bindingsInstalled) {
                return;
            }
            interactions.replaceBindings(tableId, table.bindings);
            table.bindingsInstalled = true;
        }
    }

    private static final class TableState {
        private final Map<SceneNodeId, SceneNode> desired = new LinkedHashMap<>();
        private final Map<SceneNodeId, SceneNode> actual = new LinkedHashMap<>();
        private final Set<SceneNodeId> dirty = new LinkedHashSet<>();
        private final Set<SceneNodeId> forced = new LinkedHashSet<>();
        private List<InteractionRouteBinding> bindings = List.of();
        private long desiredRevision = -1;
        private boolean regionQueued;
        private boolean failed;
        private boolean closed;
        private boolean bindingsInstalled;
        private long applyEpoch;
    }

    private static final class RegionState {
        private final ArrayDeque<TableId> ready = new ArrayDeque<>();
        private boolean scheduled;
    }

    private record Mutation(SceneNodeId id, SceneNode desired, long applyEpoch) {}
}
