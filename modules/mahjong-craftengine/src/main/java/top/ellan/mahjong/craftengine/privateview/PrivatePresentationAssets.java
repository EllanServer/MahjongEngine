package top.ellan.mahjong.craftengine.privateview;

import top.ellan.mahjong.presentation.ActionLabelNode;
import top.ellan.mahjong.presentation.PrivateItemNode;
import top.ellan.mahjong.presentation.TileAssetName;

/** Stateless mapping from semantic private nodes to CE assets and translated label JSON. */
final class PrivatePresentationAssets {
    private PrivatePresentationAssets() {}

    static String itemAsset(PrivateItemNode item) {
        return "mahjongpaper:" + TileAssetName.from(item.visualId());
    }

    static String labelJson(ActionLabelNode label) {
        String color = label.emphasized() ? "gold" : "aqua";
        LabelText text = labelText(label.labelKey());
        return "{\"text\":\"[\",\"color\":\""
                + color
                + "\",\"extra\":[{\"translate\":\""
                + text.translationKey()
                + "\",\"fallback\":\""
                + text.fallback()
                + "\"}"
                + text.suffixJson()
                + ",{\"text\":\"]\"}]}";
    }

    private static LabelText labelText(String labelKey) {
        int separator = labelKey.indexOf(':');
        if (separator < 0) {
            return new LabelText("mahjongpaper." + labelKey, labelKey, "");
        }
        String base = labelKey.substring(0, separator);
        String suffix = labelKey.substring(separator + 1);
        if (base.equals("action.respond")) {
            int nextSeparator = suffix.indexOf(':');
            String reaction = nextSeparator < 0 ? suffix : suffix.substring(0, nextSeparator);
            String choices = nextSeparator < 0 ? "" : suffix.substring(nextSeparator + 1);
            return new LabelText(
                    "mahjongpaper.action." + reaction,
                    reaction,
                    literalSuffix(choices));
        }
        return new LabelText(
                "mahjongpaper." + base,
                base,
                literalSuffix(suffix));
    }

    private static String literalSuffix(String suffix) {
        return suffix.isEmpty()
                ? ""
                : ",{\"text\":\" " + suffix + "\",\"color\":\"gray\"}";
    }

    private record LabelText(
            String translationKey,
            String fallback,
            String suffixJson) {}
}
