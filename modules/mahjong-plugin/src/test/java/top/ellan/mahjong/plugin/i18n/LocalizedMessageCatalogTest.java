package top.ellan.mahjong.plugin.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocalizedMessageCatalogTest {
    private final LocalizedMessageCatalog messages =
            LocalizedMessageCatalog.load(getClass().getClassLoader());

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
}
