package top.ellan.mahjong.plugin.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.platform.CraftEnginePlatformRuntime;
import top.ellan.mahjong.plugin.protection.ProtectionService;

/**
 * Validates one proposed table against fixed local geometry, indexed anchors and protection flags.
 */
public final class TablePlacementService {
    private static final double MIN_CENTER_DISTANCE_XZ = 5.5D;
    private static final double VERTICAL_OVERLAP_DISTANCE = 4.0D;
    private static final int CLEARANCE_RADIUS_BLOCKS = 3;
    private static final int CLEARANCE_HEIGHT_BLOCKS = 4;

    private final CraftEnginePlatformRuntime platform;
    private final ProtectionService protection;

    public TablePlacementService(
            CraftEnginePlatformRuntime platform, ProtectionService protection) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    public Optional<TablePlacementFailure> validateCreation(Player owner, Location center) {
        if (owner == null || center == null || center.getWorld() == null) {
            return Optional.of(
                    TablePlacementFailure.simple(
                            TablePlacementFailure.Reason.INVALID_LOCATION));
        }
        Optional<TableId> overlap =
                platform.overlappingTable(
                        center, MIN_CENTER_DISTANCE_XZ, VERTICAL_OVERLAP_DISTANCE);
        if (overlap.isPresent()) {
            return Optional.of(TablePlacementFailure.overlap(overlap.orElseThrow()));
        }
        Optional<TablePlacementFailure> blocked = firstBlockedSpace(center);
        if (blocked.isPresent()) {
            return blocked;
        }
        if (!protection.canPlace(owner, footprint(center))) {
            return Optional.of(
                    TablePlacementFailure.simple(
                            TablePlacementFailure.Reason.PROTECTED_AREA));
        }
        return Optional.empty();
    }

    /** Checks one addressed table only. Empty means the anchor is not currently registered. */
    public Optional<Boolean> canRemove(Player player, TableId tableId) {
        Objects.requireNonNull(player, "player");
        return platform.tableAnchor(Objects.requireNonNull(tableId, "tableId"))
                .map(anchor -> protection.canBreak(player, footprint(anchor)));
    }

    static Optional<TablePlacementFailure> firstBlockedSpace(Location center) {
        World world = center == null ? null : center.getWorld();
        if (world == null) {
            return Optional.of(
                    TablePlacementFailure.simple(
                            TablePlacementFailure.Reason.INVALID_LOCATION));
        }
        int minY = Math.max(world.getMinHeight(), center.getBlockY());
        int maxY =
                Math.min(
                        world.getMaxHeight() - 1,
                        center.getBlockY() + CLEARANCE_HEIGHT_BLOCKS - 1);
        if (maxY - minY + 1 < CLEARANCE_HEIGHT_BLOCKS) {
            return Optional.of(
                    TablePlacementFailure.simple(
                            TablePlacementFailure.Reason.NOT_ENOUGH_HEIGHT));
        }
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        for (int y = minY; y <= maxY; y++) {
            for (int x = centerX - CLEARANCE_RADIUS_BLOCKS;
                    x <= centerX + CLEARANCE_RADIUS_BLOCKS;
                    x++) {
                for (int z = centerZ - CLEARANCE_RADIUS_BLOCKS;
                        z <= centerZ + CLEARANCE_RADIUS_BLOCKS;
                        z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.isPassable() || block.isLiquid()) {
                        return Optional.of(TablePlacementFailure.blocked(x, y, z));
                    }
                }
            }
        }
        return Optional.empty();
    }

    static List<Location> footprint(Location center) {
        World world = center == null ? null : center.getWorld();
        if (world == null) {
            return List.of();
        }
        int diameter = CLEARANCE_RADIUS_BLOCKS * 2 + 1;
        ArrayList<Location> points = new ArrayList<>(diameter * diameter);
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        for (int x = centerX - CLEARANCE_RADIUS_BLOCKS;
                x <= centerX + CLEARANCE_RADIUS_BLOCKS;
                x++) {
            for (int z = centerZ - CLEARANCE_RADIUS_BLOCKS;
                    z <= centerZ + CLEARANCE_RADIUS_BLOCKS;
                    z++) {
                points.add(new Location(world, x + 0.5D, centerY, z + 0.5D));
            }
        }
        return List.copyOf(points);
    }
}
