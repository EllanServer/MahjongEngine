package top.ellan.mahjong.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Constant-time CE/Paper interaction routing. */
public final class InteractionRouter {
    static final long DUPLICATE_CLICK_NANOS = 40_000_000L;

    private final ConcurrentHashMap<RouteKey, Route> routes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableId, Set<RouteKey>> keysByTable =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, HandSelection> handSelections =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, OverheadClick> overheadClicks =
            new ConcurrentHashMap<>();
    private final TableActorRegistry actors;
    private final HandTileSelectionPort handProjection;
    private final OverheadViewPort overheadViews;
    private final LongSupplier nanoTime;

    public InteractionRouter(TableActorRegistry actors) {
        this(
                actors,
                HandTileSelectionPort.NOOP,
                OverheadViewPort.DISABLED,
                System::nanoTime);
    }

    public InteractionRouter(
            TableActorRegistry actors,
            HandTileSelectionPort handProjection,
            OverheadViewPort overheadViews) {
        this(actors, handProjection, overheadViews, System::nanoTime);
    }

    InteractionRouter(
            TableActorRegistry actors,
            HandTileSelectionPort handProjection,
            OverheadViewPort overheadViews,
            LongSupplier nanoTime) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.handProjection = Objects.requireNonNull(handProjection, "handProjection");
        this.overheadViews = Objects.requireNonNull(overheadViews, "overheadViews");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void bind(
            InteractionHandle handle, TableId tableId, PlayerId actor, ActionToken actionToken) {
        Objects.requireNonNull(handle, "handle");
        Route route = new Route(
                tableId,
                actor,
                actionToken.revision(),
                InteractionPurpose.RULE_ACTION,
                actionToken,
                null);
        RouteKey key = new RouteKey(handle, actor);
        routes.put(key, route);
        keysByTable.compute(
                tableId,
                (ignored, previous) -> withAdded(previous, key));
    }

