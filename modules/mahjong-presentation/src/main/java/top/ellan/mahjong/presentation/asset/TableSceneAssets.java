package top.ellan.mahjong.presentation.asset;

import java.util.Objects;
import java.util.regex.Pattern;

/** Restart-scoped CraftEngine asset references; geometry remains in CraftEngine configuration. */
public record TableSceneAssets(
        String tableFurniture,
        String seatFurniture,
        String standingBackFurniture,
        String flatBackFurniture,
        String handInteractionFurniture,
        String actionInteractionFurniture) {
    public TableSceneAssets {
        tableFurniture = requireAsset(tableFurniture, "tableFurniture");
        seatFurniture = requireAsset(seatFurniture, "seatFurniture");
        standingBackFurniture = requireAsset(standingBackFurniture, "standingBackFurniture");
        flatBackFurniture = requireAsset(flatBackFurniture, "flatBackFurniture");
        handInteractionFurniture = requireAsset(handInteractionFurniture, "handInteractionFurniture");
        actionInteractionFurniture = requireAsset(actionInteractionFurniture, "actionInteractionFurniture");
    }

    private static final Pattern ASSET = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static String requireAsset(String value, String name) {
        String asset = Objects.requireNonNull(value, name);
        if (!ASSET.matcher(asset).matches()) {
            throw new IllegalArgumentException("Invalid CraftEngine asset: " + asset);
        }
        return asset;
    }
}
