package top.ellan.mahjong.craftengine.privateview;

import java.util.Locale;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.presentation.asset.TileAssetName;
import top.ellan.mahjong.presentation.label.ActionLabelText;

/** Stateless mapping from semantic private nodes to CE assets and translated label JSON. */
final class PrivatePresentationAssets {
    private PrivatePresentationAssets() {}

    static String itemAsset(PrivateItemNode item) {
        return "mahjongpaper:" + TileAssetName.from(item.visualId());
    }

    static String labelJson(
            ActionLabelNode label, Locale locale, PlayerTextResolver messages) {
        String color = label.emphasized() ? "gold" : "aqua";
        String localized = ActionLabelText.resolve(
                label.labelKey(),
                (key, fallback) -> messages.resolve(locale, key, fallback));
        return "{\"text\":\"[\",\"color\":\""
                + color
                + "\",\"extra\":[{\"text\":\""
                + escape(localized)
                + "\"},{\"text\":\"]\"}]}";
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
}
