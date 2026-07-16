package top.ellan.mahjong.compat;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.seat.Seat;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.TableDisplayRegistry;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class CraftEngineFurnitureBridge {
    static final String TABLE_HITBOX_ITEM_ID = "mahjongpaper:table_hitbox";
    static final String HAND_TILE_HITBOX_ITEM_ID = "mahjongpaper:hand_tile_hitbox";
    static final String SEAT_HITBOX_ITEM_ID = "mahjongpaper:seat_hitbox";

    private static final String MANAGED_FURNITURE_KEY = "managed_craftengine_furniture";
    private static final String MAHJONGPAPER_FURNITURE_PREFIX = "mahjongpaper:";
    private static final int STARTUP_FURNITURE_CLEANUP_REMOVALS_PER_TICK = 8;

    private final CraftEngineBridgeContext context;
    private final boolean preferFurnitureHitbox;
    private final Set<String> warnedUnavailableFurnitureIds = ConcurrentHashMap.newKeySet();
    private volatile NamespacedKey managedFurnitureKey;

    CraftEngineFurnitureBridge(CraftEngineBridgeContext context, boolean preferFurnitureHitbox) {
        this.context = context;
        this.preferFurnitureHitbox = preferFurnitureHitbox;
    }

    int cleanupMahjongFurniture() {
        Plugin craftEngine = this.context.craftEnginePlugin();
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return 0;
        }

        AtomicInteger removed = new AtomicInteger();
        int scheduledRegions = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                Location chunkCenter = new Location(world, (chunkX << 4) + 8.0D, world.getMinHeight(), (chunkZ << 4) + 8.0D);
                scheduledRegions++;
                this.context.plugin().scheduler().runRegion(chunkCenter, () -> {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        return;
                    }
                    int scheduledFallbackRemovals = 0;
                    for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                        if (!this.isManagedFurnitureEntity(entity) && !this.isMahjongFurnitureEntity(entity)) {
                            continue;
                        }
                        boolean removedByCraftEngine = this.removeFurniture(entity);
                        if (!removedByCraftEngine && entity.isValid()) {
                            if (entity instanceof Interaction interaction) {
                                interaction.setResponsive(false);
                            }
                            long delayTicks = 1L + (scheduledFallbackRemovals / STARTUP_FURNITURE_CLEANUP_REMOVALS_PER_TICK);
                            this.context.plugin().scheduler().removeEntity(entity, delayTicks);
                            scheduledFallbackRemovals++;
                        }
                        removed.incrementAndGet();
                    }
                });
            }
        }

        if (scheduledRegions == 0) {
            return 0;
        }
        this.context.plugin().scheduler().runGlobalDelayed(() -> {
            int removedCount = removed.get();
            if (removedCount > 0) {
                this.context.plugin().getLogger().info(
                    "Removed " + removedCount + " leftover MahjongPaper CraftEngine furniture entities from previous sessions."
                );
                this.context.plugin().debug().log(
                    "lifecycle",
                    "Startup cleanup removed " + removedCount + " lingering mahjongpaper furniture entities."
                );
            }
        }, 40L);
        return 0;
    }

    Entity placeTableHitbox(Location location) {
        return this.placeFurniture(location, TABLE_HITBOX_ITEM_ID);
    }

    Entity placeHandTileHitbox(Location location, DisplayClickAction action) {
        return this.placeFurniture(location, HAND_TILE_HITBOX_ITEM_ID, action);
    }

    Entity placeSeatHitbox(Location location, DisplayClickAction action) {
        return this.placeFurniture(location, SEAT_HITBOX_ITEM_ID, action);
    }

    Entity placeSeatFurniture(Location location, String furnitureItemId, DisplayClickAction action) {
        Entity entity = this.placeFurniture(location, furnitureItemId);
        if (entity != null && action != null) {
            this.markManagedFurnitureEntity(entity);
            TableDisplayRegistry.register(entity.getEntityId(), action);
        }
        return entity;
    }

    Entity placeFurniture(Location location, String furnitureItemId, DisplayClickAction action) {
        Entity entity = this.placeFurniture(location, furnitureItemId);
        if (entity != null) {
            this.markManagedFurnitureEntity(entity);
            this.applyDisplayClickAction(entity, action);
        }
        return entity;
    }

    Entity placeFurniture(Location location, String furnitureItemId) {
        if (!this.preferFurnitureHitbox || !this.isCraftEngineAvailable()) {
            return null;
        }

        try {
            BukkitFurniture furniture = CraftEngineFurniture.place(location, Key.of(furnitureItemId));
            if (furniture == null) {
                this.warnUnavailableFurnitureId(furnitureItemId);
                return null;
            }
            Entity entity = furniture.bukkitEntity();
            if (entity != null) {
                this.markManagedFurnitureEntity(entity);
                entity.setPersistent(false);
                return entity;
            }
            return null;
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().getLogger().warning(
                "CraftEngine was detected, but MahjongPaper could not place CraftEngine furniture. CraftEngine-based interaction may be unavailable."
            );
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine furniture API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return null;
        }
    }

    boolean removeFurniture(Entity entity) {
        if (entity == null || !this.isCraftEngineAvailable()) {
            return false;
        }

        try {
            if (!CraftEngineFurniture.isFurniture(entity)) {
                return false;
            }
            return CraftEngineFurniture.remove(entity, false, false);
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine furniture remove API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return false;
        }
    }

    boolean isFurnitureEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (this.isManagedFurnitureEntity(entity)) {
            return true;
        }
        if (!this.isCraftEngineAvailable()) {
            return false;
        }
        try {
            return CraftEngineFurniture.isFurniture(entity);
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine furniture detection API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return false;
        }
    }

    boolean reconcileFurniture(Entity entity, Location location, String furnitureItemId, DisplayClickAction action) {
        if (entity == null || location == null || furnitureItemId == null || furnitureItemId.isBlank()) {
            return false;
        }
        // Hand-tile hitboxes move every discard/draw; respawning them avoids stale
        // CraftEngine interaction offsets when the furniture entity is teleported.
        if (HAND_TILE_HITBOX_ITEM_ID.equals(furnitureItemId)) {
            return false;
        }
        String existingFurnitureId = this.furnitureItemId(entity);
        if (!Objects.equals(existingFurnitureId, furnitureItemId)) {
            return false;
        }
        Location target = location.clone();
        this.context.plugin().scheduler().teleport(entity, target);
        this.markManagedFurnitureEntity(entity);
        this.applyDisplayClickAction(entity, action);
        return true;
    }

    boolean isManagedFurnitureEntity(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(this.managedFurnitureKey(), PersistentDataType.BYTE);
    }

    boolean isMahjongFurnitureEntity(Entity entity) {
        String itemId = this.furnitureItemId(entity);
        return itemId != null && itemId.startsWith(MAHJONGPAPER_FURNITURE_PREFIX);
    }

    String furnitureItemId(Entity entity) {
        if (entity == null || !this.isCraftEngineAvailable()) {
            return null;
        }
        try {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            return furniture == null ? null : furniture.id().toString();
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine furniture id API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return null;
        }
    }

    boolean isSeatEntity(Entity entity) {
        if (entity == null || !this.isCraftEngineAvailable()) {
            return false;
        }
        try {
            return CraftEngineFurniture.isSeat(entity);
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine seat detection API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return false;
        }
    }

    Entity furnitureEntityForSeat(Entity seatEntity) {
        if (seatEntity == null || !this.isCraftEngineAvailable()) {
            return null;
        }
        try {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureBySeat(seatEntity);
            return furniture == null ? null : furniture.bukkitEntity();
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine seat owner API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return null;
        }
    }

    boolean canPlaceFurniture() {
        return this.preferFurnitureHitbox && this.isCraftEngineAvailable();
    }

    boolean seatPlayerOnFurniture(Entity furnitureEntity, Player player) {
        if (furnitureEntity == null || player == null || !player.isOnline() || !this.isCraftEngineAvailable()) {
            return false;
        }
        try {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(furnitureEntity);
            if (furniture == null) {
                return false;
            }
            net.momirealms.craftengine.core.entity.player.Player adaptedPlayer = BukkitAdaptor.adapt(player);
            if (adaptedPlayer == null) {
                return false;
            }
            for (FurnitureHitBox hitbox : furniture.hitboxes()) {
                for (Seat<?> seat : hitbox.seats()) {
                    if (seat.isOccupied()) {
                        continue;
                    }
                    if (seat.spawnSeat(adaptedPlayer, furniture.position())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine seat spawn API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return false;
        }
    }

    void markManagedFurnitureEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        entity.getPersistentDataContainer().set(this.managedFurnitureKey(), PersistentDataType.BYTE, (byte) 1);
    }

    private void applyDisplayClickAction(Entity entity, DisplayClickAction action) {
        if (entity == null) {
            return;
        }
        this.markManagedFurnitureEntity(entity);
        if (action == null) {
            TableDisplayRegistry.unregister(entity.getEntityId());
            return;
        }
        TableDisplayRegistry.register(entity.getEntityId(), action);
    }

    private NamespacedKey managedFurnitureKey() {
        NamespacedKey cached = this.managedFurnitureKey;
        if (cached != null) {
            return cached;
        }
        NamespacedKey resolved = new NamespacedKey(this.context.bukkitPlugin(), MANAGED_FURNITURE_KEY);
        this.managedFurnitureKey = resolved;
        return resolved;
    }

    private void warnUnavailableFurnitureId(String furnitureItemId) {
        if (furnitureItemId == null || furnitureItemId.isBlank()) {
            return;
        }
        if (!this.warnedUnavailableFurnitureIds.add(furnitureItemId)) {
            return;
        }
        this.context.plugin().getLogger().warning(
            "CraftEngine could not place furniture '" + furnitureItemId
                + "'. Ensure the id exists and is defined as furniture, not only as a block or item."
        );
        this.context.plugin().debug().log(
            "lifecycle",
            "CraftEngine returned no furniture instance for id=" + furnitureItemId + ". MahjongPaper will use its fallback render path when available."
        );
    }

    private boolean isCraftEngineAvailable() {
        Plugin craftEngine = this.context.craftEnginePlugin();
        return craftEngine != null && craftEngine.isEnabled();
    }
}
