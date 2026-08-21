package top.ellan.mahjong.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.presentation.label.ActionLabelPolicy;

class CommandHelpCatalogTest {
    @Test
    void entriesKeepStableOrderAndChatSizedPages() {
        assertEquals(7, CommandHelpCatalog.HELP_PAGE_SIZE);
        List<String> names =
                CommandHelpCatalog.ENTRIES.stream()
                        .map(CommandHelpCatalog.HelpEntry::canonicalName)
                        .toList();

        assertEquals("help", names.get(0));
        assertEquals("create", names.get(1));
        assertEquals("table", names.get(9));
        assertEquals("addbot", names.get(10));
        assertEquals("removebot", names.get(11));
        assertEquals("rule", names.get(12));
        assertEquals("leaderboard", names.get(26));
        assertEquals("render", names.get(27));
        assertEquals("inspect", names.get(28));
        assertEquals("clear", names.get(29));
        assertEquals("forceend", names.get(30));
        assertEquals("deletetable", names.get(31));
        assertEquals("reload", names.get(32));
        assertEquals("room", names.get(33));
        assertTrue(names.indexOf("ready") > names.indexOf("room"));
    }

    @Test
    void displayUsagesRemainCompactEnoughForTwoLineChatEntries() {
        assertTrue(
                CommandHelpCatalog.ENTRIES.stream()
                        .map(CommandHelpCatalog.HelpEntry::usage)
                        .allMatch(usage -> usage.length() <= 64));
        assertTrue(
                CommandHelpCatalog.ENTRIES.stream()
                        .map(CommandHelpCatalog.HelpEntry::usage)
                        .noneMatch(usage -> usage.contains("MAJSOUL_HANCHAN|")));
        assertTrue(
                CommandHelpCatalog.ENTRIES.stream()
                        .map(CommandHelpCatalog.HelpEntry::fallbackDescription)
                        .allMatch(description -> ActionLabelPolicy.visualUnits(description) <= 56));
        assertEquals(
                "/mahjong create [rule] [profile]",
                CommandHelpCatalog.ENTRIES.get(1).usage());
    }

    @Test
    void aliasesAreSuggestedInCatalogOrder() {
        List<String> suggestions =
                CommandHelpCatalog.ENTRIES.stream()
                        .flatMap(entry -> entry.names().stream())
                        .distinct()
                        .toList();

        assertTrue(suggestions.indexOf("addbot") > suggestions.indexOf("table"));
        assertTrue(suggestions.indexOf("leaderboard") > suggestions.indexOf("rank"));
        assertTrue(suggestions.indexOf("deletetable") > suggestions.indexOf("forceend"));
        assertEquals(
                List.of(
                        "help",
                        "create",
                        "botmatch",
                        "mode",
                        "join",
                        "leave",
                        "list",
                        "spectate",
                        "unspectate",
                        "table",
                        "gui",
                        "panel",
                        "control",
                        "addbot",
                        "removebot"),
                suggestions.subList(0, 15));
    }
}
