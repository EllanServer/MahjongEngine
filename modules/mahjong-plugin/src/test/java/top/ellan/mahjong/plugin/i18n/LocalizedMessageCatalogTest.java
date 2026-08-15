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
}
