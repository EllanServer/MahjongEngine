package top.ellan.mahjong.craftengine.scene;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import top.ellan.mahjong.craftengine.port.RegionKey;
import top.ellan.mahjong.craftengine.port.RegionSchedulerPort;
import top.ellan.mahjong.craftengine.port.TableRegionResolver;
import top.ellan.mahjong.domain.table.TableId;

/** Fair per-region round-robin queue with a shared nanosecond and per-table mutation budget. */
final class SceneRegionDispatcher {
    private final ConcurrentHashMap<RegionKey, RegionState> regions = new ConcurrentHashMap<>();
    private final RegionSchedulerPort scheduler;
    private final TableRegionResolver regionsByTable;
    private final ConcurrentHashMap<TableId, CraftEngineTableState> tables;
    private final CraftEngineBackendConfig config;
    private final BooleanSupplier ready;
    private final Consumer<CraftEngineTableFailure> failureSink;
    private final SceneMutationProcessor mutations;

    SceneRegionDispatcher(
            RegionSchedulerPort scheduler,
            TableRegionResolver regionsByTable,
            ConcurrentHashMap<TableId, CraftEngineTableState> tables,
            CraftEngineBackendConfig config,
            BooleanSupplier ready,
            Consumer<CraftEngineTableFailure> failureSink,
            SceneMutationProcessor mutations) {
        this.scheduler = scheduler;
        this.regionsByTable = regionsByTable;
        this.tables = tables;
        this.config = config;
        this.ready = ready;
        this.failureSink = failureSink;
        this.mutations = mutations;
    }

    void markReady(TableId tableId, CraftEngineTableState table) {
        if (!ready.getAsBoolean()) {
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
                    failureSink.accept(new CraftEngineTableFailure(
                            tableId, table.desiredRevision, "region-table-capacity"));
                    return;
                }
                table.regionQueued = true;
            }
            region.ready.addLast(tableId);
            schedule(regionKey, region);
        }
    }

    void suspend() {
        regions.forEach(
                (ignored, region) -> {
                    synchronized (region) {
                        for (TableId tableId : region.ready) {
                            CraftEngineTableState table = tables.get(tableId);
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
    }

    private void schedule(RegionKey key, RegionState region) {
        if (region.scheduled) {
            return;
        }
        region.scheduled = true;
        try {
            scheduler.nextTick(key, () -> run(key, region));
        } catch (RuntimeException failure) {
            region.scheduled = false;
            List<TableId> affected = List.copyOf(region.ready);
            region.ready.clear();
            for (TableId tableId : affected) {
                CraftEngineTableState table = tables.get(tableId);
                if (table != null) {
                    synchronized (table) {
                        table.regionQueued = false;
                        table.failed = true;
                    }
                    failureSink.accept(new CraftEngineTableFailure(
                            tableId,
                            Math.max(0, table.desiredRevision),
                            "region-scheduler-" + failure.getClass().getSimpleName()));
                }
            }
        }
    }

    private void run(RegionKey key, RegionState region) {
        long started = System.nanoTime();
        long deadline = config.regionBudgetNanos() > Long.MAX_VALUE - started
                ? Long.MAX_VALUE
                : started + config.regionBudgetNanos();
        Map<TableId, Integer> perTable = new HashMap<>();
        ArrayDeque<TableId> nextTick = new ArrayDeque<>();
        synchronized (region) {
            region.scheduled = false;
            if (!ready.getAsBoolean()) {
                release(region);
                return;
            }
            while (ready.getAsBoolean()
                    && !region.ready.isEmpty()
                    && System.nanoTime() < deadline) {
                TableId tableId = region.ready.removeFirst();
                CraftEngineTableState table = tables.get(tableId);
                if (table == null) {
                    continue;
                }
                synchronized (table) {
                    table.regionQueued = false;
                }
                int used = perTable.getOrDefault(tableId, 0);
                if (used >= config.maxMutationsPerTablePerTick()) {
                    defer(nextTick, tableId, table);
                    continue;
                }
                SceneMutation mutation = mutations.next(table);
                if (mutation == null) {
                    mutations.installBindingsIfReady(tableId, table);
                    mutations.finishClosedTable(tableId, table);
                    continue;
                }
                boolean succeeded = mutations.apply(tableId, table, mutation);
                int nextUsed = succeeded ? used + 1 : config.maxMutationsPerTablePerTick();
                perTable.put(tableId, nextUsed);
                if (nextUsed >= config.maxMutationsPerTablePerTick()) {
                    defer(nextTick, tableId, table);
                } else {
                    requeue(region, tableId, table);
                }
            }
            region.ready.addAll(nextTick);
            region.scheduled = false;
            if (ready.getAsBoolean() && !region.ready.isEmpty()) {
                schedule(key, region);
            }
        }
    }

    private void release(RegionState region) {
        for (TableId tableId : region.ready) {
            CraftEngineTableState table = tables.get(tableId);
            if (table != null) {
                synchronized (table) {
                    table.regionQueued = false;
                }
            }
        }
        region.ready.clear();
    }

    private void requeue(
            RegionState region, TableId tableId, CraftEngineTableState table) {
        synchronized (table) {
            if (table.dirty.isEmpty() || table.failed || table.regionQueued) {
                mutations.finishClosedTable(tableId, table);
                return;
            }
            table.regionQueued = true;
        }
        region.ready.addLast(tableId);
    }

    private void defer(
            ArrayDeque<TableId> nextTick, TableId tableId, CraftEngineTableState table) {
        synchronized (table) {
            if (table.dirty.isEmpty() || table.failed || table.regionQueued) {
                mutations.finishClosedTable(tableId, table);
                return;
            }
            table.regionQueued = true;
        }
        nextTick.addLast(tableId);
    }

    private static final class RegionState {
        private final ArrayDeque<TableId> ready = new ArrayDeque<>();
        private boolean scheduled;
    }
}
