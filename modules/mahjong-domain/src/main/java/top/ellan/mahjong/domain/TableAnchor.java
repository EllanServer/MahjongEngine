package top.ellan.mahjong.domain;

import java.util.Objects;

/** Platform-neutral location of one physical table. */
public record TableAnchor(
        TableId tableId,
        String worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {
    public TableAnchor {
        Objects.requireNonNull(tableId, "tableId");
        worldId = Objects.requireNonNull(worldId, "worldId");
        if (worldId.isBlank() || worldId.length() > 128 || worldId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("worldId is invalid");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("table coordinates must be finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("table rotation must be finite");
        }
    }
}
