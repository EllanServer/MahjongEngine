package top.ellan.mahjong.persistence.sql;

import java.util.Objects;
import top.ellan.mahjong.domain.TableId;

/** Platform-neutral persisted coordinates for restoring a table scene after restart. */
public record StoredTableAnchor(
        TableId tableId,
        String worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {
    public StoredTableAnchor {
        Objects.requireNonNull(tableId, "tableId");
        worldId = Objects.requireNonNull(worldId, "worldId");
        if (worldId.isBlank() || worldId.length() > 128) {
            throw new IllegalArgumentException("Invalid world id");
        }
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(yaw)
                || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Anchor coordinates must be finite");
        }
    }
}
