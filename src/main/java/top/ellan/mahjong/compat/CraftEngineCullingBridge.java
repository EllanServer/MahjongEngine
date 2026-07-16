package top.ellan.mahjong.compat;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.entity.culling.Cullable;
import net.momirealms.craftengine.core.entity.culling.CullingData;
import net.momirealms.craftengine.core.world.collision.AABB;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import top.ellan.mahjong.render.display.DisplayVisibilityRegistry;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CraftEngineCullingBridge {
    private final CraftEngineBridgeContext context;
    private final Map<Integer, TrackedCullableEntity> trackedCullableEntities = new ConcurrentHashMap<>();

    CraftEngineCullingBridge(CraftEngineBridgeContext context) {
        this.context = context;
    }

    void registerCullableEntity(Entity entity) {
        if (entity == null || !isCullableEntity(entity) || !this.context.plugin().isEnabled() || !this.isAvailable()) {
            return;
        }

        int entityId = entity.getEntityId();
        UUID entityUuid = entity.getUniqueId();
        TrackedCullableEntity tracked = new TrackedCullableEntity(entity, entityId, entityUuid, this.createCullable(entity, entityId));
        TrackedCullableEntity previous = this.trackedCullableEntities.put(entityId, tracked);
        if (previous != null && previous.entityUuid().equals(entityUuid)) {
            return;
        }
        if (previous != null) {
            this.trackedCullableEntities.remove(previous.entityId());
            for (Player player : this.onlinePlayersSnapshot()) {
                this.context.plugin().scheduler().runEntity(player, () -> this.removeTrackedEntity(player, previous.entityId()));
            }
            this.trackedCullableEntities.put(entityId, tracked);
        }

        for (Player player : this.onlinePlayersSnapshot()) {
            this.context.plugin().scheduler().runEntity(player, () -> this.addTrackedEntity(player, tracked));
        }
    }

    void unregisterCullableEntity(Entity entity) {
        if (entity == null) {
            return;
        }

        TrackedCullableEntity tracked = this.trackedCullableEntities.remove(entity.getEntityId());
        if (tracked == null || !this.context.plugin().isEnabled()) {
            return;
        }
        for (Player player : this.onlinePlayersSnapshot()) {
            this.context.plugin().scheduler().runEntity(player, () -> this.removeTrackedEntity(player, tracked.entityId()));
        }
    }

    void syncTrackedEntitiesFor(Player player) {
        if (player == null || !player.isOnline() || !this.context.plugin().isEnabled() || !this.isAvailable()) {
            return;
        }
        for (TrackedCullableEntity tracked : this.trackedCullableEntities.values()) {
            this.addTrackedEntity(player, tracked);
            this.scheduleViewerVisibility(tracked.entityId(), tracked.entity(), player, true);
        }
    }

    void clearTrackedCullables() {
        this.trackedCullableEntities.clear();
    }

    private Cullable createCullable(Entity entity, int entityId) {
        CullingData cullingData = this.createCullingData(entity);
        return new Cullable() {
            @Override
            public void show(net.momirealms.craftengine.core.entity.player.Player player) {
                CraftEngineCullingBridge.this.scheduleViewerVisibility(entityId, entity, platformPlayer(player), true);
            }

            @Override
            public void hide(net.momirealms.craftengine.core.entity.player.Player player) {
                CraftEngineCullingBridge.this.scheduleViewerVisibility(entityId, entity, platformPlayer(player), false);
            }

            @Override
            public CullingData cullingData() {
                return cullingData;
            }

            @Override
            public int hashCode() {
                return entityId;
            }

            @Override
            public String toString() {
                return "MahjongPaperCullable[" + entityId + ']';
            }
        };
    }

    private CullingData createCullingData(Entity entity) {
        BoundingBox box = entity.getBoundingBox();
        AABB aabb = new AABB(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        return new CullingData(aabb, maxDistance(entity), 0.25D, true);
    }

    private void addTrackedEntity(Player player, TrackedCullableEntity tracked) {
        try {
            net.momirealms.craftengine.core.entity.player.Player cePlayer = BukkitAdaptor.adapt(player);
            if (cePlayer != null) {
                cePlayer.addTrackedEntity(tracked.entityId(), tracked.cullable());
            }
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine tracked entity add failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private void removeTrackedEntity(Player player, int entityId) {
        try {
            net.momirealms.craftengine.core.entity.player.Player cePlayer = BukkitAdaptor.adapt(player);
            if (cePlayer != null) {
                cePlayer.removeTrackedEntity(entityId);
            }
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine tracked entity remove failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private void scheduleViewerVisibility(int entityId, Entity entity, Player viewer, boolean visible) {
        if (entity == null || viewer == null || !this.context.plugin().isEnabled()) {
            return;
        }
        this.context.plugin().scheduler().runEntity(viewer, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            // Folia/Paper region threading: showEntity/hideEntity touches both viewer and target entity internals.
            // Only run when the current thread owns both entities to avoid cross-region thread-check violations.
            if (!PaperCompatibility.isOwnedByCurrentRegion(viewer) || !PaperCompatibility.isOwnedByCurrentRegion(entity)) {
                return;
            }
            if (!entity.isValid() || entity.isDead()) {
                return;
            }
            if (!DisplayVisibilityRegistry.canView(entityId, viewer.getUniqueId())) {
                viewer.hideEntity(this.context.bukkitPlugin(), entity);
                return;
            }
            if (visible) {
                viewer.showEntity(this.context.bukkitPlugin(), entity);
            } else {
                viewer.hideEntity(this.context.bukkitPlugin(), entity);
            }
        });
    }

    private List<Player> onlinePlayersSnapshot() {
        return List.copyOf(Bukkit.getOnlinePlayers());
    }

    boolean isAvailable() {
        Plugin craftEngine = this.context.craftEnginePlugin();
        return craftEngine != null && craftEngine.isEnabled();
    }

    private static Player platformPlayer(net.momirealms.craftengine.core.entity.player.Player player) {
        if (player == null) {
            return null;
        }
        Object platformPlayer = player.platformPlayer();
        return platformPlayer instanceof Player bukkitPlayer ? bukkitPlayer : null;
    }

    private static int maxDistance(Entity entity) {
        if (entity instanceof Display display) {
            return Math.max(1, (int) Math.ceil(display.getViewRange()));
        }
        if (entity instanceof Interaction interaction) {
            return Math.max(8, (int) Math.ceil(interaction.getInteractionWidth() * 16.0D));
        }
        return 32;
    }

    private static boolean isCullableEntity(Entity entity) {
        return entity instanceof Display || entity instanceof Interaction;
    }

    private record TrackedCullableEntity(Entity entity, int entityId, UUID entityUuid, Cullable cullable) {
    }
}
