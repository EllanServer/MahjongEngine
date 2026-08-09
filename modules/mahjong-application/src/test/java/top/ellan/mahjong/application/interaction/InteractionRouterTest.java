package top.ellan.mahjong.application.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

final class InteractionRouterTest {
    private static final PlayerId PLAYER = new PlayerId(UUID.randomUUID());

    @Test
    void handTileRequiresSelectThenConfirmAndIgnoresDuplicateFurnitureEvent() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        RecordingSelectionPort projection = new RecordingSelectionPort();
        InteractionRouter router = new InteractionRouter(
                new TableActorRegistry(),
                projection,
                OverheadViewPort.DISABLED,
                nanos::get);
        TableId table = TableId.random();
        InteractionHandle handle = new InteractionHandle(UUID.randomUUID());
        TileInstanceId tile = new TileInstanceId(17);
        ActionToken token = new ActionToken(UUID.randomUUID(), PLAYER, 4);
        router.replaceBindings(
                table,
                List.of(InteractionRouteBinding.handTile(handle, PLAYER, token, tile)));

        assertEquals(
                TableActionCode.HAND_TILE_SELECTED,
                result(router.interact(handle, PLAYER, false)).code());
        assertEquals(List.of(new SelectionUpdate(table, PLAYER, Optional.of(tile))), projection.updates);

        nanos.addAndGet(InteractionRouter.DUPLICATE_CLICK_NANOS);
        assertEquals(
                TableActionCode.DUPLICATE_INTERACTION,
                result(router.interact(handle, PLAYER, false)).code());
        assertEquals(1, projection.updates.size());

