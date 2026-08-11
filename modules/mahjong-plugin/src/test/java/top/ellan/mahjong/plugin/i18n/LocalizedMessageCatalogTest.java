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

    @Test
    void localizesCommandPurposeAndPagedHelpChrome() {
        assertEquals(
                "在当前位置使用所选规则包创建一张可复用牌桌。",
                messages.resolve(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.command.help.description.create",
                        "missing"));
        assertEquals(
                "第 1/2 页，共 17 条命令",
                messages.format(
                        Locale.SIMPLIFIED_CHINESE,
                        "mahjongpaper.command.help.page_status",
                        "Page %s/%s - %s commands available",
                        List.of("1", "2", "17")));
        assertEquals(
                "各コマンドの用途を説明するページ形式のヘルプを表示します。",
                messages.resolve(
                        Locale.JAPANESE,
                        "mahjongpaper.command.help.description.help",
                        "missing"));
    }
}
