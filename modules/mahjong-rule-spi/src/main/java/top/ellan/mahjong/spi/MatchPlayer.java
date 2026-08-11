package top.ellan.mahjong.spi;

import java.util.Objects;

/** Player-to-seat assignment fixed when a match is created. */
public record MatchPlayer(PlayerId playerId, SeatId seatId) {
    public MatchPlayer {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(seatId, "seatId");
    }
}
