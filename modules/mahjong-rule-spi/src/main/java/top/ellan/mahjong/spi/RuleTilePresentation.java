package top.ellan.mahjong.spi;

import java.util.Objects;

/**
 * Compact, platform-neutral placement hints for one projected physical tile.
 *
 * @param layoutIndex zero-based position within the tile's presentation zone
 * @param rotation physical rotation requested for the tile
 * @param stackLevel zero-based vertical layer within the presentation zone
 * @param emphasized whether the client should visually emphasize the tile
 */
public record RuleTilePresentation(
        int layoutIndex,
        RuleTileRotation rotation,
        int stackLevel,
        boolean emphasized) {
    private static final int MAX_LAYOUT_INDEX = 4_095;
    private static final int MAX_STACK_LEVEL = 3;
    private static final RuleTilePresentation[] NATURAL_CACHE = naturalCache();
    private static final RuleTilePresentation[] CLOCKWISE_CACHE = rotationCache(0);
    private static final RuleTilePresentation[] STACKED_CLOCKWISE_CACHE = rotationCache(1);

    public RuleTilePresentation {
        if (layoutIndex < 0 || layoutIndex > MAX_LAYOUT_INDEX) {
            throw new IllegalArgumentException("layoutIndex is outside the supported scene range");
        }
        Objects.requireNonNull(rotation, "rotation");
        if (stackLevel < 0 || stackLevel > MAX_STACK_LEVEL) {
            throw new IllegalArgumentException("stackLevel is outside the supported scene range");
        }
    }

    public static RuleTilePresentation natural(int layoutIndex) {
        return layoutIndex >= 0 && layoutIndex < NATURAL_CACHE.length
                ? NATURAL_CACHE[layoutIndex]
                : new RuleTilePresentation(layoutIndex, RuleTileRotation.NATURAL, 0, false);
    }

    /** Returns the cached physical pose used for a claimed discard in an open meld. */
    public static RuleTilePresentation clockwise(int layoutIndex) {
        return layoutIndex >= 0 && layoutIndex < CLOCKWISE_CACHE.length
                ? CLOCKWISE_CACHE[layoutIndex]
                : new RuleTilePresentation(layoutIndex, RuleTileRotation.CLOCKWISE, 0, false);
    }

    /** Returns the cached pose used when an added-kong tile is stacked on the claimed tile. */
    public static RuleTilePresentation stackedClockwise(int layoutIndex) {
        return layoutIndex >= 0 && layoutIndex < STACKED_CLOCKWISE_CACHE.length
                ? STACKED_CLOCKWISE_CACHE[layoutIndex]
                : new RuleTilePresentation(layoutIndex, RuleTileRotation.CLOCKWISE, 1, false);
    }

    public RuleTilePresentation withEmphasis() {
        return emphasized
                ? this
                : new RuleTilePresentation(layoutIndex, rotation, stackLevel, true);
    }

    private static RuleTilePresentation[] naturalCache() {
        RuleTilePresentation[] result = new RuleTilePresentation[256];
        for (int index = 0; index < result.length; index++) {
            result[index] = new RuleTilePresentation(
                    index, RuleTileRotation.NATURAL, 0, false);
        }
        return result;
    }

    private static RuleTilePresentation[] rotationCache(int stackLevel) {
        RuleTilePresentation[] result = new RuleTilePresentation[256];
        for (int index = 0; index < result.length; index++) {
            result[index] = new RuleTilePresentation(
                    index, RuleTileRotation.CLOCKWISE, stackLevel, false);
        }
        return result;
    }
}
