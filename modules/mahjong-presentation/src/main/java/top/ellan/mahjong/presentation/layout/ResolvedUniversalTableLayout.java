package top.ellan.mahjong.presentation.layout;

import java.util.Objects;
import top.ellan.mahjong.presentation.node.InteractionBounds;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.SeatId;

/** A cheap per-frame view over a cached plan with the current wall draw origin. */
record ResolvedUniversalTableLayout(
        UniversalLayoutPlan plan, int drawStartStack, RuleWallDirection direction)
        implements ResolvedTableLayout {
    ResolvedUniversalTableLayout {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(direction, "direction");
    }

    @Override
    public SceneTransform tile(RuleViewTile tile, int groupSize) {
        if (tile.zone() == RuleViewZone.WALL || tile.zone() == RuleViewZone.INDICATOR) {
            return plan.wall(tile.presentation(), drawStartStack, direction);
        }
        return plan.tile(tile, groupSize);
    }

    @Override
    public SceneTransform privateTile(RuleViewTile tile, int groupSize) {
        return plan.privateTile(tile, groupSize);
    }

    @Override
    public InteractionBounds handInteractionBounds() {
        return plan.handInteractionBounds();
    }

    @Override
    public SceneTransform action(SeatId seat, ActionPlacement placement, int index) {
        return plan.action(seat, placement, index);
    }

    @Override
    public SceneTransform action(
            SeatId seat, ActionPlacement placement, int row, double tangentOffset) {
        return plan.action(seat, placement, row, tangentOffset);
    }

    @Override
    public SceneTransform viewControl(SeatId seat) {
        return plan.viewControl(seat);
    }

    @Override
    public SceneTransform lastDiscardHighlight() {
        return plan.lastDiscardHighlight();
    }

    @Override
    public SceneTransform overheadCamera(SeatId seat, double height) {
        return plan.overheadCamera(seat, height);
    }
}
