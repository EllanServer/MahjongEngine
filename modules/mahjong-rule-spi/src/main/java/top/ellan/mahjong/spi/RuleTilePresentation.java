package top.ellan.mahjong.spi;

import java.util.Objects;

/** Compact, platform-neutral placement hints for one projected physical tile. */
public record RuleTilePresentation(
        int layoutIndex,
        RuleTileRotation rotation,
        int stackLevel,
        boolean emphasized) {
    private static final int MAX_LAYOUT_INDEX = 4_095;
    private static final int MAX_STACK_LEVEL = 3;

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
        return new RuleTilePresentation(layoutIndex, RuleTileRotation.NATURAL, 0, false);
    }

    public RuleTilePresentation withEmphasis() {
        return emphasized
                ? this
                : new RuleTilePresentation(layoutIndex, rotation, stackLevel, true);
    }
}
