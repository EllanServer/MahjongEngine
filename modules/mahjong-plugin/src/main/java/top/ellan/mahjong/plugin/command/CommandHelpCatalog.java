package top.ellan.mahjong.plugin.command;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable paged command help catalog; the router only reads this data. */
final class CommandHelpCatalog {
    static final int HELP_PAGE_SIZE = 10;
    static final List<HelpEntry> ENTRIES =
            List.of(
                    help(
                            "help",
                            "/mahjong help [page]",
                            "Show this paged command help with an explanation for every command.",
                            false),
                    help(
                            "create",
                            "/mahjong create [riichi|mcr|sichuan] [profile]",
                            "Create a reusable table at your position; riichi is the default rule pack.",
                            false),
                    help(
                            "botmatch",
                            "/mahjong botmatch [MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN]",
                            "Admin: create a four-bot demo match and spectate it.",
                            true),
                    help(
                            "join",
                            "/mahjong join <table-id> [seat]",
                            "Join a table; with no seat the first empty chair is selected.",
                            false),
                    help(
                            "leave",
                            "/mahjong leave",
                            "Leave your current seat and table.",
                            false),
                    help(
                            "spectate",
                            "/mahjong spectate <table-id>",
                            "Watch a table without taking a seat.",
                            false),
                    help(
                            "unspectate",
                            "/mahjong unspectate",
                            "Stop watching the current table.",
                            false),
                    help(
                            "ready",
                            "/mahjong ready",
                            "Toggle your ready state before the match starts.",
                            false),
                    help(
                            "owner",
                            "/mahjong owner <east|south|west|north>",
                            "Transfer table ownership to the selected occupied seat.",
                            false,
                            "transfer"),
                    help(
                            "bot",
                            "/mahjong bot <add|remove> <seat>",
                            "Add or remove a bot; addbot/removebot reuse the first available seat.",
                            false,
                            "addbot",
                            "removebot"),
                    help(
                            "start",
                            "/mahjong start",
                            "Start the match after the lobby meets its start conditions.",
                            false),
                    help(
                            "mode",
                            "/mahjong mode <riichi|mcr|sichuan|MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN> [profile]",
                            "Change the lobby rule pack and optional profile before starting.",
                            false),
                    help(
                            "auto",
                            "/mahjong auto <on|off>",
                            "Enable or disable automatic trustee play for your active seat.",
                            false,
                            "trustee"),
                    help(
                            "riichi",
                            "/mahjong riichi <index>",
                            "Declare riichi and discard the selected hand tile.",
                            false),
                    help(
                            "tsumo",
                            "/mahjong tsumo",
                            "Declare a self-draw win.",
                            false),
                    help(
                            "ron",
                            "/mahjong ron",
                            "Claim the current discard for a win.",
                            false),
                    help(
                            "pon",
                            "/mahjong pon",
                            "Claim the current discard for a pung.",
                            false),
                    help(
                            "minkan",
                            "/mahjong minkan",
                            "Claim the current discard for a kong.",
                            false),
                    help(
                            "chii",
                            "/mahjong chii <tileA> <tileB>",
                            "Claim the current discard for a chow.",
                            false),
                    help(
                            "kan",
                            "/mahjong kan <tile>",
                            "Declare a concealed or added kong.",
                            false),
                    help(
                            "skip",
                            "/mahjong skip",
                            "Pass the current reaction window.",
                            false),
                    help(
                            "kyuushu",
                            "/mahjong kyuushu",
                            "Declare nine terminals.",
                            false),
                    help(
                            "list",
                            "/mahjong list",
                            "Show only the table you currently belong to.",
                            false),
                    help(
                            "state",
                            "/mahjong state [table-id]",
                            "Show lobby or match state; defaults to your current table.",
                            false),
                    help(
                            "table",
                            "/mahjong table [table-id]",
                            "Open the native table-control dialog; table owner <seat|player> transfers ownership.",
                            false,
                            "gui",
                            "panel",
                            "control"),
                    help(
                            "settlement",
                            "/mahjong settlement [table-id]",
                            "Reopen the latest hand or match settlement details.",
                            false),
                    help(
                            "history",
                            "/mahjong history [page]",
                            "Show your in-progress and completed match history by page.",
                            false),
                    help(
                            "rank",
                            "/mahjong rank [riichi|mcr|sichuan] [page]",
                            "Show your ranking page; with no rule it uses your current table.",
                            false,
                            "ranking",
                            "leaderboard",
                            "lb"),
                    help(
                            "rule",
                            "/mahjong rule [summary]",
                            "Open your lobby's rule settings; rule summary prints the current profile.",
                            false),
                    help(
                            "rules",
                            "/mahjong rules <operation> [...]",
                            "List installed rule packs; admins can install, verify, activate, swap, or roll them back.",
                            false),
                    help(
                            "room",
                            "/mahjong room <wand|create|delete|list|info> [...]",
                            "Admin: define bounded game rooms and inspect them by ID or page.",
                            true,
                            "gameroom"),
                    help(
                            "ops",
                            "/mahjong ops <status|force-end|remove|reload-rooms> [...]",
                            "Admin: operate one explicit table or reload the room index; never lists all tables.",
                            true,
                            "forceend"),
                    help(
                            "reload",
                            "/mahjong reload",
                            "Admin: reload bounded runtime indexes; rule-pack/core changes still need a restart.",
                            true),
                    help(
                            "referee",
                            "/mahjong referee <table-id> <operation> <seat> [ruling]",
                            "Admin: submit an official Sichuan referee ruling for a live table.",
                            true),
                    help(
                            "remove",
                            "/mahjong remove <table-id>",
                            "Remove your own waiting table; admins may remove any table.",
                            false,
                            "deletetable"));

    private static HelpEntry help(
            String canonicalName,
            String usage,
            String fallbackDescription,
            boolean adminOnly,
            String... aliases) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(canonicalName);
        for (String alias : aliases) {
            names.add(alias);
        }
        return new HelpEntry(
                canonicalName,
                Set.copyOf(names),
                usage,
                "mahjongpaper.command.help.description." + canonicalName,
                fallbackDescription,
                adminOnly);
    }

    record HelpEntry(
            String canonicalName,
            Set<String> names,
            String usage,
            String translationKey,
            String fallbackDescription,
            boolean adminOnly) {
        HelpEntry {
            canonicalName = Objects.requireNonNull(canonicalName, "canonicalName");
            names = Set.copyOf(names);
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(translationKey, "translationKey");
            Objects.requireNonNull(fallbackDescription, "fallbackDescription");
        }
    }
}
