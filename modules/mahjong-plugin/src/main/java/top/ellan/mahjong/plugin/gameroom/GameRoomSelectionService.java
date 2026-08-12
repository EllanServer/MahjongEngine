package top.ellan.mahjong.plugin.gameroom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;

/** Per-admin two-point selection state with a one-shot, client-rendered outline. */
public final class GameRoomSelectionService {
    private static final int PREVIEW_POINT_CAP = 192;
    private final ConcurrentHashMap<UUID, Selection> selections =
            new ConcurrentHashMap<>();

    public Selection setFirst(UUID playerId, Location location) {
        Objects.requireNonNull(playerId, "playerId");
        Location point = blockPoint(location);
        return selections.compute(
                playerId,
                (ignored, existing) ->
                        new Selection(point, existing == null ? null : existing.second()));
    }

    public Selection setSecond(UUID playerId, Location location) {
        Objects.requireNonNull(playerId, "playerId");
        Location point = blockPoint(location);
        return selections.compute(
                playerId,
                (ignored, existing) ->
                        new Selection(existing == null ? null : existing.first(), point));
    }

    public Selection selection(UUID playerId) {
        return selections.get(Objects.requireNonNull(playerId, "playerId"));
    }

    public void clear(UUID playerId) {
        selections.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    public void close() {
        selections.clear();
    }

    public List<Location> previewPoints(Selection selection) {
        if (selection == null) {
            return List.of();
        }
        if (!selection.complete()) {
            Location point = selection.first() == null ? selection.second() : selection.first();
            return point == null
                    ? List.of()
                    : List.of(point.clone().add(0.5D, 0.5D, 0.5D));
        }
        Location first = selection.first();
        Location second = selection.second();
        World world = first.getWorld();
        if (world == null) {
            return List.of();
        }
        double minX = Math.min(first.getBlockX(), second.getBlockX());
        double minY = Math.min(first.getBlockY(), second.getBlockY());
        double minZ = Math.min(first.getBlockZ(), second.getBlockZ());
        double maxX = Math.max(first.getBlockX(), second.getBlockX()) + 1.0D;
        double maxY = Math.max(first.getBlockY(), second.getBlockY()) + 1.0D;
        double maxZ = Math.max(first.getBlockZ(), second.getBlockZ()) + 1.0D;
        int spacing = previewSpacing(maxX - minX, maxY - minY, maxZ - minZ);
        ArrayList<Location> points = new ArrayList<>();
        HashSet<PointKey> seen = new HashSet<>();
        addEdges(world, points, seen, minX, minY, minZ, maxX, maxY, maxZ, spacing);
        return List.copyOf(points);
    }

    private static void addEdges(
            World world,
            List<Location> points,
            Set<PointKey> seen,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            int spacing) {
        double[][] corners = {
            {minX, minY, minZ}, {maxX, minY, minZ},
            {minX, maxY, minZ}, {maxX, maxY, minZ},
            {minX, minY, maxZ}, {maxX, minY, maxZ},
            {minX, maxY, maxZ}, {maxX, maxY, maxZ}
        };
        int[][] edges = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] edge : edges) {
            double[] from = corners[edge[0]];
            double[] to = corners[edge[1]];
            addLine(world, points, seen, from, to, spacing);
        }
    }

    private static void addLine(
            World world,
            List<Location> points,
            Set<PointKey> seen,
            double[] from,
            double[] to,
            int spacing) {
        int steps = Math.max(
                1,
                (int)
                        Math.round(
                                Math.abs(to[0] - from[0])
                                        + Math.abs(to[1] - from[1])
                                        + Math.abs(to[2] - from[2])));
        for (int step = 0; step <= steps; step += spacing) {
            addPoint(world, points, seen, from, to, steps, step);
        }
        if (steps % spacing != 0) {
            addPoint(world, points, seen, from, to, steps, steps);
        }
    }

    private static void addPoint(
            World world,
            List<Location> points,
            Set<PointKey> seen,
            double[] from,
            double[] to,
            int steps,
            int step) {
        if (points.size() >= PREVIEW_POINT_CAP) {
            return;
        }
        double ratio = (double) step / steps;
        Location point =
                new Location(
                        world,
                        from[0] + (to[0] - from[0]) * ratio,
                        from[1] + (to[1] - from[1]) * ratio,
                        from[2] + (to[2] - from[2]) * ratio);
        if (seen.add(PointKey.of(point))) {
            points.add(point);
        }
    }

    private static int previewSpacing(double width, double height, double depth) {
        double estimate = 4.0D * (Math.max(1.0D, width) + Math.max(1.0D, height) + Math.max(1.0D, depth));
        return Math.max(1, (int) Math.ceil(estimate / PREVIEW_POINT_CAP));
    }

    private static Location blockPoint(Location location) {
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Selection point requires a world");
        }
        return new Location(
                location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    public record Selection(Location first, Location second) {
        public boolean complete() {
            return first != null
                    && second != null
                    && first.getWorld() != null
                    && first.getWorld().equals(second.getWorld());
        }
    }

    private record PointKey(long x, long y, long z) {
        private static PointKey of(Location location) {
            return new PointKey(
                    Math.round(location.getX() * 1_000.0D),
                    Math.round(location.getY() * 1_000.0D),
                    Math.round(location.getZ() * 1_000.0D));
        }
    }
}
