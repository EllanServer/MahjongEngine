package top.ellan.mahjong.application.lobby.usecase;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;
import top.ellan.mahjong.application.lobby.port.ActiveSeatLookupPort;
import top.ellan.mahjong.application.lobby.port.SeatInteractionAdmission;
import top.ellan.mahjong.application.lobby.port.SeatInteractionPort;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.lobby.runtime.LobbyTableDirectory;
import top.ellan.mahjong.domain.LobbySeat;
import top.ellan.mahjong.domain.SeatPresence;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Lock-free snapshot admission followed by authoritative single-writer lobby reduction. */
public final class LobbySeatInteractionService implements SeatInteractionPort {
    private final LobbyTableDirectory lobbies;
    private final ActiveSeatLookupPort activeSeats;

    public LobbySeatInteractionService(LobbyTableDirectory lobbies) {
        this(lobbies, ActiveSeatLookupPort.NONE);
    }

    public LobbySeatInteractionService(
            LobbyTableDirectory lobbies, ActiveSeatLookupPort activeSeats) {
        this.lobbies = Objects.requireNonNull(lobbies, "lobbies");
        this.activeSeats = Objects.requireNonNull(activeSeats, "activeSeats");
    }

    @Override
    public SeatInteractionAdmission interact(
            TableId tableId, SeatId seatId, PlayerId playerId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(playerId, "playerId");
        HostedLobby hosted = lobbies.find(tableId).orElse(null);
        if (hosted == null) {
            boolean pinned = activeSeats.seatOf(tableId, playerId).filter(seatId::equals).isPresent();
            return pinned
                    ? new SeatInteractionAdmission(
                            true,
                            CompletableFuture.completedFuture(
                                    new TableActionResult(
                                            TableActionCode.ACCEPTED_MEMORY,
                                            0,
                                            "active-seat-restored")))
                    : rejected(tableId, "seat-not-authorized");
        }
        TableLobby snapshot = hosted.state();
        int index = seatId.value();
        if (index >= snapshot.seats().size()) {
            return rejected(tableId, "seat-out-of-range");
        }
        LobbySeat requested = snapshot.seats().get(index);
        if (requested.occupant().isPresent()
                && !requested.occupant().orElseThrow().equals(playerId)) {
            return rejected(tableId, "seat-occupied");
        }
        if (snapshot.seatOf(playerId).filter(current -> !current.equals(seatId)).isPresent()) {
            return rejected(tableId, "already-seated");
        }
        return new SeatInteractionAdmission(
                true,
                hosted.actor().command(new LobbyCommand.JoinSeat(playerId, seatId)));
    }

    @Override
    public void connected(PlayerId playerId) {
        setPresence(playerId, SeatPresence.ONLINE);
    }

    @Override
    public void disconnected(PlayerId playerId) {
        setPresence(playerId, SeatPresence.OFFLINE);
    }

    private void setPresence(PlayerId playerId, SeatPresence presence) {
        Objects.requireNonNull(playerId, "playerId");
        lobbies.findByPlayer(playerId)
                .ifPresent(
                        hosted ->
                                hosted.actor()
                                        .command(new LobbyCommand.SetPresence(playerId, presence)));
    }

    private static SeatInteractionAdmission rejected(TableId tableId, String reason) {
        return new SeatInteractionAdmission(
                false,
                CompletableFuture.completedFuture(
                        new TableActionResult(
                                TableActionCode.REJECTED_BY_RULES,
                                0,
                                tableId + ":" + reason)));
    }
}
