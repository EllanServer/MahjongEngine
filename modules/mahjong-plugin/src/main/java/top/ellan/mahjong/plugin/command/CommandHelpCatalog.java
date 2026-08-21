package top.ellan.mahjong.plugin.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable command help catalog with chat-width-safe summaries and stable reading order. */
final class CommandHelpCatalog {
    /** Seven two-line entries plus page chrome fit the default expanded Minecraft chat height. */
    static final int HELP_PAGE_SIZE = 7;
    static final List<HelpEntry> ENTRIES =
            List.of(
                    help(
                            "help",
                            "/mahjong help [page]",
                            "Show paged help for every available command.",
                            false),
                    help(
                            "create",
                            "/mahjong create [rule] [profile]",
                            "Create a table with an optional rule and profile.",
                            false),
                    help(
                            "botmatch",
                            "/mahjong botmatch [preset]",
                            "Admin: create a four-bot test match and spectate it.",
                            true),
                    help(
                            "mode",
                            "/mahjong mode <rule|preset> [profile]",
                            "Change the waiting rule or preset; Tab completes values.",
                            false),
                    help(
                            "join",
                            "/mahjong join <table> [seat]",
                            "Join an existing table as a player.",
                            false),
                    help(
                            "leave",
                            "/mahjong leave",
                            "Leave your current table or stop spectating.",
                            false),
                    help(
                            "list",
                            "/mahjong list",
                            "Show only the table you currently belong to.",
                            false),
                    help(
                            "spectate",
                            "/mahjong spectate <table>",
                            "Watch a table without taking a seat.",
                            false),
                    help(
                            "unspectate",
                            "/mahjong unspectate",
                            "Stop spectating the current table.",
                            false),
                    help(
                            "table",
                            "/mahjong table [table]",
                            "Open the table control panel.",
                            false,
                            "gui",
                            "panel",
                            "control"),
                    help(
                            "addbot",
                            "/mahjong addbot",
                            "Fill one empty seat with a bot.",
                            false),
                    help(
                            "removebot",
                            "/mahjong removebot",
                            "Remove one bot before the round starts.",
                            false),
                    help(
                            "rule",
                            "/mahjong rule [summary]",
                            "Open the rule GUI or change a rule before starting.",
                            false),
                    help(
                            "start",
                            "/mahjong start",
                            "Toggle ready; the owner starts when everyone is ready.",
                            false),
                    help(
                            "state",
                            "/mahjong state [table]",
                            "Show the current table and round state.",
                            false),
                    help(
                            "riichi",
                            "/mahjong riichi <index>",
                            "Declare riichi and discard a tile index. Riichi only.",
                            false),
                    help(
                            "tsumo",
                            "/mahjong tsumo",
                            "Claim a self-draw win if your hand is ready.",
                            false),
                    help(
                            "ron",
                            "/mahjong ron",
                            "Claim a win on another player's discard.",
                            false),
                    help(
                            "pon",
                            "/mahjong pon",
                            "Call pon on the current reaction window.",
                            false),
                    help(
                            "minkan",
                            "/mahjong minkan",
                            "Call an open kan on the current reaction window.",
                            false),
                    help(
                            "chii",
                            "/mahjong chii <tileA> <tileB>",
                            "Call chii with the listed two tiles from your hand.",
                            false),
                    help(
                            "kan",
                            "/mahjong kan <tile>",
                            "Declare ankan or kakan with the given tile kind.",
                            false),
                    help(
                            "skip",
                            "/mahjong skip",
                            "Pass the current reaction opportunity.",
                            false),
                    help(
                            "kyuushu",
                            "/mahjong kyuushu",
                            "Declare a nine-terminals abortive draw. Riichi only.",
                            false),
                    help(
                            "settlement",
                            "/mahjong settlement [table]",
                            "Reopen the latest settlement UI for this table.",
                            false),
                    help(
                            "rank",
                            "/mahjong rank [rule] [page]",
                            "Show rank points, match count, and score for one mode.",
                            false,
                            "ranking"),
                    help(
                            "leaderboard",
                            "/mahjong leaderboard [rule] [page]",
                            "Show the ranked leaderboard for one mode.",
                            false,
                            "lb"),
                    help(
                            "render",
                            "/mahjong render",
                            "Force a table display refresh.",
                            true),
                    help(
                            "inspect",
                            "/mahjong inspect",
                            "Show the table anchor and scene diagnostics.",
                            true),
                    help(
                            "clear",
                            "/mahjong clear",
                            "Remove current display entities for the table.",
                            true),
                    help(
                            "forceend",
                            "/mahjong forceend <table>",
                            "Admin: end the match and return its table to waiting.",
                            true),
                    help(
                            "deletetable",
                            "/mahjong deletetable <table>",
                            "Admin: immediately delete this or another table.",
                            true),
                    help(
                            "reload",
                            "/mahjong reload",
                            "Admin: reload rooms; other changes need a restart.",
                            true),
                    help(
                            "room",
                            "/mahjong room <operation> [...]",
                            "Manage game rooms.",
                            true,
                            "gameroom"),
                    help(
                            "ready",
                            "/mahjong ready",
                            "Toggle your ready state before the match starts.",
                            false),
                    help(
                            "owner",
                            "/mahjong owner <seat>",
                            "Transfer table ownership to the selected occupied seat.",
                            false,
                            "transfer"),
                    help(
                            "bot",
                            "/mahjong bot <add|remove> <seat>",
                            "Add or remove a bot before starting.",
                            false),
                    help(
                            "auto",
                            "/mahjong auto <on|off>",
                            "Toggle automatic play for your active seat.",
                            false,
                            "trustee"),
                    help(
                            "history",
                            "/mahjong history [page]",
                            "Show active and completed match history by page.",
                            false),
                    help(
                            "rules",
                            "/mahjong rules <operation> [...]",
                            "List rule packs; admins can verify, swap, or roll back.",
                            false),
                    help(
                            "ops",
                            "/mahjong ops <operation> [...]",
                            "Admin: operate one table or reload the room index.",
                            true),
                    help(
                            "referee",
                            "/mahjong referee <table> <operation> <seat> [ruling]",
                            "Admin: submit a Sichuan ruling for a live table.",
                            true),
                    help(
                            "remove",
                            "/mahjong remove <table>",
                            "Remove your waiting table; admins may remove any.",
                            false));

    private static HelpEntry help(
            String canonicalName,
            String usage,
            String fallbackDescription,
            boolean adminOnly,
            String... aliases) {
        ArrayList<String> names = new ArrayList<>(1 + aliases.length);
        names.add(canonicalName);
        for (String alias : aliases) {
            names.add(alias);
        }
        return new HelpEntry(
                canonicalName,
                List.copyOf(names),
                usage,
                "mahjongpaper.command.help.description." + canonicalName,
                fallbackDescription,
                adminOnly);
    }

    record HelpEntry(
            String canonicalName,
            List<String> names,
            String usage,
            String translationKey,
            String fallbackDescription,
            boolean adminOnly) {
        HelpEntry {
            canonicalName = Objects.requireNonNull(canonicalName, "canonicalName");
            names = List.copyOf(names);
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(translationKey, "translationKey");
            Objects.requireNonNull(fallbackDescription, "fallbackDescription");
        }
    }
}
