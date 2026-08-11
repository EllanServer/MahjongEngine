package top.ellan.mahjong.craftengine.interaction;

import java.util.Optional;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import top.ellan.mahjong.spi.SeatId;

/** Resolves the four v1.5 compass seats from stable scene ids or legacy CE hitboxes. */
public final class CraftEngineSeatResolver {
    private static final float MIN_OFFSET = 0.25F;
    private static final String SEAT_NODE_PREFIX = "furniture/seat/";

    public Optional<SeatId> resolve(String nodeId, FurnitureHitBox hitBox) {
        Optional<SeatId> nodeSeat = resolveNode(nodeId);
        if (nodeSeat.isPresent()) {
            return nodeSeat;
        }
        if (!"furniture/table".equals(nodeId)) {
            return Optional.empty();
        }
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
        if (Math.abs(x) >= Math.abs(z)) {
            return Optional.of(new SeatId(x >= 0.0F ? 0 : 2));
        }
        return Optional.of(new SeatId(z >= 0.0F ? 1 : 3));
    }

    static Optional<SeatId> resolveNode(String nodeId) {
        if (nodeId == null || !nodeId.startsWith(SEAT_NODE_PREFIX)) {
            return Optional.empty();
        }
        String suffix = nodeId.substring(SEAT_NODE_PREFIX.length());
        if (suffix.length() != 1 || suffix.charAt(0) < '0' || suffix.charAt(0) > '3') {
            return Optional.empty();
        }
        return Optional.of(new SeatId(suffix.charAt(0) - '0'));
    }
}
