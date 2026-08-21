package top.ellan.mahjong.presentation.layout;

import top.ellan.mahjong.presentation.node.InteractionBounds;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.SeatId;

/** Immutable lookup plan compiled once for one physical table specification. */
public interface ResolvedTableLayout {
    SceneTransform tile(RuleViewTile tile, int groupSize);

    /** Client-private replacement for a public tile, separated just enough to avoid z-fighting. */
    SceneTransform privateTile(RuleViewTile tile, int groupSize);

    InteractionBounds handInteractionBounds();

    SceneTransform action(SeatId seat, ActionPlacement placement, int index);

    /** Places a dynamically sized button inside one centered four-button row. */
    SceneTransform action(
            SeatId seat, ActionPlacement placement, int row, double tangentOffset);

    SceneTransform viewControl(SeatId seat);

    /** Centre pose for the enlarged copy of the most recent discard. */
    SceneTransform lastDiscardHighlight();

    SceneTransform overheadCamera(SeatId seat, double height);
}
