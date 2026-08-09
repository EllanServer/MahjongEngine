package top.ellan.mahjong.application.interaction;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;

/** Owns duplicate suppression and read-only lifecycle for the overhead camera gesture. */
final class OverheadInteractionController {
    private final ConcurrentHashMap<PlayerId, OverheadClick> clicks =
            new ConcurrentHashMap<>();
    private final OverheadViewPort views;
    private final LongSupplier nanoTime;

    OverheadInteractionController(OverheadViewPort views, LongSupplier nanoTime) {
        this.views = Objects.requireNonNull(views, "views");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    CompletionStage<TableActionResult> toggle(
            InteractionRoute route,
            InteractionHandle handle,
            PlayerId sender,
            Runnable beforeToggle) {
        Objects.requireNonNull(beforeToggle, "beforeToggle");
        long now = nanoTime.getAsLong();
        OverheadClick previous = clicks.put(
                sender, new OverheadClick(route.tableId(), handle, now));
        if (isDuplicate(previous, route.tableId(), handle, now)) {
            return InteractionResults.completed(
                    TableActionCode.DUPLICATE_INTERACTION,
                    route.revision(),
                    "duplicate-overhead-click");
        }
        beforeToggle.run();
        try {
            return views.toggle(route.tableId(), sender, route.revision())
                    .thenApply(result -> toggleResult(route.revision(), result));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    boolean active(PlayerId playerId) {
        return views.active(playerId);
    }

    boolean exit(PlayerId playerId) {
        return views.exit(playerId);
    }

    void clearPlayer(PlayerId playerId) {
        clicks.remove(playerId);
        views.exit(playerId);
    }

    private static boolean isDuplicate(
            OverheadClick previous,
            TableId tableId,
            InteractionHandle handle,
            long now) {
        if (previous == null
                || !previous.tableId().equals(tableId)
                || !previous.handle().equals(handle)) {
            return false;
        }
        long elapsed = now - previous.clickedAtNanos();
        return elapsed >= 0 && elapsed <= InteractionRouter.DUPLICATE_CLICK_NANOS;
    }

    private static TableActionResult toggleResult(
            long revision, OverheadViewPort.ToggleResult toggled) {
        return switch (toggled) {
            case ENTERED -> InteractionResults.result(
                    TableActionCode.OVERHEAD_VIEW_ENTERED,
                    revision,
                    "overhead-view-entered");
            case EXITED -> InteractionResults.result(
                    TableActionCode.OVERHEAD_VIEW_EXITED,
                    revision,
                    "overhead-view-exited");
            case UNAVAILABLE -> InteractionResults.result(
                    TableActionCode.OVERHEAD_VIEW_UNAVAILABLE,
                    revision,
                    "overhead-view-unavailable");
        };
    }

    private record OverheadClick(
            TableId tableId, InteractionHandle handle, long clickedAtNanos) {}
}
