package top.ellan.mahjong.presentation;

import java.util.Optional;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.SeatId;

/** Compact four-side table layout expressed entirely in table-local coordinates. */
public final class RadialTableLayout implements TableLayout {
    private final double tileSpacing;

    public RadialTableLayout(double tileSpacing) {
        if (!Double.isFinite(tileSpacing) || tileSpacing <= 0) {
            throw new IllegalArgumentException("tileSpacing must be positive");
        }
        this.tileSpacing = tileSpacing;
    }

    @Override
    public SceneTransform tile(RuleViewZone zone, Optional<SeatId> owner, int index) {
        int seat = owner.map(SeatId::value).orElse(0) % 4;
        double radius =
                switch (zone) {
                    case HAND -> 1.62;
                    case DISCARD -> 0.78;
                    case MELD -> 1.28;
                    case WALL -> 1.05;
                    case INDICATOR -> 0.28;
                    case POINT_STICK -> 1.82;
                    case AUXILIARY -> 0.48;
                };
        double centered = (index - 8.0) * tileSpacing;
        double baseAngle = Math.toRadians(seat * 90.0);
        double radialX = Math.sin(baseAngle) * radius;
        double radialZ = Math.cos(baseAngle) * radius;
        double tangentX = Math.cos(baseAngle) * centered;
        double tangentZ = -Math.sin(baseAngle) * centered;
        double height = zone == RuleViewZone.POINT_STICK ? 0.1 : 0.16;
        return new SceneTransform(
                radialX + tangentX,
                height,
                radialZ + tangentZ,
                seat * 90.0,
                0,
                0,
                1);
    }

    @Override
    public SceneTransform interaction(int index) {
        double x = (index % 4 - 1.5) * 0.42;
        double z = 0.18 + (index / 4) * 0.34;
        return new SceneTransform(x, 0.3, z, 0, 0, 0, 1);
    }
}
