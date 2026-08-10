package top.ellan.mahjong.presentation.projection.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;

/**
 * Per-frame zone counts for each viewer's private tiles, computed at most once per viewer.
 *
 * <p>The private and interaction projectors both position tiles from the same
 * {@link PrivateRuleView}, so each used to walk every viewer's tile list separately. Sharing one
 * instance for the frame halves that work and guarantees both projectors agree on tile counts, which
 * is what keeps a hand action anchored to the same slot as the tile it acts on.</p>
 */
public final class ViewerZoneCounts {
    private final Map<PlayerId, ZoneTileCounts> byViewer = new HashMap<>();

    public ZoneTileCounts of(PlayerId viewer, PrivateRuleView privateView) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(privateView, "privateView");
        return byViewer.computeIfAbsent(
                viewer, ignored -> ZoneTileCounts.from(privateView.tiles()));
    }
}
