package top.ellan.mahjong.plugin.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocalizedMessageCatalogTest {
    private final LocalizedMessageCatalog messages =
            LocalizedMessageCatalog.load(getClass().getClassLoader());

    @Test
    void cacheLocaleKeysCollapseArbitraryClientLocalesToBundledFamilies() {
        assertEquals("en_us", messages.localeKey(Locale.forLanguageTag("fr-CA")));
        assertEquals("zh_hk", messages.localeKey(Locale.forLanguageTag("zh-Hant-HK")));
        assertEquals("ja_jp", messages.localeKey(Locale.JAPANESE));
    }

    @Test
    void restoresEveryV15ChineseLocaleAlias() {
        assertEquals(
                "准备",
                messages.resolve(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.action.ready",
                        "ready"));
        assertEquals(
                "準備",
                messages.resolve(
                        Locale.TRADITIONAL_CHINESE,
                        "mahjongpaper.action.ready",
                        "ready"));
        assertEquals(
                "準備",
                messages.resolve(
                        Locale.forLanguageTag("zh-HK"),
                        "mahjongpaper.action.ready",
                        "ready"));
        assertEquals(
                "準備",
                messages.resolve(
                        Locale.forLanguageTag("zh-MO"),
                        "mahjongpaper.action.ready",
                        "ready"));
    }

    @Test
    void formatsCommandArgumentsAfterServerSideLocaleSelection() {
        assertEquals(
                "失败：boom",
                messages.format(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.command.failed",
                        "Failed: %s",
                        List.of("boom")));
    }

    @Test
    void localizesCommandPurposeAndPagedHelpChrome() {
        assertEquals(
                "在你当前位置创建一张新牌桌。",
                messages.resolve(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.command.help.description.create",
                        "missing"));
        assertEquals(
                "[1/2] 17 条可用命令",
                messages.format(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.command.help.page_status",
                        "Page %s/%s - %s commands available",
                        List.of("1", "2", "17")));
        assertEquals(
                "ページ形式のコマンドヘルプを表示します。",
                messages.resolve(
                        Locale.JAPANESE,
                        "mahjongpaper.command.help.description.help",
                        "missing"));
    }

    @Test
    void localizesSemanticTileAndSuitLabelsWithLocaleSizedHitboxes() {
        assertEquals(
                "2万",
                messages.resolve(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.tile.m2",
                        "2m"));
        assertEquals(
                "萬子",
                messages.resolve(
                        Locale.JAPANESE,
                        "mahjongpaper.suit.wan",
                        "Characters"));
        assertTrue(
                messages.actionButtonWidth(
                                Locale.SIMPLIFIED_CHINESE,
                                "action.declare_missing:suit.wan")
                        < messages.actionButtonWidth(
                                Locale.ENGLISH,
                                "action.declare_missing:suit.wan"));
    }

    @Test
    void localizesV15GameRoomCountdownTexts() {
        assertEquals(
                "如不返回，对局将在 30 秒后强制结束。",
                messages.format(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.gameroom.countdown",
                        "Match ends in %s seconds if you do not return.",
                        List.of("30")));
        assertEquals(
                "Match ends in 30 seconds if you do not return.",
                messages.format(
                        Locale.ENGLISH,
                        "mahjongpaper.gameroom.countdown",
                        "Match ends in %s seconds if you do not return.",
                        List.of("30")));
    }

    @Test
    void localizesRuleProfilesAndRuntimeStateValues() {
        assertEquals(
                "立直麻将",
                messages.resolve(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.rule.riichi",
                        "missing"));
        assertEquals(
                "等待出牌",
                messages.resolve(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.value.awaiting_discard",
                        "missing"));
        assertEquals(
                "雀魂ルール",
                messages.resolve(
                        Locale.JAPANESE,
                        "mahjongpaper.profile.mahjong-soul",
                        "missing"));
    }

    /**
     * A locale carrying more placeholders than the caller supplies arguments for throws
     * {@link java.util.MissingFormatArgumentException} at runtime, and one carrying fewer silently
     * drops information. Every locale must therefore agree on the placeholder count for a key.
     */
    @Test
    void everyLocaleAgreesOnPlaceholderCountPerKey() {
        java.util.Map<String, java.util.Map<String, Integer>> counts =
                new java.util.LinkedHashMap<>();
        for (String locale : List.of("en_us", "zh_cn", "zh_tw", "zh_hk", "zh_mo", "ja_jp")) {
            readLocale(locale)
                    .forEach((key, value) -> counts
                            .computeIfAbsent(key, ignored -> new java.util.LinkedHashMap<>())
                            .put(locale, placeholders(value)));
        }
        List<String> disagreements = counts.entrySet().stream()
                .filter(entry -> java.util.Set.copyOf(entry.getValue().values()).size() > 1)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .sorted()
                .toList();
        assertEquals(List.of(), disagreements, "Placeholder counts differ between locales");
    }

    private static int placeholders(String value) {
        int total = 0;
        for (int index = 0; index < value.length() - 1; index++) {
            if (value.charAt(index) != '%') {
                continue;
            }
            if (value.charAt(index + 1) == '%') {
                index++;
            } else {
                total++;
            }
        }
        return total;
    }

    /** Minimal reader for the flat, one-entry-per-line locale files the build ships. */
    private static java.util.Map<String, String> readLocale(String locale) {
        String resource =
                "craftengine/mahjongpaper/resourcepack/assets/mahjongcraft/lang/"
                        + locale
                        + ".json";
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern entry =
                java.util.regex.Pattern.compile("^\\s*\"([^\"]+)\"\\s*:\\s*\"(.*)\",?\\s*$");
        try (java.io.InputStream stream =
                        LocalizedMessageCatalogTest.class
                                .getClassLoader()
                                .getResourceAsStream(resource);
                java.io.BufferedReader reader =
                        new java.io.BufferedReader(
                                new java.io.InputStreamReader(
                                        java.util.Objects.requireNonNull(stream, resource),
                                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher matcher = entry.matcher(line);
                if (matcher.matches()) {
                    entries.put(matcher.group(1), matcher.group(2));
                }
            }
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        assertTrue(entries.size() > 300, resource + " looks truncated");
        return entries;
    }
}
