package top.ellan.mahjong.craftengine;

import org.bukkit.entity.Entity;

/** CraftEngine Cullable registration boundary. */
public interface CullingRegistration {
    void register(Entity entity);

    void unregister(Entity entity);
}
