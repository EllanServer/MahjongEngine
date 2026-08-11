package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Information authorized for exactly one viewer. */
public record PrivateRuleView(
        long stateRevision,
        PlayerId viewer,
        SeatId seat,
        List<RuleViewTile> tiles,
        Map<String, String> attributes) {
    public PrivateRuleView {
        if (stateRevision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(seat, "seat");
        tiles = List.copyOf(Objects.requireNonNull(tiles, "tiles"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
