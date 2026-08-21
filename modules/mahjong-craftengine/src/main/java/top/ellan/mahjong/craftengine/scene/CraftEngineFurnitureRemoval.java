package top.ellan.mahjong.craftengine.scene;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import java.util.Objects;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

final class CraftEngineFurnitureRemoval {
    private CraftEngineFurnitureRemoval() {}

    static void runOnOwner(Plugin plugin, BukkitFurniture furniture, Runnable action) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(furniture, "furniture");
        Objects.requireNonNull(action, "action");
        Entity entity = furniture.bukkitEntity();
        if (entity == null) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            action.run();
            return;
        }
        entity.getScheduler().run(plugin, ignored -> action.run(), null);
    }

    /** Removes through CE when possible; returns true only when a defensive fallback was needed. */
    static boolean remove(BukkitFurniture furniture) {
        Entity entity = furniture.bukkitEntity();
        if (entity == null || !entity.isValid()) {
            return false;
        }
        if (CraftEngineFurniture.isFurniture(entity)
                && CraftEngineFurniture.remove(entity, false, false)) {
            return false;
        }
        if (furniture.isValid()) {
            furniture.destroy();
        } else {
            entity.remove();
        }
        return true;
    }
}
