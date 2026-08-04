package top.ellan.mahjong.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class LocalizedMessages {
    public static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("zh-CN");
    private static final String MESSAGE_INDEX_RESOURCE = "i18n/_index.json";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainTextSerializer = PlainTextComponentSerializer.plainText();
    private final MessageBundleIndex index = MessageBundleIndex.load();
    private final Map<Locale, Map<String, String>> bundles = new ConcurrentHashMap<>();
    private final Map<Locale, List<Locale>> localeChains = new ConcurrentHashMap<>();
    private final Map<MessageCacheKey, String> templates = new ConcurrentHashMap<>();
    private final Map<MessageCacheKey, Component> staticComponents = new ConcurrentHashMap<>();
    private final Map<MessageCacheKey, String> staticPlainTexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Locale, ThreadLocal<NumberFormat>> integerFormats = new ConcurrentHashMap<>();

    public Component render(Locale locale, String key, TagResolver... placeholders) {
        if (placeholders.length == 0) {
            return this.staticComponents.computeIfAbsent(this.cacheKey(locale, key), this::renderStaticComponent);
        }
        String template = this.template(locale, key);
        return this.miniMessage.deserialize(template, TagResolver.resolver(placeholders));
    }

    public String plain(Locale locale, String key, TagResolver... placeholders) {
        if (placeholders.length == 0) {
            return this.staticPlainTexts.computeIfAbsent(this.cacheKey(locale, key), this::renderStaticPlain);
        }
        return this.plainTextSerializer.serialize(this.render(locale, key, placeholders));
    }

    /**
     * Renders a message with placeholders supplied as a plain key/value map.
     *
     * <p>This overload is behaviourally identical to the {@link TagResolver} variant
     * (placeholders are resolved by tag name, so map iteration order is irrelevant). It
     * exists so hot rendering paths can be measured and cached through a single,
     * stable placeholder representation.
     */
    public Component render(Locale locale, String key, Map<String, String> placeholders) {
        return this.render(locale, key, resolvers(placeholders));
    }

    /**
     * Renders a message to plain text with placeholders supplied as a key/value map.
     *
     * <p>See {@link #render(Locale, String, Map)} for the contract shared with the
     * {@link TagResolver} variant.
     */
    public String plain(Locale locale, String key, Map<String, String> placeholders) {
        return this.plain(locale, key, resolvers(placeholders));
    }

    /**
     * Formats a number using the locale-aware integer format used by {@link #number}.
     */
    public String formatNumber(Locale locale, String key, Number value) {
        Locale formatLocale = this.resolveLocales(locale).get(0);
        NumberFormat format = this.integerFormats.computeIfAbsent(
            formatLocale,
            resolvedLocale -> ThreadLocal.withInitial(() -> NumberFormat.getIntegerInstance(resolvedLocale))
        ).get();
        return format.format(value);
    }

    public boolean contains(Locale locale, String key) {
        for (Locale candidate : this.resolveLocales(locale)) {
            if (this.bundle(candidate).containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    public TagResolver tag(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    public TagResolver number(Locale locale, String key, Number value) {
        return Placeholder.unparsed(key, this.formatNumber(locale, key, value));
    }

    private static TagResolver[] resolvers(Map<String, String> placeholders) {
        TagResolver[] resolvers = new TagResolver[placeholders.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolvers[index++] = Placeholder.unparsed(entry.getKey(), entry.getValue());
        }
        return resolvers;
    }

    public Locale normalizeLocale(String rawLocale) {
        if (rawLocale == null || rawLocale.isBlank()) {
            return this.index.defaultLocale();
        }
        Locale locale = Locale.forLanguageTag(rawLocale.replace('_', '-'));
        return locale.getLanguage().isBlank() ? this.index.defaultLocale() : this.resolveLocales(locale).get(0);
    }

    private List<Locale> resolveLocales(Locale locale) {
        Locale safeLocale = this.safeLocale(locale);
        return this.localeChains.computeIfAbsent(safeLocale, this.index::resolveChain);
    }

    private String template(Locale locale, String key) {
        return this.templates.computeIfAbsent(this.cacheKey(locale, key), this::resolveTemplate);
    }

    private String resolveTemplate(MessageCacheKey cacheKey) {
        for (Locale candidate : this.resolveLocales(cacheKey.locale())) {
            String template = this.bundle(candidate).get(cacheKey.key());
            if (template != null) {
                return template;
            }
        }
        throw new IllegalArgumentException("Missing message key: " + cacheKey.key());
    }

    private Component renderStaticComponent(MessageCacheKey cacheKey) {
        return this.miniMessage.deserialize(this.template(cacheKey.locale(), cacheKey.key()));
    }

    private String renderStaticPlain(MessageCacheKey cacheKey) {
        return this.plainTextSerializer.serialize(this.renderStaticComponent(cacheKey));
    }

    private MessageCacheKey cacheKey(Locale locale, String key) {
        return new MessageCacheKey(this.safeLocale(locale), key);
    }

    private Locale safeLocale(Locale locale) {
        return locale == null ? this.index.defaultLocale() : locale;
    }

    private Map<String, String> bundle(Locale locale) {
        Locale safeLocale = locale == null ? this.index.defaultLocale() : locale;
        return this.bundles.computeIfAbsent(safeLocale, this::loadBundle);
    }

    private Map<String, String> loadBundle(Locale locale) {
        String resourceName = this.index.resourceName(locale);
        if (resourceName == null) {
            return Map.of();
        }

        Properties properties = new Properties();
        try (
            InputStream inputStream = LocalizedMessages.class.getClassLoader().getResourceAsStream(resourceName);
            Reader reader = inputStream == null ? null : new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        ) {
            if (reader == null) {
                throw new IllegalStateException("Missing message bundle resource: " + resourceName);
            }
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load message bundle: " + resourceName, exception);
        }

        Map<String, String> loaded = new LinkedHashMap<>();
        properties.forEach((key, value) -> loaded.put(String.valueOf(key), String.valueOf(value)));
        return Map.copyOf(loaded);
    }

    private static final class MessageBundleIndex {
        private static final Pattern DEFAULT_LOCALE_PATTERN = Pattern.compile("\"defaultLocale\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern BUNDLE_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+\\.properties)\"");
        private static final MessageBundleIndex FALLBACK = new MessageBundleIndex(
            DEFAULT_LOCALE,
            Map.of(
                "en", "language/messages.properties",
                "ja-JP", "language/messages_ja_JP.properties",
                DEFAULT_LOCALE.toLanguageTag(), "language/messages_zh_CN.properties",
                "zh-TW", "language/messages_zh_TW.properties",
                "zh-HK", "language/messages_zh_HK.properties",
                "zh-MO", "language/messages_zh_MO.properties"
            )
        );

        private final Locale defaultLocale;
        private final Map<String, String> bundles;

        private MessageBundleIndex(Locale defaultLocale, Map<String, String> bundles) {
            this.defaultLocale = defaultLocale;
            this.bundles = Map.copyOf(bundles);
        }

        static MessageBundleIndex load() {
            try (InputStream inputStream = LocalizedMessages.class.getClassLoader().getResourceAsStream(MESSAGE_INDEX_RESOURCE)) {
                if (inputStream == null) {
                    return FALLBACK;
                }

                String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                Matcher defaultLocaleMatcher = DEFAULT_LOCALE_PATTERN.matcher(json);
                Locale defaultLocale = defaultLocaleMatcher.find()
                    ? Locale.forLanguageTag(defaultLocaleMatcher.group(1))
                    : DEFAULT_LOCALE;

                Map<String, String> bundles = new LinkedHashMap<>();
                Matcher bundleMatcher = BUNDLE_PATTERN.matcher(json);
                while (bundleMatcher.find()) {
                    bundles.put(bundleMatcher.group(1), bundleMatcher.group(2));
                }
                if (bundles.isEmpty()) {
                    return FALLBACK;
                }
                return new MessageBundleIndex(defaultLocale, bundles);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to load " + MESSAGE_INDEX_RESOURCE, exception);
            }
        }

        Locale defaultLocale() {
            return this.defaultLocale;
        }

        List<Locale> resolveChain(Locale requestedLocale) {
            Locale safeLocale = requestedLocale == null ? this.defaultLocale : requestedLocale;
            LinkedHashSet<String> candidateTags = new LinkedHashSet<>();
            this.collectExactCandidate(candidateTags, safeLocale);
            this.collectChineseCandidates(candidateTags, safeLocale);
            this.collectLanguageCandidates(candidateTags, safeLocale);
            candidateTags.add(this.defaultLocale.toLanguageTag());
            candidateTags.add(Locale.ENGLISH.toLanguageTag());

            List<Locale> resolved = new ArrayList<>(candidateTags.size());
            for (String candidateTag : candidateTags) {
                if (this.bundles.containsKey(candidateTag)) {
                    resolved.add(Locale.forLanguageTag(candidateTag));
                }
            }
            if (resolved.isEmpty()) {
                resolved.add(this.defaultLocale);
            }
            return List.copyOf(resolved);
        }

        private void collectExactCandidate(Set<String> candidateTags, Locale locale) {
            String exactTag = locale.toLanguageTag();
            if (this.bundles.containsKey(exactTag)) {
                candidateTags.add(exactTag);
            }
        }

        private void collectLanguageCandidates(Set<String> candidateTags, Locale locale) {
            String language = safeLanguage(locale);
            if (!language.isBlank()) {
                if (this.bundles.containsKey(language)) {
                    candidateTags.add(language);
                }
                for (String supportedLocale : this.bundles.keySet()) {
                    if (supportedLocale.startsWith(language + "-")) {
                        candidateTags.add(supportedLocale);
                    }
                }
            }
        }

        private void collectChineseCandidates(Set<String> candidateTags, Locale locale) {
            if (!"zh".equalsIgnoreCase(safeLanguage(locale))) {
                return;
            }

            String region = locale.getCountry().toUpperCase(Locale.ROOT);
            String script = locale.getScript();
            boolean traditional = "Hant".equalsIgnoreCase(script) || region.equals("TW") || region.equals("HK") || region.equals("MO");
            boolean simplified = "Hans".equalsIgnoreCase(script) || region.equals("CN") || region.equals("SG") || region.equals("MY");

            if (region.equals("HK") && this.bundles.containsKey("zh-HK")) {
                candidateTags.add("zh-HK");
            }
            if (region.equals("MO") && this.bundles.containsKey("zh-MO")) {
                candidateTags.add("zh-MO");
            }
            if (traditional || (!simplified && !region.isBlank())) {
                if (this.bundles.containsKey("zh-TW")) {
                    candidateTags.add("zh-TW");
                }
            }
            if (simplified && this.bundles.containsKey("zh-CN")) {
                candidateTags.add("zh-CN");
            }
            if (!traditional && !simplified && this.bundles.containsKey("zh-CN")) {
                candidateTags.add("zh-CN");
            }
        }

        String resourceName(Locale locale) {
            return this.bundles.get(locale.toLanguageTag());
        }

        private String safeLanguage(Locale locale) {
            return locale.getLanguage() == null ? "" : locale.getLanguage();
        }
    }

    private record MessageCacheKey(Locale locale, String key) {
        private MessageCacheKey {
            locale = Objects.requireNonNull(locale, "locale");
            key = Objects.requireNonNull(key, "key");
        }
    }
}
