package top.ellan.mahjong.domain.lobby;

import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Immutable occupancy and readiness for one physical seat. */
public record LobbySeat(
        SeatId seatId,
        Optional<PlayerId> occupant,
        boolean ready,
        SeatPresence presence) {
    public LobbySeat {
        Objects.requireNonNull(seatId, "seatId");
        occupant = Objects.requireNonNull(occupant, "occupant");
        Objects.requireNonNull(presence, "presence");
        if (occupant.isEmpty() && (ready || presence != SeatPresence.OFFLINE)) {
            throw new IllegalArgumentException("An empty seat cannot be ready or online");
        }
    }

    public static LobbySeat empty(SeatId seatId) {
        return new LobbySeat(seatId, Optional.empty(), false, SeatPresence.OFFLINE);
    }

    public LobbySeat occupiedBy(PlayerId playerId) {
        return new LobbySeat(
                seatId,
                Optional.of(Objects.requireNonNull(playerId, "playerId")),
                false,
                SeatPresence.ONLINE);
    }

    public LobbySeat occupiedByReadyBot(PlayerId playerId) {
        return new LobbySeat(
                seatId,
                Optional.of(Objects.requireNonNull(playerId, "playerId")),
                true,
                SeatPresence.ONLINE);
    }

    public LobbySeat vacated() {
        return empty(seatId);
    }

    public LobbySeat withReady(boolean nextReady) {
        if (occupant.isEmpty()) {
            throw new IllegalStateException("An empty seat cannot become ready");
        }
        if (nextReady && presence != SeatPresence.ONLINE) {
            throw new IllegalStateException("An offline seat cannot become ready");
        }
        return new LobbySeat(seatId, occupant, nextReady, presence);
    }

    public LobbySeat withPresence(SeatPresence nextPresence) {
        Objects.requireNonNull(nextPresence, "nextPresence");
        if (occupant.isEmpty()) {
            return empty(seatId);
        }
        return new LobbySeat(
                seatId,
                occupant,
                nextPresence == SeatPresence.ONLINE && ready,
                nextPresence);
    }
}
