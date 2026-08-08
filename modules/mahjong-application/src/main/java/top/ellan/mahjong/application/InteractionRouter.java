package top.ellan.mahjong.application;

import java.util.Objects;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** Constant-time CE/Paper interaction routing. */
public final class InteractionRouter {
    private final ConcurrentHashMap<RouteKey, Route> routes = new ConcurrentHashMap<>();
    private final Map<TableId, Set<RouteKey>> keysByTable = new java.util.HashMap<>();
    private final TableActorRegistry actors;

    public InteractionRouter(TableActorRegistry actors) {
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    public void bind(
            InteractionHandle handle, TableId tableId, PlayerId actor, ActionToken actionToken) {
        Objects.requireNonNull(handle, "handle");
        Route route = new Route(tableId, actor, actionToken);
        RouteKey key = new RouteKey(handle, actor);
        routes.put(key, route);
        synchronized (keysByTable) {
            keysByTable.computeIfAbsent(tableId, ignored -> new HashSet<>()).add(key);
        }
    }

    public void unbind(InteractionHandle handle, PlayerId playerId) {
        RouteKey key = new RouteKey(handle, playerId);
        Route removed = routes.remove(key);
        if (removed != null) {
            synchronized (keysByTable) {
                Set<RouteKey> keys = keysByTable.get(removed.tableId());
                if (keys != null) {
                    keys.remove(key);
                    if (keys.isEmpty()) {
                        keysByTable.remove(removed.tableId());
                    }
                }
            }
        }
    }

    /** Replaces one table's bounded route set after a scene revision is accepted. */
    public void replaceBindings(
            TableId tableId, Collection<InteractionRouteBinding> bindings) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(bindings, "bindings");
        if (bindings.size() > 256) {
            throw new IllegalArgumentException("A table cannot expose more than 256 interaction routes");
        }
        synchronized (keysByTable) {
            Set<RouteKey> previous = keysByTable.remove(tableId);
            if (previous != null) {
                previous.forEach(routes::remove);
            }
            Set<RouteKey> next = new HashSet<>();
            for (InteractionRouteBinding binding : bindings) {
                RouteKey key = new RouteKey(binding.handle(), binding.playerId());
                if (!next.add(key)) {
                    throw new IllegalArgumentException("Duplicate interaction route binding");
                }
                routes.put(
                        key,
                        new Route(tableId, binding.playerId(), binding.actionToken()));
            }
            if (!next.isEmpty()) {
                keysByTable.put(tableId, next);
            }
        }
    }

    public CompletionStage<TableActionResult> route(InteractionHandle handle, PlayerId sender) {
        Objects.requireNonNull(sender, "sender");
        Route route = routes.get(new RouteKey(Objects.requireNonNull(handle, "handle"), sender));
        if (route == null) {
            return CompletableFuture.completedFuture(
                    new TableActionResult(TableActionCode.STALE_TOKEN, 0, "unknown-interaction"));
        }
        return actors.find(route.tableId())
                .<CompletionStage<TableActionResult>>map(
                        actor -> actor.submit(sender, route.token()))
                .orElseGet(
                        () ->
                                CompletableFuture.completedFuture(
                                        new TableActionResult(
                                                TableActionCode.TABLE_CLOSED,
                                                route.token().revision(),
                                                "table-not-found")));
    }

    public int routeCount() {
        return routes.size();
    }

    private record Route(TableId tableId, PlayerId actor, ActionToken token) {
        private Route {
            Objects.requireNonNull(tableId, "tableId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(token, "token");
        }
    }

    private record RouteKey(InteractionHandle handle, PlayerId playerId) {
        private RouteKey {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(playerId, "playerId");
        }
    }
}
