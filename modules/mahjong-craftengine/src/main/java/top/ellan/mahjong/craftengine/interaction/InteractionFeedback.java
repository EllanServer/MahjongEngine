package top.ellan.mahjong.craftengine.interaction;

import org.bukkit.entity.Player;
import top.ellan.mahjong.application.table.TableActionResult;

/** Schedules user feedback on the player's owning entity thread. */
@FunctionalInterface
public interface InteractionFeedback {
    void accept(Player player, TableActionResult result, Throwable failure);
}
