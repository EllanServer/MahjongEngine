package top.ellan.mahjong.craftengine.scene;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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
            if (table.failed) {
                return null;
            }
            var iterator = table.dirty.iterator();
            while (iterator.hasNext()) {
                SceneNodeId id = iterator.next();
                if (table.inFlight.contains(id)) {
                    continue;
                }
                SceneNode desired = table.desired.get(id);
                SceneNode actual = table.actual.get(id);
                boolean forced = table.forced.contains(id);
                if (!forced && Objects.equals(desired, actual)) {
                    iterator.remove();
                    continue;
                }
                iterator.remove();
                table.inFlight.add(id);
                return new SceneMutation(id, desired, table.applyEpoch);
            }
            return null;
        }
    }

    boolean apply(
            TableId tableId,
            CraftEngineTableState table,
            SceneMutation mutation,
            Runnable asyncWake) {
        CompletionStage<Void> result;
        try {
            result = mutation.desired() == null
                    ? gateway.remove(tableId, mutation.id())
                    : gateway.upsert(tableId, mutation.desired());
        } catch (RuntimeException failure) {
            return complete(tableId, table, mutation, failure, null);
        }
        if (result == null) {
            return complete(
                    tableId,
                    table,
                    mutation,
                    new IllegalStateException("CraftEngine mutation returned a null stage"),
                    null);
        }
        var future = result.toCompletableFuture();
        if (future.isDone()) {
            try {
                future.join();
                return complete(tableId, table, mutation, null, null);
            } catch (CompletionException failure) {
                return complete(tableId, table, mutation, unwrap(failure), null);
            }
        }
        future.whenComplete((ignored, failure) -> complete(
                tableId,
                table,
                mutation,
                unwrap(failure),
                asyncWake));
        return true;
    }

    private boolean complete(
            TableId tableId,
            CraftEngineTableState table,
            SceneMutation mutation,
            Throwable failure,
            Runnable asyncWake) {
        CraftEngineTableFailure report = null;
        boolean wake = false;
        synchronized (table) {
            if (!table.inFlight.remove(mutation.id())) {
                return failure == null;
            }
            if (mutation.applyEpoch() != table.applyEpoch) {
                wake = !table.dirty.isEmpty();
            } else if (failure != null) {
                table.failed = true;
                report = new CraftEngineTableFailure(
                        tableId,
                        Math.max(0, table.desiredRevision),
                        failure.getClass().getSimpleName());
            } else {
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
                wake = !table.dirty.isEmpty();
            }
        }
        if (report != null) {
            failureSink.accept(report);
            return false;
        }
        installBindingsIfReady(tableId, table);
        finishClosedTable(tableId, table);
        if (wake && asyncWake != null) {
            asyncWake.run();
        }
        return true;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    void finishClosedTable(TableId tableId, CraftEngineTableState table) {
        installBindingsIfReady(tableId, table);
        boolean removed = false;
        synchronized (table) {
            if (table.closed
                    && table.actual.isEmpty()
                    && table.dirty.isEmpty()
                    && table.inFlight.isEmpty()) {
                removed = tables.remove(tableId, table);
            }
        }
        if (removed) {
            gateway.tableClosed(tableId);
        }
    }

    void installBindingsIfReady(TableId tableId, CraftEngineTableState table) {
        synchronized (table) {
            if (!ready.getAsBoolean()
                    || table.closed
                    || table.failed
                    || !table.dirty.isEmpty()
                    || !table.inFlight.isEmpty()
                    || table.bindingsInstalled) {
                return;
            }
            interactions.replaceBindings(tableId, table.bindings);
            table.bindingsInstalled = true;
        }
    }
}
