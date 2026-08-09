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
        boolean faceUp,
        RuleTilePresentation presentation) {
    public RuleViewTile {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(visualId, "visualId");
        owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(presentation, "presentation");
        if (index < 0) {
            throw new IllegalArgumentException("View index must be non-negative");
        }
        boolean requiresOwner = switch (zone) {
            case HAND, DISCARD, MELD, FLOWER, POINT_STICK -> true;
            case WALL, INDICATOR, WIN_CLAIM, AUXILIARY -> false;
        };
        if (requiresOwner && owner.isEmpty()) {
            throw new IllegalArgumentException(zone + " tiles require an owning seat");
        }
        if ((zone == RuleViewZone.WALL || zone == RuleViewZone.INDICATOR)
                && owner.isPresent()) {
            throw new IllegalArgumentException(zone + " tiles cannot have an owning seat");
        }
    }
}
