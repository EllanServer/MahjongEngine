package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Information authorized for exactly one viewer.
 *
 * @param stateRevision revision of the rule state used to build this view
 * @param viewer player authorized to receive the view
 * @param seat seat occupied by the authorized viewer
 * @param tiles tiles visible to the authorized viewer
 * @param attributes bounded rule-defined private attributes
 */
public record PrivateRuleView(
        long stateRevision,
        PlayerId viewer,
        SeatId seat,
        List<RuleViewTile> tiles,
        Map<String, String> attributes) {
    private static final int MAX_TILES = 512;
    private static final int MAX_ATTRIBUTES = 128;

    public PrivateRuleView {
        if (stateRevision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(seat, "seat");
        Objects.requireNonNull(tiles, "tiles");
        Objects.requireNonNull(attributes, "attributes");
        if (tiles.size() > MAX_TILES || attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("Private rule view exceeds bounded output limits");
        }
        tiles = List.copyOf(tiles);
        attributes = RuleOutputLimits.copyAttributes(attributes);
    }
}
