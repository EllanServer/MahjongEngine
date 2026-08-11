package top.ellan.mahjong.presentation.layout;

import java.util.Objects;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.RuleTilePresentation;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.SeatId;

/** Immutable arrays used by the hot projection path after a layout has been compiled. */
final class UniversalLayoutPlan {
    private final TableGeometry geometry;
    private final UniversalLayoutSpec spec;
    private final SceneTransform[][][] hands;
    private final SceneTransform[][][] privateHands;
    private final SceneTransform[][] discards;
    private final SceneTransform[][] melds;
    private final SceneTransform[][] flowers;
    private final SceneTransform[][] pointSticks;
    private final SceneTransform[][] auxiliary;
    private final SceneTransform[][] winClaims;
    private final SceneTransform[][] actions;
    private final SceneTransform[][] secondaryActions;
    private final double[] seatTangentX;
    private final double[] seatTangentZ;
    private final SceneTransform[] viewControls;
    private final SceneTransform[] wall;

    UniversalLayoutPlan(
            TableGeometry geometry,
            UniversalLayoutSpec spec,
            SceneTransform[][][] hands,
            SceneTransform[][][] privateHands,
            SceneTransform[][] discards,
            SceneTransform[][] melds,
            SceneTransform[][] flowers,
            SceneTransform[][] pointSticks,
            SceneTransform[][] auxiliary,
            SceneTransform[][] winClaims,
            SceneTransform[][] actions,
            SceneTransform[][] secondaryActions,
            double[] seatTangentX,
            double[] seatTangentZ,
            SceneTransform[] viewControls,
            SceneTransform[] wall) {
        this.geometry = geometry;
        this.spec = spec;
        this.hands = hands;
        this.privateHands = privateHands;
        this.discards = discards;
        this.melds = melds;
        this.flowers = flowers;
        this.pointSticks = pointSticks;
        this.auxiliary = auxiliary;
        this.winClaims = winClaims;
        this.actions = actions;
        this.secondaryActions = secondaryActions;
        this.seatTangentX = seatTangentX;
        this.seatTangentZ = seatTangentZ;
        this.viewControls = viewControls;
        this.wall = wall;
    }

    SceneTransform tile(RuleViewTile tile, int groupSize) {
        Objects.requireNonNull(tile, "tile");
        RuleTilePresentation presentation = tile.presentation();
        SceneTransform base = switch (tile.zone()) {
            case HAND -> hand(tile, groupSize);
            case DISCARD -> seatValue(discards, tile, geometry.maxDiscards());
            case MELD -> seatValue(melds, tile, geometry.maxMeldTiles());
            case FLOWER -> seatValue(flowers, tile, geometry.maxAuxiliaryTiles());
            case WIN_CLAIM -> centerValue(
                    winClaims, groupSize, presentation.layoutIndex(), "win claim");
            case WALL, INDICATOR -> throw new IllegalArgumentException(
                    "Wall tiles require the resolved draw origin");
            case POINT_STICK -> seatValue(pointSticks, tile, geometry.maxPointSticks());
            case AUXILIARY -> centerValue(
                    auxiliary, groupSize, presentation.layoutIndex(), "auxiliary");
        };
        return decorate(base, presentation);
    }

    SceneTransform privateTile(RuleViewTile tile, int groupSize) {
        if (tile.zone() != RuleViewZone.HAND) {
            return tile(tile, groupSize);
        }
        return decorate(hand(privateHands, tile, groupSize), tile.presentation());
    }

    SceneTransform action(SeatId seat, ActionPlacement placement, int index) {
        if (index < 0 || index >= geometry.maxActions()) {
            throw new IllegalArgumentException("Action index exceeds the layout capacity");
        }
        int row = index / 4;
        int column = index % 4;
        double tangent = (column - 1.5D) * geometry.actionColumnSpacing();
        return action(seat, placement, row, tangent);
    }

