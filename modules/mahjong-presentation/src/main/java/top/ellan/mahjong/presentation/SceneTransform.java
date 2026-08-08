package top.ellan.mahjong.presentation;

/** Platform-neutral pose in table-local coordinates. */
public record SceneTransform(
        double x,
        double y,
        double z,
        double yawDegrees,
        double pitchDegrees,
        double rollDegrees,
        double scale) {
    public SceneTransform {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Double.isFinite(yawDegrees)
                || !Double.isFinite(pitchDegrees)
                || !Double.isFinite(rollDegrees)
                || !Double.isFinite(scale)
                || scale <= 0) {
            throw new IllegalArgumentException("Scene transform must be finite with positive scale");
        }
    }
}
