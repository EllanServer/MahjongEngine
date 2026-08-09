package top.ellan.mahjong.craftengine.scene;

import java.util.Iterator;
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

    SceneMutation next(CraftEngineTableState table) {
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
                return new SceneMutation(id, desired, table.applyEpoch);
            }
            return null;
        }
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
