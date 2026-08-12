package top.ellan.mahjong.domain.lobby;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.spi.PlayerId;

/** Rebuilds one completed table's durable lobby shell without consulting global server state. */
final class TableLobbyReuse {
    private TableLobbyReuse() {}

    static Optional<TableLobby> reset(TableLobby lobby, Set<PlayerId> departedPlayers) {
        Objects.requireNonNull(lobby, "lobby");
        departedPlayers = Set.copyOf(
                Objects.requireNonNull(departedPlayers, "departedPlayers"));
        ArrayList<LobbySeat> resetSeats = new ArrayList<>(lobby.seats().size());
        for (LobbySeat seat : lobby.seats()) {
            if (seat.occupant().isEmpty()
                    || departedPlayers.contains(seat.occupant().orElseThrow())) {
                resetSeats.add(LobbySeat.empty(seat.seatId()));
                continue;
            }
            PlayerId occupant = seat.occupant().orElseThrow();
            resetSeats.add(
                    lobby.isBotSeat(seat)
                            ? LobbySeat.empty(seat.seatId()).occupiedByReadyBot(occupant)
                            : new LobbySeat(
                                    seat.seatId(),
                                    Optional.of(occupant),
                                    false,
                                    SeatPresence.OFFLINE));
        }
        LinkedHashSet<PlayerId> resetSpectators = new LinkedHashSet<>(lobby.spectators());
        resetSpectators.removeAll(departedPlayers);
        PlayerId nextOwner = retainedHuman(lobby, resetSeats, lobby.ownerId())
                .or(() -> firstRetainedHuman(lobby, resetSeats))
                .orElse(null);
        if (nextOwner == null) {
            return Optional.empty();
        }
        return Optional.of(
                new TableLobby(
                        lobby.tableId(),
                        lobby.revision() + 1,
                        nextOwner,
                        lobby.ruleId(),
                        lobby.profileId(),
                        lobby.configuration(),
                        resetSeats,
                        resetSpectators,
                        LobbyPhase.WAITING,
                        lobby.createdAt()));
    }

    private static Optional<PlayerId> retainedHuman(
            TableLobby lobby, List<LobbySeat> seats, PlayerId candidate) {
        return seats.stream()
                .filter(seat -> seat.occupant().filter(candidate::equals).isPresent())
                .filter(seat -> !LobbyBotIdentity.occupies(lobby, seat))
                .map(seat -> candidate)
                .findFirst();
    }

    private static Optional<PlayerId> firstRetainedHuman(
            TableLobby lobby, List<LobbySeat> seats) {
        return seats.stream()
                .filter(seat -> seat.occupant().isPresent())
                .filter(seat -> !LobbyBotIdentity.occupies(lobby, seat))
                .map(seat -> seat.occupant().orElseThrow())
                .findFirst();
    }
}
