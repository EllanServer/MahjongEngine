package top.ellan.mahjong.craftengine.interaction;

import java.util.Optional;
import top.ellan.mahjong.spi.SeatId;

/** Resolves the four compass seats exclusively from authoritative scene node ids. */
public final class CraftEngineSeatResolver {
    private static final String SEAT_NODE_PREFIX = "furniture/seat/";

    public Optional<SeatId> resolve(String nodeId) {
        return resolveNode(nodeId);
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