        nanos.incrementAndGet();
        assertEquals(
                TableActionCode.TABLE_CLOSED,
                result(router.interact(handle, PLAYER, false)).code());
        assertEquals(
                new SelectionUpdate(table, PLAYER, Optional.empty()),
                projection.updates.getLast());
    }

    @Test
    void sneakingOnSelectedTileCancelsButSneakingOnAnotherTileSelectsIt() {
        AtomicLong nanos = new AtomicLong(2_000_000_000L);
        RecordingSelectionPort projection = new RecordingSelectionPort();
        InteractionRouter router = new InteractionRouter(
                new TableActorRegistry(),
                projection,
                OverheadViewPort.DISABLED,
                nanos::get);
        TableId table = TableId.random();
        InteractionHandle firstHandle = new InteractionHandle(UUID.randomUUID());
        InteractionHandle secondHandle = new InteractionHandle(UUID.randomUUID());
        TileInstanceId firstTile = new TileInstanceId(1);
        TileInstanceId secondTile = new TileInstanceId(2);
        router.replaceBindings(
                table,
                List.of(
                        InteractionRouteBinding.handTile(
                                firstHandle,
                                PLAYER,
                                new ActionToken(UUID.randomUUID(), PLAYER, 9),
                                firstTile),
                        InteractionRouteBinding.handTile(
                                secondHandle,
                                PLAYER,
                                new ActionToken(UUID.randomUUID(), PLAYER, 9),
                                secondTile)));

        assertEquals(
                TableActionCode.HAND_TILE_SELECTED,
                result(router.interact(firstHandle, PLAYER, false)).code());
        nanos.addAndGet(InteractionRouter.DUPLICATE_CLICK_NANOS + 1);
        assertEquals(
                TableActionCode.HAND_TILE_SELECTED,
                result(router.interact(secondHandle, PLAYER, true)).code());
        nanos.addAndGet(InteractionRouter.DUPLICATE_CLICK_NANOS + 1);
        assertEquals(
                TableActionCode.HAND_TILE_SELECTION_CANCELLED,
                result(router.interact(secondHandle, PLAYER, true)).code());

        assertEquals(
                List.of(
                        new SelectionUpdate(table, PLAYER, Optional.of(firstTile)),
                        new SelectionUpdate(table, PLAYER, Optional.of(secondTile)),
                        new SelectionUpdate(table, PLAYER, Optional.empty())),
                projection.updates);
    }

    @Test
    void replacingOneTablesBindingsClearsOnlyThatTablesSelection() {
        RecordingSelectionPort projection = new RecordingSelectionPort();
        InteractionRouter router = new InteractionRouter(
                new TableActorRegistry(),
                projection,
                OverheadViewPort.DISABLED,
                () -> 3_000_000_000L);
        TableId table = TableId.random();
        InteractionHandle handle = new InteractionHandle(UUID.randomUUID());
        router.replaceBindings(
                table,
                List.of(InteractionRouteBinding.handTile(
                        handle,
                        PLAYER,
                        new ActionToken(UUID.randomUUID(), PLAYER, 1),
                        new TileInstanceId(5))));
        result(router.interact(handle, PLAYER, false));

        router.replaceBindings(table, List.of());

        assertEquals(Optional.empty(), projection.updates.getLast().selectedTile());
        assertEquals(0, router.routeCount());
    }

    @Test
    void overheadViewIsRevisionBoundReadOnlyAndShiftCanExitIt() {
        RecordingOverheadViewPort overhead = new RecordingOverheadViewPort();
        InteractionRouter router = new InteractionRouter(
                new TableActorRegistry(),
                HandTileSelectionPort.NOOP,
                overhead,
                () -> 4_000_000_000L);
        TableId table = TableId.random();
        InteractionHandle viewHandle = new InteractionHandle(UUID.randomUUID());
        InteractionHandle actionHandle = new InteractionHandle(UUID.randomUUID());
        ActionToken actionToken = new ActionToken(UUID.randomUUID(), PLAYER, 12);
        router.replaceBindings(
                table,
                List.of(
                        InteractionRouteBinding.overhead(viewHandle, PLAYER, 12),
                        new InteractionRouteBinding(actionHandle, PLAYER, actionToken)));

        TableActionResult entered = result(router.interact(viewHandle, PLAYER, false));
        assertEquals(TableActionCode.OVERHEAD_VIEW_ENTERED, entered.code());
        assertEquals(12, entered.revision());
        assertEquals(
                TableActionCode.DUPLICATE_INTERACTION,
                result(router.interact(viewHandle, PLAYER, false)).code());
        assertEquals(
                TableActionCode.OVERHEAD_VIEW_READ_ONLY,
                result(router.interact(actionHandle, PLAYER, false)).code());

        assertEquals(true, router.exitOverhead(PLAYER));
        assertEquals(
                TableActionCode.TABLE_CLOSED,
                result(router.interact(actionHandle, PLAYER, false)).code());
    }

    private static TableActionResult result(
            java.util.concurrent.CompletionStage<TableActionResult> stage) {
        return stage.toCompletableFuture().join();
    }

    private static final class RecordingSelectionPort implements HandTileSelectionPort {
        private final List<SelectionUpdate> updates = new ArrayList<>();

        @Override
        public void showSelection(
                TableId tableId,
                PlayerId playerId,
                Optional<TileInstanceId> selectedTile) {
            updates.add(new SelectionUpdate(tableId, playerId, selectedTile));
        }
    }

    private static final class RecordingOverheadViewPort implements OverheadViewPort {
        private boolean active;

        @Override
        public CompletionStage<ToggleResult> toggle(
                TableId tableId, PlayerId playerId, long revision) {
            active = !active;
            return CompletableFuture.completedFuture(
                    active ? ToggleResult.ENTERED : ToggleResult.EXITED);
        }

        @Override
        public boolean active(PlayerId playerId) {
            return active;
        }

        @Override
        public boolean exit(PlayerId playerId) {
            boolean previous = active;
            active = false;
            return previous;
        }
    }

    private record SelectionUpdate(
            TableId tableId, PlayerId playerId, Optional<TileInstanceId> selectedTile) {}
}
