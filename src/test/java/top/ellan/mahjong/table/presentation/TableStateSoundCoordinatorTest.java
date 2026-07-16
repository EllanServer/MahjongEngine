package top.ellan.mahjong.table.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.table.core.TableSessionContext;
import top.ellan.mahjong.table.core.round.TableRoundController;

class TableStateSoundCoordinatorTest {
    @Test
    void playsDrawSoundWhenWallCountDropsAfterInitialSync() {
        TableSessionContext session = mock(TableSessionContext.class);
        TableRoundController controller = mock(TableRoundController.class);
        Player viewer = mock(Player.class);
        Location location = mock(Location.class);
        TableStateSoundCoordinator coordinator = new TableStateSoundCoordinator(session);

        when(session.hasRoundController()).thenReturn(true);
        when(session.roundControllerInternal()).thenReturn(controller);
        when(session.viewers()).thenReturn(List.of(viewer));
        when(session.isStarted()).thenReturn(true);
        when(session.currentSeat()).thenReturn(null);
        when(session.pendingReactionFingerprint()).thenReturn("");
        when(session.lastResolution()).thenReturn(null);
        when(controller.started()).thenReturn(true);
        when(viewer.getLocation()).thenReturn(location);

        when(controller.remainingWallCount()).thenReturn(70);
        coordinator.syncStateSounds();
        verify(viewer, never()).playSound(location, "mahjongcraft:tile_draw", 0.65F, 1.05F);

        when(controller.remainingWallCount()).thenReturn(69);
        coordinator.syncStateSounds();

        verify(viewer).playSound(location, "mahjongcraft:tile_draw", 0.65F, 1.05F);
    }

    @Test
    void playsDiscardSoundOnDemand() {
        TableSessionContext session = mock(TableSessionContext.class);
        Player viewer = mock(Player.class);
        Location location = mock(Location.class);
        TableStateSoundCoordinator coordinator = new TableStateSoundCoordinator(session);

        when(session.viewers()).thenReturn(List.of(viewer));
        when(viewer.getLocation()).thenReturn(location);

        coordinator.playDiscardSound();

        verify(viewer).playSound(location, "mahjongcraft:tile_discard", 0.75F, 1.05F);
    }

    @Test
    void playsRiichiSoundOnlyOnDemandInsteadOfDuringStateSync() {
        TableSessionContext session = mock(TableSessionContext.class);
        TableRoundController controller = mock(TableRoundController.class);
        Player viewer = mock(Player.class);
        Location location = mock(Location.class);
        TableStateSoundCoordinator coordinator = new TableStateSoundCoordinator(session);

        when(session.hasRoundController()).thenReturn(true);
        when(session.roundControllerInternal()).thenReturn(controller);
        when(session.viewers()).thenReturn(List.of(viewer));
        when(session.currentSeat()).thenReturn(null);
        when(session.lastResolution()).thenReturn(null);
        when(viewer.getLocation()).thenReturn(location);

        coordinator.syncStateSounds();
        verify(viewer, never()).playSound(location, "mahjongcraft:riichi", 0.8F, 1.25F);

        coordinator.playRiichiSound();
        verify(viewer).playSound(location, "mahjongcraft:riichi", 0.8F, 1.25F);
    }

    @Test
    void routesDrawSoundByGbVariant() {
        TableSessionContext session = mock(TableSessionContext.class);
        TableRoundController controller = mock(TableRoundController.class);
        Player viewer = mock(Player.class);
        Location location = mock(Location.class);
        TableStateSoundCoordinator coordinator = new TableStateSoundCoordinator(session);

        when(session.currentVariant()).thenReturn(MahjongVariant.GB);
        when(session.hasRoundController()).thenReturn(true);
        when(session.roundControllerInternal()).thenReturn(controller);
        when(session.viewers()).thenReturn(List.of(viewer));
        when(session.isStarted()).thenReturn(true);
        when(session.currentSeat()).thenReturn(null);
        when(session.pendingReactionFingerprint()).thenReturn("");
        when(session.lastResolution()).thenReturn(null);
        when(controller.started()).thenReturn(true);
        when(viewer.getLocation()).thenReturn(location);

        when(controller.remainingWallCount()).thenReturn(70);
        coordinator.syncStateSounds();
        when(controller.remainingWallCount()).thenReturn(69);
        coordinator.syncStateSounds();

        verify(viewer).playSound(location, "mahjongcraft:gb_tile_draw", 0.65F, 1.05F);
    }

    @Test
    void routesDiscardSoundBySichuanVariant() {
        TableSessionContext session = mock(TableSessionContext.class);
        Player viewer = mock(Player.class);
        Location location = mock(Location.class);
        TableStateSoundCoordinator coordinator = new TableStateSoundCoordinator(session);

        when(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN);
        when(session.viewers()).thenReturn(List.of(viewer));
        when(viewer.getLocation()).thenReturn(location);

        coordinator.playDiscardSound();

        verify(viewer).playSound(location, "mahjongcraft:sichuan_tile_discard", 0.75F, 1.05F);
    }

    @Test
    void turnCueIsPrivateAndDoesNotRepeatWhenOnlyOtherStateChanges() {
        TableSessionContext session = mock(TableSessionContext.class);
        TableRoundController controller = mock(TableRoundController.class);
        Player activePlayer = mock(Player.class);
        Player otherViewer = mock(Player.class);
        Location activeLocation = mock(Location.class);
        UUID activeId = UUID.fromString("00000000-0000-0000-0000-000000000501");
        TableStateSoundCoordinator coordinator = new TableStateSoundCoordinator(session);

        when(session.hasRoundController()).thenReturn(true);
        when(session.roundControllerInternal()).thenReturn(controller);
        when(session.isStarted()).thenReturn(true);
        when(session.currentSeat()).thenReturn(SeatWind.EAST);
        when(session.playerAt(SeatWind.EAST)).thenReturn(activeId);
        when(session.onlinePlayer(activeId)).thenReturn(activePlayer);
        when(session.viewers()).thenReturn(List.of(activePlayer, otherViewer));
        when(session.lastResolution()).thenReturn(null);
        when(controller.started()).thenReturn(true);
        when(controller.remainingWallCount()).thenReturn(70);
        when(activePlayer.getLocation()).thenReturn(activeLocation);

        coordinator.syncStateSounds();
        coordinator.syncStateSounds();

        verify(activePlayer, times(1)).playSound(activeLocation, "mahjongcraft:turn_change", 0.5F, 1.6F);
        verify(otherViewer, never()).playSound(otherViewer.getLocation(), "mahjongcraft:turn_change", 0.5F, 1.6F);
    }

    @Test
    void identicalResolutionSoundIsDeliveredOnce() {
        TableSessionContext session = mock(TableSessionContext.class);
        TableRoundController controller = mock(TableRoundController.class);
        Player viewer = mock(Player.class);
        Location location = mock(Location.class);
        RoundResolution resolution = new RoundResolution("RON", List.of(), null, null);
        TableStateSoundCoordinator coordinator = new TableStateSoundCoordinator(session);

        when(session.hasRoundController()).thenReturn(true);
        when(session.roundControllerInternal()).thenReturn(controller);
        when(session.viewers()).thenReturn(List.of(viewer));
        when(session.lastResolution()).thenReturn(resolution);
        when(viewer.getLocation()).thenReturn(location);

        coordinator.syncStateSounds();
        coordinator.syncStateSounds();

        verify(viewer, times(1)).playSound(location, "mahjongcraft:round_win", 0.9F, 1.0F);
    }
}
