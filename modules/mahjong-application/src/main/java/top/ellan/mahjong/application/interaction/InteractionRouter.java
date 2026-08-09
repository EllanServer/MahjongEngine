package top.ellan.mahjong.application.interaction;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** Constant-time CE/Paper interaction routing facade. */
public final class InteractionRouter {
    static final long DUPLICATE_CLICK_NANOS = 40_000_000L;

    private final InteractionRouteRegistry routes = new InteractionRouteRegistry();
    private final HandSelectionController handSelections;
    private final OverheadInteractionController overhead;
    private final TableActorRegistry actors;

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
        Objects.requireNonNull(nanoTime, "nanoTime");
        handSelections = new HandSelectionController(handProjection, nanoTime);
        overhead = new OverheadInteractionController(overheadViews, nanoTime);
    }

    public void bind(
            InteractionHandle handle, TableId tableId, PlayerId actor, ActionToken actionToken) {
        routes.bind(handle, tableId, actor, actionToken);
    }

    public void unbind(InteractionHandle handle, PlayerId playerId) {
        if (routes.unbind(handle, playerId)) {
            handSelections.clearIfHandle(playerId, handle);
        }
    }

    /** Replaces one table's bounded route set after a scene revision is accepted. */
    public void replaceBindings(
            TableId tableId, Collection<InteractionRouteBinding> bindings) {
        routes.replace(tableId, bindings);
        handSelections.clearTable(tableId);
    }

    /** Preserves the select/confirm hand gesture while action buttons submit immediately. */
    public CompletionStage<TableActionResult> interact(
            InteractionHandle handle, PlayerId sender, boolean cancelSelection) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(sender, "sender");
        InteractionRoute route = routes.find(handle, sender);
        if (route == null) {
            return InteractionResults.completed(
                    TableActionCode.STALE_TOKEN, 0, "unknown-interaction");
        }
        if (route.purpose() == InteractionPurpose.OVERHEAD_VIEW) {
            return overhead.toggle(
                    route, handle, sender, () -> handSelections.clear(sender));
        }
        if (overhead.active(sender)) {
            return InteractionResults.completed(
                    TableActionCode.OVERHEAD_VIEW_READ_ONLY,
                    route.revision(),
                    "return-to-seat-first");
        }
        if (route.purpose() != InteractionPurpose.HAND_TILE_ACTION) {
            handSelections.clear(sender);
            return submit(route, sender);
        }
        return handSelections.interact(
                route,
                handle,
                sender,
                cancelSelection,
                routes,
                () -> submit(route, sender));
    }

    public CompletionStage<TableActionResult> route(
            InteractionHandle handle, PlayerId sender) {
        return interact(handle, sender, false);
    }

    /** Clears ephemeral input when a player disconnects or leaves the table. */
    public void clearPlayer(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        handSelections.clear(playerId);
        overhead.clearPlayer(playerId);
    }

    public boolean exitOverhead(PlayerId playerId) {
        return overhead.exit(Objects.requireNonNull(playerId, "playerId"));
    }

    public int routeCount() {
        return routes.size();
    }

    private CompletionStage<TableActionResult> submit(
            InteractionRoute route, PlayerId sender) {
        if (route.actionToken() == null) {
            return InteractionResults.completed(
                    TableActionCode.STALE_TOKEN,
                    route.revision(),
                    "missing-action-token");
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
}
