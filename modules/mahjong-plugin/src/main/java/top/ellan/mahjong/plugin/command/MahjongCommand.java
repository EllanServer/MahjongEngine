package top.ellan.mahjong.plugin.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.plugin.command.handler.LobbyActionHandler;
import top.ellan.mahjong.plugin.command.handler.MatchAutomationHandler;
import top.ellan.mahjong.plugin.command.handler.MatchRefereeHandler;
import top.ellan.mahjong.plugin.command.handler.PlayerRecordHandler;
import top.ellan.mahjong.plugin.command.handler.RulePackAdminHandler;
import top.ellan.mahjong.plugin.command.handler.TableCreateHandler;
import top.ellan.mahjong.plugin.command.handler.TableDialogHandler;
import top.ellan.mahjong.plugin.command.handler.TableQueryHandler;
import top.ellan.mahjong.plugin.command.handler.TableRemoveHandler;

/** Thin O(1) command router; use cases live behind dedicated handlers. */
public final class MahjongCommand implements BasicCommand {
    private static final int HELP_PAGE_SIZE = 10;
    private static final List<HelpEntry> HELP_ENTRIES =
            List.of(
                    help(
                            "help",
                            "/mahjong help [page]",
                            "Show this paged command help with an explanation for every command.",
                            false),
                    help(
                            "create",
                            "/mahjong create <riichi|mcr|sichuan> [profile]",
                            "Create a reusable table at your position with the selected rule pack.",
                            false),
                    help(
                            "join",
                            "/mahjong join <table-id> <seat>",
                            "Join a table in the specified seat; you can also click a chair.",
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
                            "Add or remove a bot in the selected seat before starting.",
                            false),
                    help(
                            "start",
                            "/mahjong start",
                            "Start the match after the lobby meets its start conditions.",
                            false),
                    help(
                            "mode",
                            "/mahjong mode <riichi|mcr|sichuan> [profile]",
                            "Change the lobby rule pack and optional profile before starting.",
                            false),
                    help(
                            "auto",
                            "/mahjong auto <on|off>",
                            "Enable or disable automatic trustee play for your active seat.",
                            false,
                            "trustee"),
                    help(
                            "list",
                            "/mahjong list",
                            "List all waiting lobbies and active matches.",
                            false),
                    help(
                            "state",
                            "/mahjong state [table-id]",
                            "Show lobby or match state; defaults to your current table.",
                            false),
                    help(
                            "table",
                            "/mahjong table [table-id]",
                            "Open the native table-control dialog; clicking the physical table does the same.",
                            false,
                            "gui"),
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
                            "/mahjong rank <riichi|mcr|sichuan> [page]",
                            "Show the ranking page for one official rule pack.",
                            false,
                            "ranking"),
                    help(
                            "rules",
                            "/mahjong rules <operation> [...]",
                            "List installed rule packs; admins can install, verify, activate, swap, or roll them back.",
                            false),
                    help(
                            "referee",
                            "/mahjong referee <table-id> <operation> <seat> [ruling]",
                            "Admin: submit an official Sichuan referee ruling for a live table.",
                            true),
                    help(
                            "remove",
                            "/mahjong remove <table-id>",
                            "Remove your own waiting table; admins may remove any table.",
                            false));

    private final CommandSupport support;
    private final Map<String, SubcommandHandler> handlers;

    public MahjongCommand(MahjongPaperPlugin plugin, MahjongRuntime runtime) {
        support = new CommandSupport(plugin, runtime);
        LinkedHashMap<String, SubcommandHandler> routes = new LinkedHashMap<>();
        register(routes, new TableCreateHandler(support));
        register(routes, new LobbyActionHandler(support));
        register(routes, new MatchAutomationHandler(support));
        register(routes, new MatchRefereeHandler(support));
        register(routes, new TableDialogHandler(support));
        register(routes, new TableQueryHandler(support));
        register(routes, new PlayerRecordHandler(support));
        register(routes, new TableRemoveHandler(support));
        register(routes, new RulePackAdminHandler(support));
        validateHelpCoverage(routes.keySet());
        handlers = Map.copyOf(routes);
    }

    @Override
    public void execute(CommandSourceStack stack, String[] arguments) {
        Objects.requireNonNull(stack, "stack");
        onCommand(stack.getSender(), arguments);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] arguments) {
        Objects.requireNonNull(stack, "stack");
        return onTabComplete(stack.getSender(), arguments);
    }

    @Override
    public String permission() {
        return "mahjongpaper.command";
    }

    private void onCommand(CommandSender sender, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(arguments, "arguments");
        try {
            if (arguments.length == 0) {
                support.reply(
                        sender,
                        CommandSupport.message(
                                "mahjongpaper.command.status",
                                "MahjongPaper 2.0 - %s",
                                support.runtime().status()));
                sendHelp(sender, 1);
                return;
            }
            String first = arguments[0].toLowerCase(Locale.ROOT);
            if ("help".equals(first)) {
                if (arguments.length > 2) {
                    throw CommandSupport.usage("/mahjong help [page]");
                }
                sendHelp(sender, arguments.length == 2 ? helpPage(arguments[1]) : 1);
                return;
            }
            if (first.matches("[0-9]+")) {
                sendHelp(sender, helpPage(first));
                return;
            }
            SubcommandHandler handler =
                    handlers.get(first);
            if (handler == null) {
                throw CommandSupport.failure(
                        "mahjongpaper.command.unknown_subcommand", "Unknown subcommand.");
            }
            handler.execute(sender, arguments);
        } catch (LocalizedCommandException failure) {
            support.reply(sender, failure.reply());
        } catch (RuntimeException failure) {
            support.reply(sender, CommandSupport.failed(CommandSupport.safeMessage(failure)));
        }
    }

    /** Paginated usage-and-description help matching the v1.5.0 reading flow. */
    private void sendHelp(CommandSender sender, int requestedPage) {
        List<HelpEntry> entries = HELP_ENTRIES.stream()
                .filter(entry -> !entry.adminOnly() || sender.hasPermission("mahjongpaper.admin"))
                .toList();
        int pageCount = Math.max(1, (int) Math.ceil((double) entries.size() / HELP_PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, pageCount));
        int start = (page - 1) * HELP_PAGE_SIZE;
        int end = Math.min(start + HELP_PAGE_SIZE, entries.size());

        List<Component> lines = new ArrayList<>();
        lines.add(
                Component.text("------------ ", NamedTextColor.DARK_AQUA)
                        .append(Component.text(
                                        support.text(
                                                sender,
                                                "mahjongpaper.command.help.header",
                                                "MahjongPaper Commands"),
                                        NamedTextColor.GOLD)
                                .decorate(TextDecoration.BOLD))
                        .append(Component.text(" ------------", NamedTextColor.DARK_AQUA)));
        lines.add(
                Component.text(
                        support.text(
                                sender,
                                "mahjongpaper.command.help.subtitle",
                                "<> is required and [] is optional; use /mahjong help <page> to browse."),
                        NamedTextColor.GRAY));
        lines.add(
                Component.text(
                        support.text(
                                sender,
                                "mahjongpaper.command.help.page_status",
                                "Page %s/%s - %s commands available",
                                page,
                                pageCount,
                                entries.size()),
                        NamedTextColor.GRAY));
        for (int index = start; index < end; index++) {
            HelpEntry entry = entries.get(index);
            lines.add(
                    Component.text("  - ", NamedTextColor.DARK_AQUA)
                            .append(Component.text(entry.usage(), NamedTextColor.AQUA))
                            .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(
                                    support.text(
                                            sender,
                                            entry.translationKey(),
                                            entry.fallbackDescription()),
                                    NamedTextColor.WHITE)));
        }
        if (pageCount > 1) {
            lines.add(helpNavigation(sender, page, pageCount));
        }
        lines.add(Component.text("----------------------------------------", NamedTextColor.DARK_AQUA));
        lines.forEach(sender::sendMessage);
    }

    private Component helpNavigation(CommandSender sender, int page, int pageCount) {
        Component navigation = Component.text("  ");
        if (page > 1) {
            navigation = navigation.append(
                    Component.text(
                                    "< "
                                            + support.text(
                                                    sender,
                                                    "mahjongpaper.command.help.previous",
                                                    "Previous"),
                                    NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/mahjong help " + (page - 1))));
        }
        navigation = navigation.append(
                Component.text("  [" + page + '/' + pageCount + "]  ", NamedTextColor.GRAY));
        if (page < pageCount) {
            navigation = navigation.append(
                    Component.text(
                                    support.text(
                                                    sender,
                                                    "mahjongpaper.command.help.next",
                                                    "Next")
                                            + " >",
                                    NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/mahjong help " + (page + 1))));
        }
        return navigation;
    }

    private int helpPageCount(CommandSender sender) {
        long visible = HELP_ENTRIES.stream()
                .filter(entry -> !entry.adminOnly() || sender.hasPermission("mahjongpaper.admin"))
                .count();
        return Math.max(1, (int) Math.ceil((double) visible / HELP_PAGE_SIZE));
    }

    private static int helpPage(String value) {
        try {
            int page = Integer.parseInt(value);
            if (page < 1) {
                throw new NumberFormatException();
            }
            return page;
        } catch (NumberFormatException failure) {
            throw CommandSupport.usage("/mahjong help [page]");
        }
    }

    private List<String> onTabComplete(CommandSender sender, String[] arguments) {
        if (arguments.length == 0) {
            return rootSuggestions(sender);
        }
        if (arguments.length == 1) {
            return CommandSupport.filter(arguments[0], rootSuggestions(sender));
        }
        if (arguments.length == 2 && "help".equalsIgnoreCase(arguments[0])) {
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= helpPageCount(sender); page++) {
                pages.add(String.valueOf(page));
            }
            return CommandSupport.filter(arguments[1], pages);
        }
        SubcommandHandler handler = handlers.get(arguments[0].toLowerCase(Locale.ROOT));
        return handler == null ? List.of() : handler.complete(sender, arguments);
    }

    private static List<String> rootSuggestions(CommandSender sender) {
        return HELP_ENTRIES.stream()
                .filter(entry -> !entry.adminOnly() || sender.hasPermission("mahjongpaper.admin"))
                .map(HelpEntry::canonicalName)
                .toList();
    }

    private static void register(
            Map<String, SubcommandHandler> routes, SubcommandHandler handler) {
        for (String name : handler.names()) {
            if (routes.putIfAbsent(name, handler) != null) {
                throw new IllegalStateException("Duplicate command route: " + name);
            }
        }
    }

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

    private static void validateHelpCoverage(Set<String> routes) {
        LinkedHashSet<String> documented = new LinkedHashSet<>();
        HELP_ENTRIES.forEach(entry -> documented.addAll(entry.names()));
        LinkedHashSet<String> expected = new LinkedHashSet<>(routes);
        expected.add("help");
        if (!documented.equals(expected)) {
            throw new IllegalStateException(
                    "Command help coverage differs from registered routes: documented="
                            + documented
                            + ", routes="
                            + expected);
        }
    }

    private record HelpEntry(
            String canonicalName,
            Set<String> names,
            String usage,
            String translationKey,
            String fallbackDescription,
            boolean adminOnly) {
        private HelpEntry {
            canonicalName = Objects.requireNonNull(canonicalName, "canonicalName");
            names = Set.copyOf(names);
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(translationKey, "translationKey");
            Objects.requireNonNull(fallbackDescription, "fallbackDescription");
        }
    }
}
