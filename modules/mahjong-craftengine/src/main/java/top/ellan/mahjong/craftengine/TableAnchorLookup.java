package top.ellan.mahjong.craftengine;

import java.util.Optional;
import org.bukkit.Location;
import top.ellan.mahjong.domain.TableId;

/** Read-only Bukkit anchor lookup implemented by the Paper adapter. */
@FunctionalInterface
public interface TableAnchorLookup {
    Optional<Location> location(TableId tableId);
}
