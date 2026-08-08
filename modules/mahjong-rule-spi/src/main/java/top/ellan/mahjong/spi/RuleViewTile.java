package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.Optional;

/** One physical tile in a public or authorized private view. */
public record RuleViewTile(
        TileInstanceId instanceId,
        TileVisualId visualId,
        Optional<SeatId> owner,
        RuleViewZone zone,
        int index,
        boolean faceUp) {
    public RuleViewTile {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(visualId, "visualId");
        owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(zone, "zone");
        if (index < 0) {
            throw new IllegalArgumentException("View index must be non-negative");
        }
    }
}
