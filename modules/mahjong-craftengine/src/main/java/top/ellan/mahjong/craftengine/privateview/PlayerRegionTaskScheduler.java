package top.ellan.mahjong.craftengine.privateview;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Executes client projection work only on the target player's Folia entity scheduler. */
final class PlayerRegionTaskScheduler {
    private final Plugin plugin;

    PlayerRegionTaskScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void execute(Player player, Runnable task) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    void executeLater(Player player, Runnable task, long delayTicks) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        if (!plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        player.getScheduler()
                .runDelayed(
                        plugin,
                        ignored -> task.run(),
                        null,
                        Math.max(1L, delayTicks));
    }
}
