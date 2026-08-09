package top.ellan.mahjong.platform.paper.region;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.craftengine.port.RegionKey;
import top.ellan.mahjong.craftengine.port.RegionSchedulerPort;

/** Direct Paper/Folia region scheduler adapter; it has no blocking fallback. */
public final class PaperRegionScheduler implements RegionSchedulerPort {
    private final Plugin plugin;

    public PaperRegionScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void nextTick(RegionKey region, Runnable task) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(task, "task");
        if (!plugin.isEnabled()) {
            throw new IllegalStateException("MahjongPaper is disabled");
        }
        World world = resolveWorld(region.worldId());
        Bukkit.getRegionScheduler()
                .runDelayed(
                        plugin,
                        world,
                        region.chunkX(),
                        region.chunkZ(),
                        ignored -> task.run(),
                        1L);
    }

    private static World resolveWorld(String worldId) {
        World world = null;
        try {
            world = Bukkit.getWorld(UUID.fromString(worldId));
        } catch (IllegalArgumentException ignored) {
            // RegionKey also supports a stable world name for test and migration adapters.
        }
        if (world == null) {
            world = Bukkit.getWorld(worldId);
        }
        if (world == null) {
            throw new IllegalStateException("World is not loaded: " + worldId);
        }
        return world;
    }
}
