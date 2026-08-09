package top.ellan.mahjong.platform.paper.anchor;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.domain.table.TableAnchor;

/** Region-safe conversion between domain anchors and loaded Paper worlds. */
public final class PaperTableAnchorService {
    private final Plugin plugin;
    private final PaperTableAnchorRegistry registry;

    public PaperTableAnchorService(Plugin plugin, PaperTableAnchorRegistry registry) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void register(TableAnchor anchor, Location location) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null
                || !anchor.worldId().equals(location.getWorld().getUID().toString())) {
            throw new IllegalArgumentException("domain and Paper anchor worlds differ");
        }
        registry.register(anchor.tableId(), location);
    }

    public CompletionStage<Void> restore(TableAnchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler()
                    .execute(
                            plugin,
                            () -> {
                                try {
                                    World world = resolveWorld(anchor.worldId());
                                    registry.register(
                                            anchor.tableId(),
                                            new Location(
                                                    world,
                                                    anchor.x(),
                                                    anchor.y(),
                                                    anchor.z(),
                                                    anchor.yaw(),
                                                    anchor.pitch()));
                                    result.complete(null);
                                } catch (RuntimeException failure) {
                                    result.completeExceptionally(failure);
                                }
                            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public void remove(TableAnchor anchor) {
        registry.remove(Objects.requireNonNull(anchor, "anchor").tableId());
    }

    private static World resolveWorld(String worldId) {
        UUID worldUuid;
        try {
            worldUuid = UUID.fromString(worldId);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Stored world id is not a UUID: " + worldId, invalid);
        }
        World world = Bukkit.getWorld(worldUuid);
        if (world == null) {
            throw new IllegalStateException("World is not loaded: " + worldId);
        }
        return world;
    }
}
