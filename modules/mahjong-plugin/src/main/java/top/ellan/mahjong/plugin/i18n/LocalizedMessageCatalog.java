package top.ellan.mahjong.plugin.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.presentation.label.ActionLabelPolicy;
import top.ellan.mahjong.presentation.label.ActionLabelText;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.registry.MiniJson;

/** Immutable server-side view of the same locale files shipped to CraftEngine clients. */
public final class LocalizedMessageCatalog implements PlayerTextResolver {
    private static final int ACTION_WIDTH_CACHE_SIZE = 256;
    private static final String RESOURCE_ROOT =
            "craftengine/mahjongpaper/resourcepack/assets/mahjongcraft/lang/";
    private static final List<String> LOCALES =
            List.of("en_us", "zh_cn", "zh_tw", "zh_hk", "zh_mo", "ja_jp");
    private final Map<String, Map<String, String>> translations;
    private final AtomicReferenceArray<ActionWidthEntry> actionWidths =
            new AtomicReferenceArray<>(ACTION_WIDTH_CACHE_SIZE);

    private LocalizedMessageCatalog(Map<String, Map<String, String>> translations) {
        this.translations = Map.copyOf(translations);
    }

    public static LocalizedMessageCatalog load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        LinkedHashMap<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            loaded.put(locale, readLocale(classLoader, locale));
        }
        Map<String, String> english = loaded.get("en_us");
        for (Map.Entry<String, Map<String, String>> entry : loaded.entrySet()) {
            if (!entry.getValue().keySet().equals(english.keySet())) {
                throw new IllegalStateException(
                        "Locale key set differs from en_us: " + entry.getKey());
            }
        }
        return new LocalizedMessageCatalog(loaded);
    }

    @Override
    public String resolve(Locale locale, String translationKey, String fallback) {
        Objects.requireNonNull(translationKey, "translationKey");
        Objects.requireNonNull(fallback, "fallback");
        Map<String, String> selected = translations.get(localeId(locale));
        String translated = selected == null ? null : selected.get(translationKey);
        if (translated != null) {
            return translated;
        }
        return translations.get("en_us").getOrDefault(translationKey, fallback);
    }

    public String format(
            Locale locale, String translationKey, String fallback, List<String> arguments) {
        String pattern = resolve(locale, translationKey, fallback);
        return String.format(
                locale == null ? Locale.ENGLISH : locale,
                pattern,
                List.copyOf(arguments).toArray());
    }

    /** Returns the v1.5-compatible button width for a semantic label in one player's locale. */
    public double actionButtonWidth(Locale locale, String labelKey) {
        Objects.requireNonNull(labelKey, "labelKey");
        String localeId = localeId(locale);
        String cacheKey = localeId + '\0' + labelKey;
        int slot = cacheKey.hashCode() & (ACTION_WIDTH_CACHE_SIZE - 1);
        ActionWidthEntry cached = actionWidths.get(slot);
        if (cached != null && cached.key().equals(cacheKey)) {
            return cached.width();
        }
        Map<String, String> selected = translations.get(localeId);
        Map<String, String> english = translations.get("en_us");
        String label = ActionLabelText.resolve(
                labelKey,
                (translationKey, fallback) -> {
                    String translated = selected == null ? null : selected.get(translationKey);
                    return translated == null
                            ? english.getOrDefault(translationKey, fallback)
                            : translated;
                });
        double width = ActionLabelPolicy.buttonWidth(label);
        actionWidths.set(slot, new ActionWidthEntry(cacheKey, width));
        return width;
    }

    static String localeId(Locale locale) {
        if (locale == null) {
            return "en_us";
        }
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        if ("zh".equals(language)) {
            return switch (country) {
                case "TW" -> "zh_tw";
                case "HK" -> "zh_hk";
                case "MO" -> "zh_mo";
                default -> "zh_cn";
            };
        }
        return "ja".equals(language) ? "ja_jp" : "en_us";
    }

    private static Map<String, String> readLocale(ClassLoader classLoader, String locale) {
        String resource = RESOURCE_ROOT + locale + ".json";
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled locale " + resource);
            }
            Object parsed = MiniJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> object)) {
                throw new IllegalStateException("Locale is not a JSON object: " + resource);
            }
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || !(entry.getValue() instanceof String value)) {
                    throw new IllegalStateException("Locale must contain only strings: " + resource);
                }
                values.put(key, value);
            }
            return Map.copyOf(values);
        } catch (IOException | RulePackException failure) {
            throw new IllegalStateException("Cannot load bundled locale " + resource, failure);
        }
    }

    private record ActionWidthEntry(String key, double width) {}
}
