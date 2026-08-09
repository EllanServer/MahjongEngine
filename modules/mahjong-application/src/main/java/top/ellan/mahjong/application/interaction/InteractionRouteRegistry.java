package top.ellan.mahjong.application.interaction;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** Lock-free route lookup plus bounded per-table replacement index. */
final class InteractionRouteRegistry {
    private static final int MAX_ROUTES_PER_TABLE = 256;

    private final ConcurrentHashMap<RouteKey, InteractionRoute> routes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableId, Set<RouteKey>> keysByTable =
            new ConcurrentHashMap<>();

    void bind(
            InteractionHandle handle, TableId tableId, PlayerId actor, ActionToken actionToken) {
        Objects.requireNonNull(actionToken, "actionToken");
        RouteKey key = new RouteKey(handle, actor);
        routes.put(
                key,
                new InteractionRoute(
                        tableId,
                        actor,
                        actionToken.revision(),
                        InteractionPurpose.RULE_ACTION,
                        actionToken,
                        null));
        keysByTable.compute(tableId, (ignored, previous) -> withAdded(previous, key));
    }

    boolean unbind(InteractionHandle handle, PlayerId playerId) {
        RouteKey key = new RouteKey(handle, playerId);
        InteractionRoute removed = routes.remove(key);
        if (removed == null) {
            return false;
        }
        keysByTable.computeIfPresent(
                removed.tableId(), (ignored, previous) -> without(previous, key));
        return true;
    }

    void replace(TableId tableId, Collection<InteractionRouteBinding> bindings) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(bindings, "bindings");
        if (bindings.size() > MAX_ROUTES_PER_TABLE) {
            throw new IllegalArgumentException(
                    "A table cannot expose more than " + MAX_ROUTES_PER_TABLE
                            + " interaction routes");
        }
        Map<RouteKey, InteractionRoute> replacements = new HashMap<>(bindings.size());
        for (InteractionRouteBinding binding : bindings) {
            RouteKey key = new RouteKey(binding.handle(), binding.playerId());
            InteractionRoute route = new InteractionRoute(
                    tableId,
                    binding.playerId(),
                    binding.revision(),
                    binding.purpose(),
                    binding.actionToken(),
                    binding.targetTile());
            if (replacements.putIfAbsent(key, route) != null) {
                throw new IllegalArgumentException("Duplicate interaction route binding");
            }
        }
        keysByTable.compute(
                tableId,
                (ignored, previous) -> {
                    if (previous != null) {
                        previous.forEach(routes::remove);
                    }
                    routes.putAll(replacements);
                    return replacements.isEmpty()
                            ? null
                            : Set.copyOf(replacements.keySet());
                });
    }

    InteractionRoute find(InteractionHandle handle, PlayerId playerId) {
        return routes.get(new RouteKey(handle, playerId));
    }

    boolean isCurrent(
            InteractionHandle handle, PlayerId playerId, InteractionRoute expected) {
        return routes.get(new RouteKey(handle, playerId)) == expected;
    }

    int size() {
        return routes.size();
    }

    private static Set<RouteKey> withAdded(Set<RouteKey> previous, RouteKey key) {
        if (previous == null || previous.isEmpty()) {
            return Set.of(key);
        }
        HashSet<RouteKey> copy = new HashSet<>(previous);
        copy.add(key);
        return Set.copyOf(copy);
    }

    private static Set<RouteKey> without(Set<RouteKey> previous, RouteKey key) {
        if (!previous.contains(key)) {
            return previous;
        }
        if (previous.size() == 1) {
            return null;
        }
        HashSet<RouteKey> copy = new HashSet<>(previous);
        copy.remove(key);
        return Set.copyOf(copy);
    }

    private record RouteKey(InteractionHandle handle, PlayerId playerId) {
        private RouteKey {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(playerId, "playerId");
        }
    }
}
