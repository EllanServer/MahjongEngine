package top.ellan.mahjong.presentation.layout;

/** Reusable physical dimensions shared by every rule mode and supplied by the CE resource pack. */
public record TableGeometry(
        double tileWidth,
        double tileHeight,
        double tileDepth,
        double tileGap,
        double surfaceHeight,
        double handRadius,
        double wallRadius,
        double tableHalfLength,
        double emphasisRaise,
        double actionColumnSpacing,
        double actionRowSpacing,
        int maxHandTiles,
        int maxDiscards,
        int maxMeldTiles,
        int maxPointSticks,
        int maxAuxiliaryTiles,
        int maxActions) {
    public TableGeometry {
        requirePositive(tileWidth, "tileWidth");
        requirePositive(tileHeight, "tileHeight");
        requirePositive(tileDepth, "tileDepth");
        requireNonNegative(tileGap, "tileGap");
        requirePositive(surfaceHeight, "surfaceHeight");
        requirePositive(handRadius, "handRadius");
        requirePositive(wallRadius, "wallRadius");
        requirePositive(tableHalfLength, "tableHalfLength");
        requireNonNegative(emphasisRaise, "emphasisRaise");
        requirePositive(actionColumnSpacing, "actionColumnSpacing");
        requirePositive(actionRowSpacing, "actionRowSpacing");
        requireCapacity(maxHandTiles, 32, "maxHandTiles");
        requireCapacity(maxDiscards, 128, "maxDiscards");
        requireCapacity(maxMeldTiles, 32, "maxMeldTiles");
        requireCapacity(maxPointSticks, 128, "maxPointSticks");
        requireCapacity(maxAuxiliaryTiles, 64, "maxAuxiliaryTiles");
        requireCapacity(maxActions, 128, "maxActions");
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireCapacity(int value, int maximum, String name) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }
}
