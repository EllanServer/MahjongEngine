package top.ellan.mahjong.render.display;

import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.runtime.ServerScheduler;
import java.util.Collection;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public interface DisplayEntityRuntime {
    Plugin bukkitPlugin();

    default ServerScheduler scheduler() {
        return null;
    }

    default Collection<? extends Player> onlinePlayers() {
        return Bukkit.getOnlinePlayers();
    }

    default Supplier<CraftEngineService> craftEngineSupplier() {
        return null;
    }

    default CraftEngineService craftEngine() {
        Supplier<CraftEngineService> supplier = this.craftEngineSupplier();
        return supplier == null ? null : supplier.get();
    }

    default void teleport(Entity entity, Location location) {
        ServerScheduler scheduler = this.scheduler();
        if (scheduler != null) {
            scheduler.teleport(entity, location);
        } else {
            entity.teleport(location);
        }
    }

    default void runForViewer(Player player, Runnable runnable) {
        ServerScheduler scheduler = this.scheduler();
        if (scheduler != null) {
            scheduler.runEntity(player, runnable);
        } else {
            runnable.run();
        }
    }

    default void registerCullableEntity(Entity entity) {
        CraftEngineService craftEngine = this.craftEngine();
        if (craftEngine != null) {
            craftEngine.registerCullableEntity(entity);
        }
    }

    default boolean requiresVisibilityResync() {
        // CraftEngine's culling callbacks consult DisplayVisibilityRegistry before
        // every show/hide transition, so merely having the compatibility service
        // installed does not invalidate an unchanged viewer set. Implementations
        // may still override this for an explicit lifecycle resync.
        return false;
    }

    default ItemStack resolveTileItem(MahjongVariant variant, MahjongTile tile, boolean faceDown) {
        CraftEngineService craftEngine = this.craftEngine();
        return craftEngine == null ? null : craftEngine.resolveTileItem(variant == null ? MahjongVariant.RIICHI : variant, tile, faceDown);
    }

    default Entity placeFurniture(Location location, String furnitureItemId, DisplayClickAction clickAction) {
        CraftEngineService craftEngine = this.craftEngine();
        return craftEngine == null ? null : craftEngine.placeFurniture(location, furnitureItemId, clickAction);
    }

    default boolean reconcileFurniture(Entity entity, Location location, String furnitureItemId, DisplayClickAction clickAction) {
        CraftEngineService craftEngine = this.craftEngine();
        return craftEngine != null && craftEngine.reconcileFurniture(entity, location, furnitureItemId, clickAction);
    }
}
