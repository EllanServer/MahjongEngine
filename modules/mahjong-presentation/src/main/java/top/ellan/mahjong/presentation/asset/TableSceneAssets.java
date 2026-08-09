package top.ellan.mahjong.presentation.asset;

import java.util.Objects;

/** Restart-scoped CraftEngine asset references; geometry remains in CraftEngine configuration. */
public record TableSceneAssets(
        String tableFurniture,
        String standingBackFurniture,
        String flatBackFurniture,
        String handInteractionFurniture,
        String actionInteractionFurniture) {
    public TableSceneAssets {
        tableFurniture = requireAsset(tableFurniture, "tableFurniture");
        standingBackFurniture = requireAsset(standingBackFurniture, "standingBackFurniture");
        flatBackFurniture = requireAsset(flatBackFurniture, "flatBackFurniture");
        handInteractionFurniture = requireAsset(handInteractionFurniture, "handInteractionFurniture");
        actionInteractionFurniture = requireAsset(actionInteractionFurniture, "actionInteractionFurniture");
    }

    private static String requireAsset(String value, String name) {
        String asset = Objects.requireNonNull(value, name);
        if (!asset.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid CraftEngine asset: " + asset);
        }
        return asset;
    }
}
