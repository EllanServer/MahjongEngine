package top.ellan.mahjong.craftengine.port;

import org.bukkit.entity.Player;
import top.ellan.mahjong.domain.table.TableId;

/** Opens the platform-native control surface for one managed physical table. */
@FunctionalInterface
public interface TableDialogPort {
    void open(Player player, TableId tableId);
}
