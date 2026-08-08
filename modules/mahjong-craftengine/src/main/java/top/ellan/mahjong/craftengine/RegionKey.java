package top.ellan.mahjong.craftengine;

import java.util.Objects;

/** Folia ownership key for a table anchor. */
public record RegionKey(String worldId, int chunkX, int chunkZ) {
    public RegionKey {
        worldId = Objects.requireNonNull(worldId, "worldId");
        if (worldId.isBlank()) {
            throw new IllegalArgumentException("worldId cannot be blank");
        }
    }
}
