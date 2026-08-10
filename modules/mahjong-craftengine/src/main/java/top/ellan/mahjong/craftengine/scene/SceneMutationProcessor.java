package top.ellan.mahjong.craftengine.scene;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import top.ellan.mahjong.application.interaction.InteractionRouter;
import top.ellan.mahjong.craftengine.port.CraftEngineMutationGateway;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** Applies one node change at a time and keeps failures isolated to the owning table. */
final class SceneMutationProcessor {
    private final CraftEngineMutationGateway gateway;
    private final InteractionRouter interactions;
    private final ConcurrentHashMap<TableId, CraftEngineTableState> tables;
    private final BooleanSupplier ready;
    private final Consumer<CraftEngineTableFailure> failureSink;

    SceneMutationProcessor(
            CraftEngineMutationGateway gateway,
            InteractionRouter interactions,
            ConcurrentHashMap<TableId, CraftEngineTableState> tables,
            BooleanSupplier ready,
            Consumer<CraftEngineTableFailure> failureSink) {
        this.gateway = gateway;
        this.interactions = interactions;
        this.tables = tables;
        this.ready = ready;
        this.failureSink = failureSink;
    }

    /**
     * Pops the head of the dirty queue instead of rescanning it from the start on every call.
     * Entries that already match (and are not forced) are stale leftovers and are dropped;
     * {@link #apply} re-adds an entry only when its desired changed mid-flight, so every dirty
     * entry is visited at most once per drain.
     */
    SceneMutation next(CraftEngineTableState table) {
        synchronized (table) {
            while (true) {
                SceneNodeId id = firstDirty(table);
                if (id == null) {
                    return null;
                }
                table.dirty.remove(id);
                SceneNode desired = table.desired.get(id);
                SceneNode actual = table.actual.get(id);
                boolean forced = table.forced.contains(id);
                if (!forced && Objects.equals(desired, actual)) {
                    continue;
                }
                return new SceneMutation(id, desired, table.applyEpoch);
            }
        }
    }

    private static SceneNodeId firstDirty(CraftEngineTableState table) {
        return table.dirty.isEmpty() ? null : table.dirty.iterator().next();
    }

    boolean apply(TableId tableId, CraftEngineTableState table, SceneMutation mutation) {
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
            failureSink.accept(new CraftEngineTableFailure(
                    tableId,
                    Math.max(0, table.desiredRevision),
                    failure.getClass().getSimpleName()));
            return false;
        }
    }

    void finishClosedTable(TableId tableId, CraftEngineTableState table) {
        installBindingsIfReady(tableId, table);
        synchronized (table) {
            if (table.closed && table.actual.isEmpty() && table.dirty.isEmpty()) {
                tables.remove(tableId, table);
            }
        }
    }

    void installBindingsIfReady(TableId tableId, CraftEngineTableState table) {
        synchronized (table) {
            if (!ready.getAsBoolean()
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
}
