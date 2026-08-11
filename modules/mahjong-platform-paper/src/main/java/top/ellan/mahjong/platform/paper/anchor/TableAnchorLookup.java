package top.ellan.mahjong.platform.paper.anchor;

import java.util.Optional;
import org.bukkit.Location;
import top.ellan.mahjong.domain.table.TableId;

/** Read-only loaded-world anchor lookup shared with Paper-backed render adapters. */
@FunctionalInterface
public interface TableAnchorLookup {
    Optional<Location> location(TableId tableId);
}
