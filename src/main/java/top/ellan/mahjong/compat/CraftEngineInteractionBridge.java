package top.ellan.mahjong.compat;

import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.TableOverheadViews;

final class CraftEngineInteractionBridge implements Listener {
    private static final double FURNITURE_INTERACTION_EPSILON = 0.05D;
    private final CraftEngineBridgeContext context;
    private final CraftEngineFurnitureBridge furnitureBridge;
    private MahjongTableManager tableManager;
    private boolean registered;

    CraftEngineInteractionBridge(CraftEngineBridgeContext context, CraftEngineFurnitureBridge furnitureBridge) {
        this.context = context;
        this.furnitureBridge = furnitureBridge;
    }

    void enableFurnitureInteractionBridge(MahjongTableManager tableManager) {
        if (this.registered) {
            return;
        }
        Plugin craftEngine = this.context.craftEnginePlugin();
        if (craftEngine == null || !craftEngine.isEnabled()) {
            this.context.plugin().getLogger().warning("CraftEngine interaction bridge is unavailable because CraftEngine is not enabled.");
            return;
        }
        this.tableManager = tableManager;
        this.context.plugin().getServer().getPluginManager().registerEvents(this, this.context.bukkitPlugin());
        this.registered = true;
    }

    void disableFurnitureInteractionBridge() {
        if (!this.registered) {
            return;
        }
        HandlerList.unregisterAll(this);
        this.registered = false;
        this.tableManager = null;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        MahjongTableManager manager = this.tableManager;
        if (manager == null) {
            return;
        }
        BukkitFurniture furniture = event.furniture();
        if (furniture == null) {
            return;
        }
        this.handleFurnitureInteraction(
            manager,
            event.player(),
            furniture.bukkitEntity(),
            furniture.entityId(),
            event.interactionPoint(),
            event
        );
    }

    void handleFurnitureInteraction(
        MahjongTableManager manager,
        Player player,
        Entity furnitureEntity,
        int furnitureEntityId,
        Location interactionPoint,
        Cancellable event
    ) {
        if (manager == null || player == null || event == null) {
            return;
        }
        DisplayClickAction action = TableDisplayRegistry.get(furnitureEntityId);
        boolean rayResolved = false;
        if (action == null) {
            TableOverheadViews overheadViews = manager.overheadViews();
            if (!this.furnitureBridge.isManagedFurnitureEntity(furnitureEntity)
                || overheadViews != null && overheadViews.isActive(player.getUniqueId())) {
                return;
            }
            Location eye = player.getEyeLocation();
            if (interactionPoint == null
                || eye.getWorld() == null
                || !eye.getWorld().equals(interactionPoint.getWorld())) {
                return;
            }
            double maxDistance = interactionPoint.distance(eye) + FURNITURE_INTERACTION_EPSILON;
            action = DisplayInteractionRayRegistry.resolve(player, maxDistance);
            if (action == null) {
                return;
            }
            rayResolved = true;
        }
        event.setCancelled(true);
        boolean accepted = manager.handleDisplayAction(player, action);
        if (!accepted) {
            if (action.actionType() == DisplayClickAction.ActionType.HAND_TILE) {
                this.context.plugin().messages().actionBar(player, "packet.cannot_click_tile");
            } else {
                this.context.plugin().messages().actionBar(player, "command.join_failed");
            }
        } else if (rayResolved) {
            player.swingMainHand();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        if (this.isManagedFurniture(event.furniture())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureHit(FurnitureHitEvent event) {
        if (this.isManagedFurniture(event.furniture())) {
            event.setCancelled(true);
        }
    }

    private boolean isManagedFurniture(BukkitFurniture furniture) {
        if (furniture == null) {
            return false;
        }
        Entity entity = furniture.bukkitEntity();
        return entity != null && this.furnitureBridge.isManagedFurnitureEntity(entity);
    }
}
