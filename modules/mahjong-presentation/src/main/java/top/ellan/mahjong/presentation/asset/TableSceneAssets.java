package top.ellan.mahjong.presentation.asset;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import top.ellan.mahjong.presentation.label.ActionLabelPolicy;

/** Restart-scoped CraftEngine asset references; geometry remains in CraftEngine configuration. */
public record TableSceneAssets(
        String tableFurniture,
        String seatFurniture,
        String standingBackFurniture,
        String flatBackFurniture,
        String handInteractionFurniture,
        String actionInteractionFurniture,
        List<String> actionInteractionFurnitureVariants) {
    public TableSceneAssets(
            String tableFurniture,
            String seatFurniture,
            String standingBackFurniture,
            String flatBackFurniture,
            String handInteractionFurniture,
            String actionInteractionFurniture) {
        this(
                tableFurniture,
                seatFurniture,
                standingBackFurniture,
                flatBackFurniture,
                handInteractionFurniture,
                actionInteractionFurniture,
                Collections.nCopies(
                        ActionLabelPolicy.variantCount(), actionInteractionFurniture));
    }

    public TableSceneAssets {
        tableFurniture = requireAsset(tableFurniture, "tableFurniture");
        seatFurniture = requireAsset(seatFurniture, "seatFurniture");
        standingBackFurniture = requireAsset(standingBackFurniture, "standingBackFurniture");
        flatBackFurniture = requireAsset(flatBackFurniture, "flatBackFurniture");
        handInteractionFurniture = requireAsset(handInteractionFurniture, "handInteractionFurniture");
        actionInteractionFurniture = requireAsset(actionInteractionFurniture, "actionInteractionFurniture");
        actionInteractionFurnitureVariants = List.copyOf(
                Objects.requireNonNull(
                        actionInteractionFurnitureVariants,
                        "actionInteractionFurnitureVariants"));
        if (actionInteractionFurnitureVariants.size() != ActionLabelPolicy.variantCount()) {
            throw new IllegalArgumentException(
                    "Action hitbox asset count must match the supported width variants");
        }
        actionInteractionFurnitureVariants = actionInteractionFurnitureVariants.stream()
                .map(asset -> requireAsset(asset, "actionInteractionFurnitureVariant"))
                .toList();
    }

    public String actionInteractionFurniture(double width) {
        return actionInteractionFurnitureVariants.get(ActionLabelPolicy.variantIndex(width));
    }

    public static List<String> actionInteractionVariants(String assetPrefix) {
        String prefix = requireAsset(assetPrefix, "actionInteractionFurniturePrefix");
        java.util.ArrayList<String> assets =
                new java.util.ArrayList<>(ActionLabelPolicy.variantCount());
        for (int index = 0; index < ActionLabelPolicy.variantCount(); index++) {
            assets.add(prefix + ActionLabelPolicy.variantSuffix(index));
        }
        return List.copyOf(assets);
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
