package top.ellan.mahjong.craftengine.interaction;

import java.util.Optional;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import top.ellan.mahjong.spi.SeatId;

/** Derives the canonical table side from the CraftEngine-configured seat hitbox. */
public final class CraftEngineSeatResolver {
    private static final float MIN_OFFSET = 0.25F;

    public Optional<SeatId> resolve(FurnitureHitBox hitBox) {
        if (hitBox == null || hitBox.seats().length == 0) {
            return Optional.empty();
        }
        var position = hitBox.config().position();
        return resolveConfiguredPosition(position.x(), position.z());
    }

    static Optional<SeatId> resolveConfiguredPosition(float x, float z) {
        if (Math.max(Math.abs(x), Math.abs(z)) < MIN_OFFSET) {
            return Optional.empty();
        }
        if (Math.abs(z) >= Math.abs(x)) {
            return Optional.of(new SeatId(z >= 0.0F ? 0 : 2));
        }
        return Optional.of(new SeatId(x >= 0.0F ? 1 : 3));
    }
}
