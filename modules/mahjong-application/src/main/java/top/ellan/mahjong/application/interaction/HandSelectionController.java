package top.ellan.mahjong.application.interaction;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Owns the select/confirm hand gesture and its private visual. */
final class HandSelectionController {
    private final ConcurrentHashMap<PlayerId, HandSelection> selections =
            new ConcurrentHashMap<>();
    private final HandTileSelectionPort projection;
    private final LongSupplier nanoTime;

    HandSelectionController(
            HandTileSelectionPort projection, LongSupplier nanoTime) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    CompletionStage<TableActionResult> interact(
            InteractionRoute route,
            InteractionHandle handle,
            PlayerId sender,
            boolean cancelSelection,
            InteractionRouteRegistry routes,
            Supplier<CompletionStage<TableActionResult>> submit) {
        long now = nanoTime.getAsLong();
        while (true) {
            if (!routes.isCurrent(handle, sender, route)) {
                return InteractionResults.completed(
                        TableActionCode.STALE_TOKEN,
                        route.revision(),
                        "stale-interaction");
            }
            HandSelection selected = selections.get(sender);
            if (sameSelection(selected, handle, route.revision())) {
                long elapsed = now - selected.selectedAtNanos();
                if (elapsed >= 0 && elapsed <= InteractionRouter.DUPLICATE_CLICK_NANOS) {
                    return InteractionResults.completed(
                            TableActionCode.DUPLICATE_INTERACTION,
                            route.revision(),
                            "duplicate-hand-click");
                }
                if (!selections.remove(sender, selected)) {
                    continue;
                }
                project(SelectionChange.clear(route.tableId(), sender));
                return cancelSelection
                        ? InteractionResults.completed(
                                TableActionCode.HAND_TILE_SELECTION_CANCELLED,
                                route.revision(),
                                "hand-tile-selection-cancelled")
                        : submit.get();
            }
            HandSelection next = new HandSelection(
                    route.tableId(),
                    handle,
                    route.targetTile(),
                    route.revision(),
                    now);
            boolean installed = selected == null
                    ? selections.putIfAbsent(sender, next) == null
                    : selections.replace(sender, selected, next);
            if (!installed) {
                continue;
            }
            if (!routes.isCurrent(handle, sender, route)) {
                clearStaleInstall(sender, selected, next, route.tableId());
                return InteractionResults.completed(
                        TableActionCode.STALE_TOKEN,
                        route.revision(),
                        "stale-interaction");
            }
            if (selected != null && !selected.tableId().equals(route.tableId())) {
                project(SelectionChange.clear(selected.tableId(), sender));
            }
            project(SelectionChange.select(route.tableId(), sender, route.targetTile()));
            return InteractionResults.completed(
                    TableActionCode.HAND_TILE_SELECTED,
                    route.revision(),
                    "hand-tile-selected");
        }
    }

    void clearIfHandle(PlayerId playerId, InteractionHandle handle) {
        HandSelection selection = selections.get(playerId);
        if (selection != null
                && selection.handle().equals(handle)
                && selections.remove(playerId, selection)) {
            project(SelectionChange.clear(selection.tableId(), playerId));
        }
    }

    void clearTable(TableId tableId) {
        ArrayList<SelectionChange> changes = new ArrayList<>();
        for (Map.Entry<PlayerId, HandSelection> entry : selections.entrySet()) {
            HandSelection selection = entry.getValue();
            if (selection.tableId().equals(tableId)
                    && selections.remove(entry.getKey(), selection)) {
                changes.add(SelectionChange.clear(tableId, entry.getKey()));
            }
        }
        changes.forEach(this::project);
    }

    void clear(PlayerId playerId) {
        HandSelection removed = selections.remove(playerId);
        if (removed != null) {
            project(SelectionChange.clear(removed.tableId(), playerId));
        }
    }

    private void clearStaleInstall(
            PlayerId sender,
            HandSelection selected,
            HandSelection installed,
            TableId tableId) {
        if (!selections.remove(sender, installed)) {
            return;
        }
        if (selected != null && !selected.tableId().equals(tableId)) {
            project(SelectionChange.clear(selected.tableId(), sender));
        }
        project(SelectionChange.clear(tableId, sender));
    }

    private void project(SelectionChange change) {
        try {
            projection.showSelection(
                    change.tableId(),
                    change.playerId(),
                    Optional.ofNullable(change.tile()));
        } catch (RuntimeException ignored) {
            // A private visual failure must not corrupt or stall table input state.
        }
    }

    private static boolean sameSelection(
            HandSelection selected, InteractionHandle handle, long revision) {
        return selected != null
                && selected.handle().equals(handle)
                && selected.revision() == revision;
    }

    private record HandSelection(
            TableId tableId,
            InteractionHandle handle,
            TileInstanceId tile,
            long revision,
            long selectedAtNanos) {}

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
}
