package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Information safe to publish to every viewer. */
public record PublicRuleView(
        long stateRevision,
        String phase,
        List<RuleViewTile> tiles,
        Map<String, String> attributes,
        RuleTablePresentation tablePresentation) {
    public PublicRuleView {
        if (stateRevision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
        phase = Objects.requireNonNull(phase, "phase");
        tiles = List.copyOf(Objects.requireNonNull(tiles, "tiles"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        Objects.requireNonNull(tablePresentation, "tablePresentation");
    }
}
