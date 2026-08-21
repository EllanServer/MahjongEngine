package top.ellan.mahjong.plugin.i18n;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.message.MiniMessage;
import net.momirealms.sparrow.message.tag.resolver.TagResolver;

/** Shared immutable Sparrow MiniMessage renderer for trusted server-side UI templates. */
public final class SparrowMessageRenderer {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private SparrowMessageRenderer() {}

    /**
     * Renders a trusted template. Dynamic text must be supplied through an unparsed or component
     * placeholder so player-controlled values cannot inject tags.
     */
    public static Component render(String template, TagResolver... placeholders) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(placeholders, "placeholders");
        return placeholders.length == 0
                ? MINI_MESSAGE.deserialize(template)
                : MINI_MESSAGE.deserialize(template, placeholders);
    }
}
