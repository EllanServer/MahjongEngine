package top.ellan.mahjong.domain.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

class TableLobbyTest {
    @Test
    void recoveredLobbyPreservesSeatsButRequiresEveryoneToReconnectAndReadyAgain() {
        TableLobby lobby = readyLobby();

        TableLobby recovered = lobby.recoveredOffline();

        assertFalse(recovered.readyToStart());
        assertEquals(lobby.seats().size(), recovered.occupiedSeatCount());
        recovered.seats().forEach(
                seat -> {
                    assertFalse(seat.ready());
                    assertEquals(SeatPresence.OFFLINE, seat.presence());
                });
    }

    @Test
    void duplicatePhysicalOccupantsAreRejectedAtTheDomainBoundary() {
        PlayerId player = player(1);
        List<LobbySeat> seats =
                List.of(
                        LobbySeat.empty(new SeatId(0)).occupiedBy(player),
                        LobbySeat.empty(new SeatId(1)).occupiedBy(player),
                        LobbySeat.empty(new SeatId(2)),
                        LobbySeat.empty(new SeatId(3)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TableLobby(
                                TableId.random(),
                                0,
                                player,
                                new RuleId("riichi"),
                                new ProfileId("mahjong-soul"),
                                Map.of(),
                                seats,
                                Set.of(),
                                LobbyPhase.WAITING,
                                Instant.EPOCH));
    }

    @Test
    void tableAnchorRejectsNonFinitePlatformCoordinates() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TableAnchor(
                                TableId.random(),
                                UUID.randomUUID().toString(),
                                Double.NaN,
                                0,
                                0,
                                0,
                                0));
    }

    @Test
    void tableAnchorKeepsTheFixedV15WorldDirection() {
        TableAnchor anchor =
                new TableAnchor(
                        TableId.random(),
                        UUID.randomUUID().toString(),
                        0.5,
                        64,
                        0.5,
                        137.5F,
                        -24.0F);

        assertEquals(0.0F, anchor.yaw());
        assertEquals(0.0F, anchor.pitch());
    }

    private static TableLobby readyLobby() {
        ArrayList<LobbySeat> seats = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            seats.add(
                    LobbySeat.empty(new SeatId(index))
                            .occupiedBy(player(index + 1))
                            .withReady(true));
        }
        return new TableLobby(
                TableId.random(),
                8,
                player(1),
                new RuleId("riichi"),
                new ProfileId("mahjong-soul"),
                Map.of(),
                seats,
                Set.of(),
                LobbyPhase.WAITING,
                Instant.EPOCH);
    }

    private static PlayerId player(int suffix) {
        return new PlayerId(new UUID(0, suffix));
    }
}
