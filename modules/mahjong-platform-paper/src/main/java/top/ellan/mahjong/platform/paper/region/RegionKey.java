package top.ellan.mahjong.platform.paper.region;

import java.util.Objects;

/** Immutable Folia ownership key for one table anchor. */
public record RegionKey(String worldId, int chunkX, int chunkZ) {
    public RegionKey {
        worldId = Objects.requireNonNull(worldId, "worldId");
        if (worldId.isBlank()) {
            throw new IllegalArgumentException("worldId cannot be blank");
        }
    }
}
