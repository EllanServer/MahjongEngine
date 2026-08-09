package top.ellan.mahjong.craftengine.port;

import java.util.Optional;
import org.bukkit.Location;
import top.ellan.mahjong.domain.table.TableId;

/** Read-only Bukkit anchor lookup implemented by the Paper adapter. */
@FunctionalInterface
public interface TableAnchorLookup {
    Optional<Location> location(TableId tableId);
}
