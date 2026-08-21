package top.ellan.mahjong.craftengine.scene;

import java.util.Objects;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import top.ellan.mahjong.presentation.node.SceneNode;

record ManagedConditionalFurniture(
        BukkitFurniture furniture, String assetId, long epoch, SceneNode node) {
    ManagedConditionalFurniture {
        Objects.requireNonNull(furniture, "furniture");
        Objects.requireNonNull(assetId, "assetId");
    }
}
