package top.ellan.mahjong.platform.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import top.ellan.mahjong.craftengine.port.RegionKey;
import top.ellan.mahjong.craftengine.port.TableRegionResolver;
import top.ellan.mahjong.craftengine.port.TableAnchorLookup;
import top.ellan.mahjong.domain.table.TableId;

/** Immutable-value anchor lookup shared by Paper and the CE region budget coordinator. */
public final class PaperTableAnchorRegistry implements TableRegionResolver, TableAnchorLookup {
    private final ConcurrentHashMap<TableId, AnchorValue> anchors = new ConcurrentHashMap<>();

    public void register(TableId tableId, Location anchor) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(anchor, "anchor");
        World world = anchor.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Table anchor requires a world");
        }
        anchors.put(
                tableId,
                new AnchorValue(
                        world.getUID(),
                        world.getName(),
                        anchor.getX(),
                        anchor.getY(),
                        anchor.getZ(),
                        anchor.getYaw(),
                        anchor.getPitch()));
    }

    public void remove(TableId tableId) {
        anchors.remove(Objects.requireNonNull(tableId, "tableId"));
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
