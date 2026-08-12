package top.ellan.mahjong.spi;

import java.util.Objects;

/**
 * Player-to-seat assignment fixed when a match is created.
 *
 * @param playerId player assigned to the match
 * @param seatId immutable seat assigned to the player
 */
public record MatchPlayer(PlayerId playerId, SeatId seatId) {
    /**
     * Creates a fixed player-to-seat assignment.
     *
     * @param playerId player assigned to the match
     * @param seatId immutable seat assigned to the player
     */
    public MatchPlayer {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(seatId, "seatId");
    }
}
