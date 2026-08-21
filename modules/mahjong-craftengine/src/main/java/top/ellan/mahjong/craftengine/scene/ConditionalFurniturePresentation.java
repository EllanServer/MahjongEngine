package top.ellan.mahjong.craftengine.scene;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import top.ellan.mahjong.presentation.asset.TileAssetName;
import top.ellan.mahjong.presentation.node.ActionFurnitureNode;
import top.ellan.mahjong.presentation.node.PrivateFurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.spi.PlayerId;

final class ConditionalFurniturePresentation {
    private ConditionalFurniturePresentation() {}

    static String asset(SceneNode node) {
        if (node instanceof PrivateFurnitureNode tile) {
            return "mahjongpaper:tile_private_" + TileAssetName.from(tile.visualId());
        }
        return ((ActionFurnitureNode) node).assetId();
    }

    static SceneTransform transform(SceneNode node) {
        return node instanceof PrivateFurnitureNode tile
                ? tile.transform()
                : ((ActionFurnitureNode) node).transform();
    }

    static PlayerId singleViewer(SceneNode node) {
        return node.visibility()
                .singleViewer()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Conditional furniture requires exactly one viewer"));
    }

    static void setVariant(BukkitFurniture furniture, String variant) {
        if (furniture.currentVariant().name().equals(variant)) {
            return;
        }
        boolean changed = furniture.setVariant(variant, true);
        if (!changed && !furniture.currentVariant().name().equals(variant)) {
            throw new IllegalStateException(
                    "CraftEngine refused conditional furniture variant " + variant);
        }
    }
}
