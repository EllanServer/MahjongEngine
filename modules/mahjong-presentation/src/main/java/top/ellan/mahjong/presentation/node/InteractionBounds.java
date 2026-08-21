package top.ellan.mahjong.presentation.node;

/** Exact viewer-ray target dimensions centered on an interaction node transform. */
public record InteractionBounds(
        double width, double height, double depth, double centerYOffset) {
    public InteractionBounds {
        requirePositive(width, "width");
        requirePositive(height, "height");
        if (!Double.isFinite(depth) || depth < 0.0D) {
            throw new IllegalArgumentException("depth must be finite and non-negative");
        }
        if (!Double.isFinite(centerYOffset)) {
            throw new IllegalArgumentException("centerYOffset must be finite");
        }
    }

    public InteractionBounds(double width, double height, double depth) {
        this(width, height, depth, 0.0D);
    }

    public static InteractionBounds plane(double width, double height) {
        return plane(width, height, 0.0D);
    }

    /** Flat label plane matching the 1.5.0 text interaction semantics. */
    public static InteractionBounds plane(
            double width, double height, double centerYOffset) {
        return new InteractionBounds(width, height, 0.0D, centerYOffset);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
