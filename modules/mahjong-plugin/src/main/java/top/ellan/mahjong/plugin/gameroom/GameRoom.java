package top.ellan.mahjong.plugin.gameroom;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Location;

/** Immutable, bounded game-room cuboid stored independently from rule-pack state. */
public record GameRoom(
        String id,
        String name,
        UUID worldId,
        String worldName,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        UUID ownerId) {
    private static final Pattern ID_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final int MAX_HORIZONTAL_SPAN = 256;
    private static final int MAX_VERTICAL_SPAN = 128;

    public GameRoom {
        id = normalizeId(id);
        name = Objects.requireNonNull(name, "name").trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new IllegalArgumentException("Game-room name must contain 1..64 characters");
        }
        Objects.requireNonNull(worldId, "worldId");
        worldName = Objects.requireNonNull(worldName, "worldName").trim();
        if (worldName.isEmpty()) {
            throw new IllegalArgumentException("Game-room world name cannot be blank");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Game-room bounds must be ordered");
        }
        if ((long) maxX - minX + 1L > MAX_HORIZONTAL_SPAN
                || (long) maxZ - minZ + 1L > MAX_HORIZONTAL_SPAN
                || (long) maxY - minY + 1L > MAX_VERTICAL_SPAN) {
            throw new IllegalArgumentException(
                    "Game-room bounds exceed 256x128x256 blocks");
        }
    }

    public boolean contains(Location location) {
        return location != null
                && location.getWorld() != null
                && worldId.equals(location.getWorld().getUID())
                && contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public String boundsSummary() {
        return worldName
                + ' '
                + minX
                + ','
                + minY
                + ','
                + minZ
                + " -> "
                + maxX
                + ','
                + maxY
                + ','
                + maxZ;
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public static String normalizeId(String raw) {
        String normalized = Objects.requireNonNull(raw, "raw").trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Game-room id must match [a-z0-9][a-z0-9_-]{0,31}");
        }
        return normalized;
    }
}
