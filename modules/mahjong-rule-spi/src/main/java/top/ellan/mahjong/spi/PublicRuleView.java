package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Information safe to publish to every viewer.
 *
 * @param stateRevision monotonically increasing revision of the published rule state
 * @param phase stable rule-defined identifier for the current phase
 * @param tiles tiles whose locations are public to every viewer
 * @param attributes bounded rule-defined public attributes
 * @param tablePresentation physical table presentation metadata for the client
 */
public record PublicRuleView(
        long stateRevision,
        String phase,
        List<RuleViewTile> tiles,
        Map<String, String> attributes,
        RuleTablePresentation tablePresentation) {
    private static final int MAX_TILES = 512;
    private static final int MAX_ATTRIBUTES = 128;

    /**
     * Creates a bounded view safe to publish to every viewer.
     *
     * @param stateRevision monotonically increasing revision of the published rule state
     * @param phase stable rule-defined identifier for the current phase
     * @param tiles tiles whose locations are public to every viewer
     * @param attributes bounded rule-defined public attributes
     * @param tablePresentation physical table presentation metadata for the client
     */
    public PublicRuleView {
        if (stateRevision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
        phase = Objects.requireNonNull(phase, "phase");
        if (phase.length() > 128) {
            throw new IllegalArgumentException("Rule phase is too large");
        }
        Objects.requireNonNull(tiles, "tiles");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(tablePresentation, "tablePresentation");
        if (tiles.size() > MAX_TILES || attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("Public rule view exceeds bounded output limits");
        }
        tiles = List.copyOf(tiles);
        attributes = RuleOutputLimits.copyAttributes(attributes);
    }
}
