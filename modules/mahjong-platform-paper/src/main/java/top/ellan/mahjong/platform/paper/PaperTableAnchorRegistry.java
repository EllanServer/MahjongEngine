package top.ellan.mahjong.platform.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import top.ellan.mahjong.craftengine.RegionKey;
import top.ellan.mahjong.craftengine.TableRegionResolver;
import top.ellan.mahjong.craftengine.TableAnchorLookup;
import top.ellan.mahjong.domain.TableId;

/** Immutable-copy anchor lookup shared by Paper and the CE region budget coordinator. */
public final class PaperTableAnchorRegistry implements TableRegionResolver, TableAnchorLookup {
    private final ConcurrentHashMap<TableId, Location> anchors = new ConcurrentHashMap<>();

    public void register(TableId tableId, Location anchor) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(anchor, "anchor");
        if (anchor.getWorld() == null) {
            throw new IllegalArgumentException("Table anchor requires a world");
        }
        anchors.put(tableId, anchor.clone());
    }

    public void remove(TableId tableId) {
        anchors.remove(Objects.requireNonNull(tableId, "tableId"));
    }

    public Optional<Location> location(TableId tableId) {
        Location anchor = anchors.get(Objects.requireNonNull(tableId, "tableId"));
        return anchor == null ? Optional.empty() : Optional.of(anchor.clone());
    }

    @Override
    public RegionKey regionFor(TableId tableId) {
        Location anchor =
                location(tableId)
                        .orElseThrow(
                                () -> new IllegalStateException("No Paper anchor for table " + tableId));
        World world = Objects.requireNonNull(anchor.getWorld(), "anchor world");
        return new RegionKey(
                world.getUID().toString(), anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4);
    }
}
