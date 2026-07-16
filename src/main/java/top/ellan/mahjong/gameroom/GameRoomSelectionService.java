package top.ellan.mahjong.gameroom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

public final class GameRoomSelectionService {
    private static final int PREVIEW_PARTICLE_CAP = 600;
    private static final double PREVIEW_RANGE_BLOCKS = 64.0D;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public void setFirst(UUID playerId, Location location) {
        Selection selection = this.selections.getOrDefault(playerId, new Selection(null, null));
        this.selections.put(playerId, new Selection(location == null ? null : location.clone(), selection.second()));
    }

    public void setSecond(UUID playerId, Location location) {
        Selection selection = this.selections.getOrDefault(playerId, new Selection(null, null));
        this.selections.put(playerId, new Selection(selection.first(), location == null ? null : location.clone()));
    }

    public Selection selection(UUID playerId) {
        return this.selections.get(playerId);
    }

    public List<Location> previewPoints(UUID playerId, Location viewerLocation) {
        return this.previewPoints(this.selection(playerId), viewerLocation);
    }

    public List<Location> previewPoints(Selection selection, Location viewerLocation) {
        if (selection == null) {
            return List.of();
        }
        if (selection.complete()) {
            return cuboidOutline(selection.first(), selection.second(), viewerLocation);
        }
        if (selection.first() != null && selection.second() != null) {
            return List.of();
        }
        Location anchor = selection.first() != null ? selection.first() : selection.second();
        return anchor == null ? List.of() : cuboidOutline(anchor, anchor, viewerLocation);
    }

    public void clear(UUID playerId) {
        this.selections.remove(playerId);
    }

    public void clearAll() {
        this.selections.clear();
    }

    public record Selection(Location first, Location second) {
        public boolean complete() {
            return this.first != null && this.second != null && this.first.getWorld() != null && this.first.getWorld().equals(this.second.getWorld());
        }
    }

    private static List<Location> cuboidOutline(Location first, Location second, Location viewerLocation) {
        if (first == null || second == null || first.getWorld() == null || second.getWorld() == null || !first.getWorld().equals(second.getWorld())) {
            return List.of();
        }

        World world = first.getWorld();
        double minX = Math.min(first.getBlockX(), second.getBlockX());
        double minY = Math.min(first.getBlockY(), second.getBlockY());
        double minZ = Math.min(first.getBlockZ(), second.getBlockZ());
        double maxX = Math.max(first.getBlockX(), second.getBlockX()) + 1.0D;
        double maxY = Math.max(first.getBlockY(), second.getBlockY()) + 1.0D;
        double maxZ = Math.max(first.getBlockZ(), second.getBlockZ()) + 1.0D;

        int spacing = previewSpacing(maxX - minX, maxY - minY, maxZ - minZ);
        List<Location> points = new ArrayList<>();
        Set<ParticlePointKey> seen = new HashSet<>();

        addLine(world, points, seen, viewerLocation, minX, minY, minZ, maxX, minY, minZ, spacing);
        addLine(world, points, seen, viewerLocation, minX, maxY, minZ, maxX, maxY, minZ, spacing);
        addLine(world, points, seen, viewerLocation, minX, minY, maxZ, maxX, minY, maxZ, spacing);
        addLine(world, points, seen, viewerLocation, minX, maxY, maxZ, maxX, maxY, maxZ, spacing);

        addLine(world, points, seen, viewerLocation, minX, minY, minZ, minX, maxY, minZ, spacing);
        addLine(world, points, seen, viewerLocation, maxX, minY, minZ, maxX, maxY, minZ, spacing);
        addLine(world, points, seen, viewerLocation, minX, minY, maxZ, minX, maxY, maxZ, spacing);
        addLine(world, points, seen, viewerLocation, maxX, minY, maxZ, maxX, maxY, maxZ, spacing);

        addLine(world, points, seen, viewerLocation, minX, minY, minZ, minX, minY, maxZ, spacing);
        addLine(world, points, seen, viewerLocation, maxX, minY, minZ, maxX, minY, maxZ, spacing);
        addLine(world, points, seen, viewerLocation, minX, maxY, minZ, minX, maxY, maxZ, spacing);
        addLine(world, points, seen, viewerLocation, maxX, maxY, minZ, maxX, maxY, maxZ, spacing);

        return List.copyOf(points);
    }

    private static int previewSpacing(double width, double height, double depth) {
        double edgePointEstimate = 4.0D * (Math.max(1.0D, width) + Math.max(1.0D, height) + Math.max(1.0D, depth));
        return Math.max(1, (int) Math.ceil(edgePointEstimate / PREVIEW_PARTICLE_CAP));
    }

    private static void addLine(
        World world,
        List<Location> points,
        Set<ParticlePointKey> seen,
        Location viewerLocation,
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2,
        int spacing
    ) {
        int steps = Math.max(1, (int) Math.round(Math.abs(x2 - x1) + Math.abs(y2 - y1) + Math.abs(z2 - z1)));
        for (int step = 0; step <= steps; step += spacing) {
            addPoint(world, points, seen, viewerLocation, x1, y1, z1, x2, y2, z2, steps, step);
        }
        if (steps % spacing != 0) {
            addPoint(world, points, seen, viewerLocation, x1, y1, z1, x2, y2, z2, steps, steps);
        }
    }

    private static void addPoint(
        World world,
        List<Location> points,
        Set<ParticlePointKey> seen,
        Location viewerLocation,
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2,
        int steps,
        int step
    ) {
        double ratio = steps == 0 ? 0.0D : (double) step / (double) steps;
        Location point = new Location(
            world,
            x1 + (x2 - x1) * ratio,
            y1 + (y2 - y1) * ratio,
            z1 + (z2 - z1) * ratio
        );
        if (!isVisibleFrom(point, viewerLocation)) {
            return;
        }
        ParticlePointKey key = ParticlePointKey.of(point);
        if (seen.add(key)) {
            points.add(point);
        }
    }

    private static boolean isVisibleFrom(Location point, Location viewerLocation) {
        if (viewerLocation == null) {
            return true;
        }
        if (point.getWorld() == null || viewerLocation.getWorld() == null || !point.getWorld().equals(viewerLocation.getWorld())) {
            return false;
        }
        return point.distanceSquared(viewerLocation) <= PREVIEW_RANGE_BLOCKS * PREVIEW_RANGE_BLOCKS;
    }

    private record ParticlePointKey(long x, long y, long z) {
        private static ParticlePointKey of(Location location) {
            return new ParticlePointKey(
                Math.round(location.getX() * 1000.0D),
                Math.round(location.getY() * 1000.0D),
                Math.round(location.getZ() * 1000.0D)
            );
        }
    }
}