    public void unbind(InteractionHandle handle, PlayerId playerId) {
        RouteKey key = new RouteKey(handle, playerId);
        Route removed = routes.remove(key);
        if (removed != null) {
            keysByTable.computeIfPresent(
                    removed.tableId(),
                    (ignored, previous) -> without(previous, key));
            HandSelection selection = handSelections.get(playerId);
            if (selection != null
                    && selection.handle().equals(handle)
                    && handSelections.remove(playerId, selection)) {
                project(SelectionChange.clear(selection.tableId(), playerId));
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
        Map<RouteKey, Route> replacements = new HashMap<>(bindings.size());
        for (InteractionRouteBinding binding : bindings) {
            RouteKey key = new RouteKey(binding.handle(), binding.playerId());
            Route route = new Route(
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
                    return replacements.isEmpty() ? null : Set.copyOf(replacements.keySet());
                });
        ArrayList<SelectionChange> selectionChanges = new ArrayList<>();
        for (Map.Entry<PlayerId, HandSelection> entry : handSelections.entrySet()) {
            HandSelection selection = entry.getValue();
            if (selection.tableId().equals(tableId)
                    && handSelections.remove(entry.getKey(), selection)) {
                selectionChanges.add(SelectionChange.clear(tableId, entry.getKey()));
            }
        }
        selectionChanges.forEach(this::project);
    }

    /**
     * Player-facing interaction semantics. Hand tiles use the original select/confirm gesture;
     * ordinary action buttons are submitted immediately.
     */
    public CompletionStage<TableActionResult> interact(
            InteractionHandle handle, PlayerId sender, boolean cancelSelection) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(sender, "sender");
        Route route = routes.get(new RouteKey(handle, sender));
        if (route == null) {
            return completed(TableActionCode.STALE_TOKEN, 0, "unknown-interaction");
        }
        if (route.purpose() == InteractionPurpose.OVERHEAD_VIEW) {
            long now = nanoTime.getAsLong();
            OverheadClick previous = overheadClicks.put(
                    sender, new OverheadClick(route.tableId(), handle, now));
            if (previous != null
                    && previous.tableId().equals(route.tableId())
                    && previous.handle().equals(handle)) {
                long elapsed = now - previous.clickedAtNanos();
                if (elapsed >= 0 && elapsed <= DUPLICATE_CLICK_NANOS) {
                    return completed(
                            TableActionCode.DUPLICATE_INTERACTION,
                            route.revision(),
                            "duplicate-overhead-click");
                }
            }
            clearSelection(sender);
            try {
                return overheadViews.toggle(route.tableId(), sender, route.revision())
                        .thenApply(
                                toggled -> switch (toggled) {
                                    case ENTERED -> result(
                                            TableActionCode.OVERHEAD_VIEW_ENTERED,
                                            route.revision(),
                                            "overhead-view-entered");
                                    case EXITED -> result(
                                            TableActionCode.OVERHEAD_VIEW_EXITED,
                                            route.revision(),
                                            "overhead-view-exited");
                                    case UNAVAILABLE -> result(
                                            TableActionCode.OVERHEAD_VIEW_UNAVAILABLE,
                                            route.revision(),
                                            "overhead-view-unavailable");
                                });
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        if (overheadViews.active(sender)) {
            return completed(
                    TableActionCode.OVERHEAD_VIEW_READ_ONLY,
                    route.revision(),
                    "return-to-seat-first");
        }
        if (route.purpose() != InteractionPurpose.HAND_TILE_ACTION) {
            clearSelection(sender);
            return submit(route, sender);
        }
        return interactWithHandTile(route, handle, sender, cancelSelection);
    }

    public CompletionStage<TableActionResult> route(InteractionHandle handle, PlayerId sender) {
        return interact(handle, sender, false);
    }

    /** Clears ephemeral input when a player disconnects or leaves the table. */
    public void clearPlayer(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        clearSelection(playerId);
        overheadClicks.remove(playerId);
        overheadViews.exit(playerId);
    }

    public boolean exitOverhead(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return overheadViews.exit(playerId);
    }

    private CompletionStage<TableActionResult> interactWithHandTile(
            Route route,
            InteractionHandle handle,
            PlayerId sender,
            boolean cancelSelection) {
        long now = nanoTime.getAsLong();
        while (true) {
            Route current = routes.get(new RouteKey(handle, sender));
            if (current != route) {
                return completed(TableActionCode.STALE_TOKEN, route.revision(), "stale-interaction");
            }
            HandSelection selected = handSelections.get(sender);
            if (selected != null
                    && selected.handle().equals(handle)
                    && selected.revision() == route.revision()) {
                long elapsed = now - selected.selectedAtNanos();
                if (elapsed >= 0 && elapsed <= DUPLICATE_CLICK_NANOS) {
                    return completed(
                            TableActionCode.DUPLICATE_INTERACTION,
                            route.revision(),
                            "duplicate-hand-click");
                }
                if (!handSelections.remove(sender, selected)) {
                    continue;
                }
                project(SelectionChange.clear(route.tableId(), sender));
                return cancelSelection
                        ? completed(
                                TableActionCode.HAND_TILE_SELECTION_CANCELLED,
                                route.revision(),
                                "hand-tile-selection-cancelled")
                        : submit(route, sender);
            }
            HandSelection next = new HandSelection(
                    route.tableId(),
                    handle,
                    route.targetTile(),
                    route.revision(),
                    now);
            boolean installed = selected == null
                    ? handSelections.putIfAbsent(sender, next) == null
                    : handSelections.replace(sender, selected, next);
            if (!installed) {
                continue;
            }
            if (routes.get(new RouteKey(handle, sender)) != route) {
                if (handSelections.remove(sender, next)) {
                    if (selected != null && !selected.tableId().equals(route.tableId())) {
                        project(SelectionChange.clear(selected.tableId(), sender));
                    }
                    project(SelectionChange.clear(route.tableId(), sender));
                }
                return completed(
                        TableActionCode.STALE_TOKEN,
                        route.revision(),
                        "stale-interaction");
            }
            if (selected != null && !selected.tableId().equals(route.tableId())) {
                project(SelectionChange.clear(selected.tableId(), sender));
            }
            project(SelectionChange.select(route.tableId(), sender, route.targetTile()));
            return completed(
                    TableActionCode.HAND_TILE_SELECTED,
                    route.revision(),
                    "hand-tile-selected");
        }
    }

    private CompletionStage<TableActionResult> submit(Route route, PlayerId sender) {
        if (route.actionToken() == null) {
            return completed(TableActionCode.STALE_TOKEN, route.revision(), "missing-action-token");
        }
        return actors.find(route.tableId())
                .<CompletionStage<TableActionResult>>map(
                        actor -> actor.submit(sender, route.actionToken()))
                .orElseGet(
                        () ->
                                CompletableFuture.completedFuture(
                                        new TableActionResult(
                                                TableActionCode.TABLE_CLOSED,
                                                route.revision(),
                                                "table-not-found")));
    }

    private void clearSelection(PlayerId playerId) {
        HandSelection removed = handSelections.remove(playerId);
        if (removed != null) {
            project(SelectionChange.clear(removed.tableId(), playerId));
        }
    }

    private void project(SelectionChange change) {
        if (change == null) {
            return;
        }
        try {
            handProjection.showSelection(
                    change.tableId(), change.playerId(), Optional.ofNullable(change.tile()));
        } catch (RuntimeException ignored) {
            // A private visual failure must not corrupt or stall table input state.
        }
    }

    private static CompletionStage<TableActionResult> completed(
            TableActionCode code, long revision, String reason) {
        return CompletableFuture.completedFuture(result(code, revision, reason));
    }

    private static TableActionResult result(
            TableActionCode code, long revision, String reason) {
        return new TableActionResult(code, revision, reason);
    }

    public int routeCount() {
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

    private record Route(
            TableId tableId,
            PlayerId actor,
            long revision,
            InteractionPurpose purpose,
            ActionToken actionToken,
            TileInstanceId targetTile) {
        private Route {
            Objects.requireNonNull(tableId, "tableId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(purpose, "purpose");
            if (revision < 0) {
                throw new IllegalArgumentException("route revision must be non-negative");
            }
        }
    }

    private record HandSelection(
            TableId tableId,
            InteractionHandle handle,
            TileInstanceId tile,
            long revision,
            long selectedAtNanos) {}

    private record OverheadClick(
            TableId tableId, InteractionHandle handle, long clickedAtNanos) {}

    private record SelectionChange(
            TableId tableId, PlayerId playerId, TileInstanceId tile) {
        static SelectionChange select(
                TableId tableId, PlayerId playerId, TileInstanceId tile) {
            return new SelectionChange(tableId, playerId, tile);
        }

        static SelectionChange clear(TableId tableId, PlayerId playerId) {
            return new SelectionChange(tableId, playerId, null);
        }
    }

    private record RouteKey(InteractionHandle handle, PlayerId playerId) {
        private RouteKey {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(playerId, "playerId");
        }
    }
}
