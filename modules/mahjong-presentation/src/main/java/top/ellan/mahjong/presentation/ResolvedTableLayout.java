package top.ellan.mahjong.presentation;

import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.SeatId;

/** Immutable lookup plan compiled once for one physical table specification. */
public interface ResolvedTableLayout {
    SceneTransform tile(RuleViewTile tile, int groupSize);

    /** Client-private replacement for a public tile, separated just enough to avoid z-fighting. */
    SceneTransform privateTile(RuleViewTile tile, int groupSize);

    SceneTransform action(SeatId seat, ActionPlacement placement, int index);
}