    SceneTransform action(
            SeatId seat, ActionPlacement placement, int row, double tangentOffset) {
        int seatIndex = seatIndex(seat);
        if (row < 0 || row >= actions[seatIndex].length || !Double.isFinite(tangentOffset)) {
            throw new IllegalArgumentException("Action row exceeds the layout capacity");
        }
        SceneTransform base = switch (Objects.requireNonNull(placement, "placement")) {
            case ACTION_ROW -> actions[seatIndex][row];
            case SECONDARY_ROW -> secondaryActions[seatIndex][row];
            case HAND_TILE -> throw new IllegalArgumentException(
                    "Hand-tile actions use the target tile transform");
        };
        if (tangentOffset == 0.0D) {
            return base;
        }
        return new SceneTransform(
                base.x() + seatTangentX[seatIndex] * tangentOffset,
                base.y(),
                base.z() + seatTangentZ[seatIndex] * tangentOffset,
                base.yawDegrees(),
                base.pitchDegrees(),
                base.rollDegrees(),
                base.scale());
    }

    SceneTransform viewControl(SeatId seat) {
        return viewControls[seatIndex(seat)];
    }

    SceneTransform overheadCamera(SeatId seat, double height) {
        int seatIndex = seatIndex(seat);
        if (!Double.isFinite(height) || height <= geometry.surfaceHeight()) {
            throw new IllegalArgumentException("Overhead camera height must clear the table");
        }
        double facingCenterYaw = 90.0D - 360.0D * seatIndex / spec.seatCount();
        return new SceneTransform(0, height, 0, facingCenterYaw, 90, 0, 1);
    }

    SceneTransform wall(
            RuleTilePresentation presentation,
            int drawStartStack,
            RuleWallDirection direction) {
        int logicalTile = presentation.layoutIndex();
        if (logicalTile < 0 || logicalTile >= wall.length) {
            throw new IllegalArgumentException("wall layout index exceeds its capacity");
        }
        int logicalStack = logicalTile / 2;
        int physicalStack = direction == RuleWallDirection.CLOCKWISE
                ? Math.floorMod(drawStartStack + logicalStack, spec.totalWallStacks())
                : Math.floorMod(drawStartStack - logicalStack, spec.totalWallStacks());
        return decorate(wall[physicalStack * 2 + logicalTile % 2], presentation);
    }

    private SceneTransform hand(RuleViewTile tile, int groupSize) {
        return hand(hands, tile, groupSize);
    }

    private SceneTransform hand(
            SceneTransform[][][] transforms, RuleViewTile tile, int groupSize) {
        int seat = owner(tile);
        int index = tile.presentation().layoutIndex();
        if (groupSize < 1
                || groupSize > geometry.maxHandTiles()
                || index < 0
                || index >= groupSize) {
            throw new IllegalArgumentException("Hand layout exceeds the physical capacity");
        }
        return transforms[seat][groupSize][index];
    }

    private SceneTransform seatValue(
            SceneTransform[][] values, RuleViewTile tile, int capacity) {
        int index = tile.presentation().layoutIndex();
        if (index < 0 || index >= capacity) {
            throw new IllegalArgumentException("Tile layout index exceeds its zone capacity");
        }
        return values[owner(tile)][index];
    }

    private SceneTransform centerValue(
            SceneTransform[][] values, int groupSize, int index, String zone) {
        if (groupSize < 1 || groupSize >= values.length || index < 0 || index >= groupSize) {
            throw new IllegalArgumentException(zone + " layout exceeds its capacity");
        }
        return values[groupSize][index];
    }

    private SceneTransform decorate(SceneTransform base, RuleTilePresentation presentation) {
        if (presentation.rotation().quarterTurns() == 0
                && presentation.stackLevel() == 0
                && !presentation.emphasized()) {
            return base;
        }
        return new SceneTransform(
                base.x(),
                base.y()
                        + presentation.stackLevel() * stackRaise()
                        + (presentation.emphasized() ? geometry.emphasisRaise() : 0.0D),
                base.z(),
                base.yawDegrees() + presentation.rotation().quarterTurns() * 90.0D,
                base.pitchDegrees(),
                base.rollDegrees(),
                base.scale());
    }

    private int owner(RuleViewTile tile) {
        return seatIndex(tile.owner().orElseThrow(
                () -> new IllegalArgumentException(tile.zone() + " tile has no owner")));
    }

    private int seatIndex(SeatId seat) {
        int value = Objects.requireNonNull(seat, "seat").value();
        if (value < 0 || value >= spec.seatCount()) {
            throw new IllegalArgumentException("Tile references an absent table seat");
        }
        return value;
    }

    private double stackRaise() {
        return geometry.tileDepth() + geometry.tileGap();
    }

}
