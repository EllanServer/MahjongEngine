package top.ellan.mahjong.i18n;

import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MessageService {
    private final LocalizedMessages messages = new LocalizedMessages();

    public Component render(CommandSender sender, String key, TagResolver... placeholders) {
        return this.render(this.resolveLocale(sender), key, placeholders);
    }

    public Component render(Locale locale, String key, TagResolver... placeholders) {
        return this.messages.render(locale, key, placeholders);
    }

    public void send(CommandSender sender, String key, TagResolver... placeholders) {
        sender.sendMessage(this.render(sender, key, placeholders));
    }

    public void actionBar(Player player, String key, TagResolver... placeholders) {
        player.sendActionBar(this.render(player, key, placeholders));
    }

    public String plain(Locale locale, String key, TagResolver... placeholders) {
        return this.messages.plain(locale, key, placeholders);
    }

    public Component render(Locale locale, String key, Map<String, String> placeholders) {
        return this.messages.render(locale, key, placeholders);
    }

    public String plain(Locale locale, String key, Map<String, String> placeholders) {
        return this.messages.plain(locale, key, placeholders);
    }

    public String formatNumber(Locale locale, String key, Number value) {
        return this.messages.formatNumber(locale, key, value);
    }

    public boolean contains(Locale locale, String key) {
        return this.messages.contains(locale, key);
    }

    public Locale resolveLocale(CommandSender sender) {
        if (sender instanceof Player player) {
            return this.messages.normalizeLocale(player.locale().toLanguageTag());
        }
        return LocalizedMessages.DEFAULT_LOCALE;
    }

    public TagResolver tag(String key, String value) {
        return this.messages.tag(key, value);
    }

    public TagResolver number(Locale locale, String key, Number value) {
        return this.messages.number(locale, key, value);
    }
}
