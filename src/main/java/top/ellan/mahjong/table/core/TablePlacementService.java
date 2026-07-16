package top.ellan.mahjong.table.core;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import top.ellan.mahjong.table.core.MahjongTableManager.CreateTableFailure;
import top.ellan.mahjong.table.core.MahjongTableManager.CreateTableFailureReason;

/** Owns table-space, game-room, and protection checks for new and removed tables. */
final class TablePlacementService {
    private static final double MIN_CENTER_DISTANCE_XZ = 5.5D;
    private static final double VERTICAL_OVERLAP_DISTANCE = 4.0D;
    private static final int CLEARANCE_RADIUS_BLOCKS = 3;
    private static final int CLEARANCE_HEIGHT_BLOCKS = 4;

    private final TableRuntimeServices plugin;
    private final TableDirectory directory;

    TablePlacementService(TableRuntimeServices plugin, TableDirectory directory) {
        this.plugin = plugin;
        this.directory = directory;
    }

    CreateTableFailure validateCreation(Player owner, Location center) {
        CreateTableFailure failure = this.validateTablePlacement(center);
        if (failure != null) {
            return failure;
        }
        failure = this.validateGameRoomRestriction(center);
        if (failure != null) {
            return failure;
        }
        return this.plugin.protection().canPlaceFootprint(owner, tableFootprint(center))
            ? null
            : failure(CreateTableFailureReason.PROTECTED_AREA);
    }

    boolean canBreak(Player player, Location center) {
        return this.plugin.protection().canBreakFootprint(player, tableFootprint(center));
    }

    void sendCreationFailure(Player player, CreateTableFailure failure) {
        if (failure == null) {
            return;
        }
        switch (failure.reason()) {
            case INVALID_LOCATION -> this.plugin.messages().send(player, "command.create_failed_invalid_location");
            case TOO_CLOSE_TO_TABLE -> this.plugin.messages().send(
                player,
                "command.create_failed_too_close",
                this.plugin.messages().tag("table_id", failure.tableId())
            );
            case BLOCKED_SPACE -> this.plugin.messages().send(
                player,
                "command.create_failed_blocked",
                this.plugin.messages().tag("x", String.valueOf(failure.x())),
                this.plugin.messages().tag("y", String.valueOf(failure.y())),
                this.plugin.messages().tag("z", String.valueOf(failure.z()))
            );
            case NOT_ENOUGH_HEIGHT -> this.plugin.messages().send(player, "command.create_failed_height");
            case NOT_IN_GAME_ROOM -> this.plugin.messages().send(player, "command.create_failed_not_in_room");
            case PROTECTED_AREA -> this.plugin.messages().send(player, "command.create_failed_protected");
        }
    }

    static CreateTableFailure firstBlockedTableSpace(Location center) {
        World world = center == null ? null : center.getWorld();
        if (world == null) {
            return failure(CreateTableFailureReason.INVALID_LOCATION);
        }

        int minY = Math.max(world.getMinHeight(), center.getBlockY());
        int maxY = Math.min(world.getMaxHeight() - 1, center.getBlockY() + CLEARANCE_HEIGHT_BLOCKS - 1);
        if (maxY - minY + 1 < CLEARANCE_HEIGHT_BLOCKS) {
            return failure(CreateTableFailureReason.NOT_ENOUGH_HEIGHT);
        }

        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        for (int y = minY; y <= maxY; y++) {
            for (int x = centerX - CLEARANCE_RADIUS_BLOCKS; x <= centerX + CLEARANCE_RADIUS_BLOCKS; x++) {
                for (int z = centerZ - CLEARANCE_RADIUS_BLOCKS; z <= centerZ + CLEARANCE_RADIUS_BLOCKS; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block == null || !block.isPassable() || block.isLiquid()) {
                        return new CreateTableFailure(CreateTableFailureReason.BLOCKED_SPACE, null, x, y, z);
                    }
                }
            }
        }
        return null;
    }

    static boolean isOverlappingTableCenter(Location left, Location right) {
        if (left == null || right == null || left.getWorld() == null || right.getWorld() == null) {
            return false;
        }
        if (!left.getWorld().equals(right.getWorld())) {
            return false;
        }
        if (Math.abs(left.getY() - right.getY()) > VERTICAL_OVERLAP_DISTANCE) {
            return false;
        }
        return horizontalDistanceSquared(left, right) < MIN_CENTER_DISTANCE_XZ * MIN_CENTER_DISTANCE_XZ;
    }

    static List<Location> tableFootprint(Location center) {
        World world = center == null ? null : center.getWorld();
        if (world == null) {
            return List.of();
        }
        int diameter = CLEARANCE_RADIUS_BLOCKS * 2 + 1;
        List<Location> footprint = new ArrayList<>(diameter * diameter);
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        for (int x = centerX - CLEARANCE_RADIUS_BLOCKS; x <= centerX + CLEARANCE_RADIUS_BLOCKS; x++) {
            for (int z = centerZ - CLEARANCE_RADIUS_BLOCKS; z <= centerZ + CLEARANCE_RADIUS_BLOCKS; z++) {
                footprint.add(new Location(world, x + 0.5D, centerY, z + 0.5D));
            }
        }
        return List.copyOf(footprint);
    }

    private CreateTableFailure validateTablePlacement(Location center) {
        if (center == null || center.getWorld() == null) {
            return failure(CreateTableFailureReason.INVALID_LOCATION);
        }
        MahjongTableSession overlappingTable = this.overlappingTable(center);
        if (overlappingTable != null) {
            return new CreateTableFailure(CreateTableFailureReason.TOO_CLOSE_TO_TABLE, overlappingTable.id(), null, null, null);
        }
        return firstBlockedTableSpace(center);
    }

    private CreateTableFailure validateGameRoomRestriction(Location center) {
        top.ellan.mahjong.gameroom.GameRoomManager gameRoomManager = this.plugin.gameRoomManager();
        if (gameRoomManager == null || !gameRoomManager.isRestrictNewTables()) {
            return null;
        }
        return gameRoomManager.isTableInAnyRoom(center)
            ? null
            : failure(CreateTableFailureReason.NOT_IN_GAME_ROOM);
    }

    private MahjongTableSession overlappingTable(Location center) {
        MahjongTableSession nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (MahjongTableSession table : this.directory.tables()) {
            Location tableCenter = table.center();
            if (!isOverlappingTableCenter(center, tableCenter)) {
                continue;
            }
            double distanceSquared = horizontalDistanceSquared(center, tableCenter);
            if (distanceSquared < nearestDistanceSquared) {
                nearest = table;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    private static double horizontalDistanceSquared(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static CreateTableFailure failure(CreateTableFailureReason reason) {
        return new CreateTableFailure(reason, null, null, null, null);
    }
}
