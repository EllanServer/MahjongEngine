package top.ellan.mahjong.platform.paper.anchor;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.platform.paper.region.RegionKey;
import top.ellan.mahjong.platform.paper.region.TableRegionResolver;

/** Immutable-value anchor lookup shared by Paper and the CE region budget coordinator. */
public final class PaperTableAnchorRegistry implements TableRegionResolver, TableAnchorLookup {
    private static final int CHUNK_SHIFT = 4;
    private static final double MAX_COLLISION_DISTANCE = 64.0D;
    private final ConcurrentHashMap<TableId, AnchorValue> anchors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AnchorCell, Set<TableId>> anchorsByChunk =
            new ConcurrentHashMap<>();

    public synchronized void register(TableId tableId, Location anchor) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(anchor, "anchor");
        World world = anchor.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Table anchor requires a world");
        }
        AnchorValue value =
                new AnchorValue(
                        world.getUID(),
                        world.getName(),
                        anchor.getX(),
                        anchor.getY(),
                        anchor.getZ(),
                        anchor.getYaw(),
                        anchor.getPitch());
        AnchorValue previous = anchors.put(tableId, value);
        if (previous != null) {
            removeFromSpatialIndex(tableId, previous);
        }
        anchorsByChunk.computeIfAbsent(cell(value), ignored -> ConcurrentHashMap.newKeySet())
                .add(tableId);
    }

    public synchronized void remove(TableId tableId) {
        TableId required = Objects.requireNonNull(tableId, "tableId");
        AnchorValue removed = anchors.remove(required);
        if (removed != null) {
            removeFromSpatialIndex(required, removed);
        }
    }

    public Optional<Location> location(TableId tableId) {
        AnchorValue anchor = anchors.get(Objects.requireNonNull(tableId, "tableId"));
        if (anchor == null) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(anchor.worldId());
        if (world == null) {
            world = Bukkit.getWorld(anchor.worldName());
        }
        return world == null
                ? Optional.empty()
                : Optional.of(
                        new Location(
                                world,
                                anchor.x(),
                                anchor.y(),
                                anchor.z(),
                                anchor.yaw(),
                                anchor.pitch()));
    }

    /**
     * Checks only the finite set of anchor buckets around one proposed table location.
     * No Bukkit world, entity, player or full table collection is traversed.
     */
    public Optional<TableId> overlapping(
            Location center, double horizontalDistance, double verticalDistance) {
        Objects.requireNonNull(center, "center");
        World world = center.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        if (!Double.isFinite(horizontalDistance)
                || horizontalDistance < 0.0D
                || horizontalDistance > MAX_COLLISION_DISTANCE
                || !Double.isFinite(verticalDistance)
                || verticalDistance < 0.0D
                || verticalDistance > MAX_COLLISION_DISTANCE) {
            throw new IllegalArgumentException("Collision distances must be finite and at most 64 blocks");
        }
        int chunkRadius = (int) Math.ceil(horizontalDistance / 16.0D);
        int centerChunkX = floorBlock(center.getX()) >> CHUNK_SHIFT;
        int centerChunkZ = floorBlock(center.getZ()) >> CHUNK_SHIFT;
        double distanceSquaredLimit = horizontalDistance * horizontalDistance;
        TableId nearest = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        for (int chunkX = centerChunkX - chunkRadius;
                chunkX <= centerChunkX + chunkRadius;
                chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius;
                    chunkZ <= centerChunkZ + chunkRadius;
                    chunkZ++) {
                Set<TableId> candidates =
                        anchorsByChunk.get(new AnchorCell(world.getUID(), chunkX, chunkZ));
                if (candidates == null) {
                    continue;
                }
                for (TableId candidate : candidates) {
                    AnchorValue anchor = anchors.get(candidate);
                    if (anchor == null
                            || !anchor.worldId().equals(world.getUID())
                            || Math.abs(anchor.y() - center.getY()) > verticalDistance) {
                        continue;
                    }
                    double deltaX = anchor.x() - center.getX();
                    double deltaZ = anchor.z() - center.getZ();
                    double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
                    if (distanceSquared < distanceSquaredLimit
                            && distanceSquared < nearestDistanceSquared) {
                        nearest = candidate;
                        nearestDistanceSquared = distanceSquared;
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    @Override
    public RegionKey regionFor(TableId tableId) {
        AnchorValue anchor = anchors.get(Objects.requireNonNull(tableId, "tableId"));
        if (anchor == null) {
            throw new IllegalStateException("No Paper anchor for table " + tableId);
        }
        return new RegionKey(
                anchor.worldId().toString(), floorBlock(anchor.x()) >> 4, floorBlock(anchor.z()) >> 4);
    }

    private static int floorBlock(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private void removeFromSpatialIndex(TableId tableId, AnchorValue anchor) {
        AnchorCell key = cell(anchor);
        Set<TableId> bucket = anchorsByChunk.get(key);
        if (bucket == null) {
            return;
        }
        bucket.remove(tableId);
        if (bucket.isEmpty()) {
            anchorsByChunk.remove(key, bucket);
        }
    }

    private static AnchorCell cell(AnchorValue anchor) {
        return new AnchorCell(
                anchor.worldId(),
                floorBlock(anchor.x()) >> CHUNK_SHIFT,
                floorBlock(anchor.z()) >> CHUNK_SHIFT);
    }

    private record AnchorCell(UUID worldId, int chunkX, int chunkZ) {
        private AnchorCell {
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    private record AnchorValue(
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {
        private AnchorValue {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(worldName, "worldName");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Table anchor coordinates must be finite");
            }
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Table anchor rotation must be finite");
            }
        }
    }
}
