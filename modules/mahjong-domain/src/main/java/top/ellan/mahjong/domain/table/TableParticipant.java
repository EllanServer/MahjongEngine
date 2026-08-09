package top.ellan.mahjong.domain.table;

import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Platform-neutral membership record. */
public record TableParticipant(PlayerId playerId, ParticipantRole role, Optional<SeatId> seat) {
    public TableParticipant {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        seat = Objects.requireNonNull(seat, "seat");
        if (role == ParticipantRole.PLAYER && seat.isEmpty()) {
            throw new IllegalArgumentException("A player must own a seat");
        }
        if (role != ParticipantRole.PLAYER && seat.isPresent()) {
            throw new IllegalArgumentException("Only players may own seats");
        }
    }
}
