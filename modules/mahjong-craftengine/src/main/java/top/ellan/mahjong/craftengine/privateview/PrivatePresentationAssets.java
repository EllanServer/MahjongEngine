package top.ellan.mahjong.craftengine.privateview;

import java.util.Locale;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.presentation.asset.TileAssetName;

/** Stateless mapping from semantic private nodes to CE assets and translated label JSON. */
final class PrivatePresentationAssets {
    private PrivatePresentationAssets() {}

    static String itemAsset(PrivateItemNode item) {
        return "mahjongpaper:" + TileAssetName.from(item.visualId());
    }

    static String labelJson(
            ActionLabelNode label, Locale locale, PlayerTextResolver messages) {
        String color = label.emphasized() ? "gold" : "aqua";
        LabelText text = labelText(label.labelKey());
        String localized = messages.resolve(locale, text.translationKey(), text.fallback());
        return "{\"text\":\"[\",\"color\":\""
                + color
                + "\",\"extra\":[{\"text\":\""
                + escape(localized)
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
                : ",{\"text\":\" " + escape(suffix) + "\",\"color\":\"gray\"}";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record LabelText(
            String translationKey,
            String fallback,
            String suffixJson) {}
}
