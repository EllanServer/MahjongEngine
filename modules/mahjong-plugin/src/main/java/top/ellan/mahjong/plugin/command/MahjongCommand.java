package top.ellan.mahjong.plugin.command;

import static net.momirealms.sparrow.message.tag.resolver.Placeholder.component;
import static net.momirealms.sparrow.message.tag.resolver.Placeholder.styling;
import static net.momirealms.sparrow.message.tag.resolver.Placeholder.unparsed;
import static top.ellan.mahjong.plugin.i18n.SparrowMessageRenderer.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.plugin.command.handler.LobbyActionHandler;
import top.ellan.mahjong.plugin.command.handler.MatchActionCommandHandler;
import top.ellan.mahjong.plugin.command.handler.GameRoomCommandHandler;
import top.ellan.mahjong.plugin.command.handler.BotMatchHandler;
import top.ellan.mahjong.plugin.command.handler.ReloadCommandHandler;
import top.ellan.mahjong.plugin.command.handler.RuleDialogHandler;
import top.ellan.mahjong.plugin.command.handler.MatchAutomationHandler;
import top.ellan.mahjong.plugin.command.handler.MatchRefereeHandler;
import top.ellan.mahjong.plugin.command.handler.OperationsCommandHandler;
import top.ellan.mahjong.plugin.command.handler.PlayerRecordHandler;
import top.ellan.mahjong.plugin.command.handler.RulePackAdminHandler;
import top.ellan.mahjong.plugin.command.handler.TableCreateHandler;
import top.ellan.mahjong.plugin.command.handler.TableDialogHandler;
import top.ellan.mahjong.plugin.command.handler.TableQueryHandler;
import top.ellan.mahjong.plugin.command.handler.TableRemoveHandler;
import top.ellan.mahjong.plugin.command.handler.TableRenderHandler;

/** Thin O(1) command router; use cases live behind dedicated handlers. */
public final class MahjongCommand implements BasicCommand {
    private static final String HELP_HEADER_TEMPLATE =
            "<dark_aqua><strikethrough>------------</strikethrough></dark_aqua> "
                    + "<gold><bold><header></bold></gold> "
                    + "<dark_aqua><strikethrough>------------</strikethrough></dark_aqua>";
    private static final String HELP_SUBTITLE_TEMPLATE =
            "<gray><prefix></gray><yellow><usage></yellow><gray><suffix></gray>";
    private static final String HELP_ENTRY_USAGE_TEMPLATE =
            "<dark_aqua>  › </dark_aqua><usage>";
    private static final String HELP_ENTRY_DESCRIPTION_TEMPLATE =
            "<dark_gray>      └ </dark_gray><gray><description></gray>";
    private static final String PAGE_STATUS_TEMPLATE =
            "<dark_gray>[</dark_gray><aqua><page></aqua><gray>/</gray>"
                    + "<aqua><pages></aqua><dark_gray>] </dark_gray>"
                    + "<white><count></white><gray><suffix></gray>";
    private static final String NAVIGATION_TEMPLATE =
            "  <previous> <dark_gray>[</dark_gray><aqua><page></aqua><gray>/</gray>"
                    + "<aqua><pages></aqua><dark_gray>]</dark_gray> <next>";
    private static final Component HELP_FOOTER = render(
            "<dark_aqua><strikethrough>----------------------------------------"
                    + "</strikethrough></dark_aqua>");

    private final CommandSupport support;
    private final Map<String, SubcommandHandler> handlers;
    private final ConcurrentHashMap<HelpPageKey, List<Component>> helpPages =
            new ConcurrentHashMap<>();

    public MahjongCommand(MahjongPaperPlugin plugin, MahjongRuntime runtime) {
        support = new CommandSupport(plugin, runtime);
        LinkedHashMap<String, SubcommandHandler> routes = new LinkedHashMap<>();
        register(routes, new TableCreateHandler(support));
        register(routes, new BotMatchHandler(support));
        register(routes, new LobbyActionHandler(support));
        register(routes, new MatchAutomationHandler(support));
        register(routes, new MatchActionCommandHandler(support));
        register(routes, new MatchRefereeHandler(support));
        register(routes, new TableDialogHandler(support));
        register(routes, new TableRenderHandler(support));
        register(routes, new RuleDialogHandler(support));
        register(routes, new TableQueryHandler(support));
        register(routes, new PlayerRecordHandler(support));
        register(routes, new TableRemoveHandler(support));
        register(routes, new GameRoomCommandHandler(support));
        register(routes, new OperationsCommandHandler(support));
        register(routes, new ReloadCommandHandler(support));
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

    /** Paginated two-line help sized to avoid chat-width collisions and vertical clipping. */
    private void sendHelp(CommandSender sender, int requestedPage) {
        boolean admin = sender.hasPermission("mahjongpaper.admin");
        List<CommandHelpCatalog.HelpEntry> entries = CommandHelpCatalog.ENTRIES.stream()
                .filter(entry -> !entry.adminOnly() || admin)
                .toList();
        int pageCount = Math.max(1, (int) Math.ceil((double) entries.size() / CommandHelpCatalog.HELP_PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, pageCount));
        HelpPageKey key = new HelpPageKey(support.messageLocaleKey(sender), admin, page);
        List<Component> lines = helpPages.computeIfAbsent(
                key, ignored -> renderHelpPage(sender, entries, page, pageCount));
        lines.forEach(sender::sendMessage);
    }

    private List<Component> renderHelpPage(
            CommandSender sender,
            List<CommandHelpCatalog.HelpEntry> entries,
            int page,
            int pageCount) {
        int start = (page - 1) * CommandHelpCatalog.HELP_PAGE_SIZE;
        int end = Math.min(start + CommandHelpCatalog.HELP_PAGE_SIZE, entries.size());
        List<Component> lines = new ArrayList<>((end - start) * 2 + 5);
        lines.add(render(
                HELP_HEADER_TEMPLATE,
                unparsed(
                        "header",
                        support.text(
                                sender,
                                "mahjongpaper.command.help.header",
                                "MahjongPaper Commands"))));
        lines.add(render(
                HELP_SUBTITLE_TEMPLATE,
                unparsed(
                        "prefix",
                        support.text(
                                sender,
                                "mahjongpaper.command.help.subtitle.use",
                                "Use ")),
                unparsed("usage", "/mahjong help <page>"),
                unparsed(
                        "suffix",
                        support.text(
                                sender,
                                "mahjongpaper.command.help.subtitle.browse",
                                " to browse command pages."))));
        lines.add(pageStatus(sender, page, pageCount, entries.size()));
        for (int index = start; index < end; index++) {
            CommandHelpCatalog.HelpEntry entry = entries.get(index);
            lines.add(render(
                    HELP_ENTRY_USAGE_TEMPLATE,
                    component("usage", helpUsage(sender, entry))));
            lines.add(render(
                    HELP_ENTRY_DESCRIPTION_TEMPLATE,
                    unparsed(
                            "description",
                            support.text(
                                    sender,
                                    entry.translationKey(),
                                    entry.fallbackDescription()))));
        }
        lines.add(helpNavigation(sender, page, pageCount));
        lines.add(HELP_FOOTER);
        return List.copyOf(lines);
    }

    private Component helpUsage(CommandSender sender, CommandHelpCatalog.HelpEntry entry) {
        String command = "/mahjong " + entry.canonicalName() + ' ';
        return render(
                "<suggest><aqua><usage></aqua></suggest>",
                styling(
                        "suggest",
                        ClickEvent.suggestCommand(command),
                        HoverEvent.showText(render(
                                "<gray><hint></gray>",
                                unparsed(
                                        "hint",
                                        support.text(
                                                sender,
                                                "mahjongpaper.command.help.suggest",
                                                "Click to insert this command."))))),
                unparsed("usage", entry.usage()));
    }

    private Component pageStatus(CommandSender sender, int page, int pageCount, int commandCount) {
        return render(
                PAGE_STATUS_TEMPLATE,
                unparsed("page", Integer.toString(page)),
                unparsed("pages", Integer.toString(pageCount)),
                unparsed("count", Integer.toString(commandCount)),
                unparsed(
                        "suffix",
                        support.text(
                                sender,
                                "mahjongpaper.command.help.commands_available",
                                " commands available")));
    }

    private Component helpNavigation(CommandSender sender, int page, int pageCount) {
        return render(
                NAVIGATION_TEMPLATE,
                component(
                        "previous",
                        helpPageButton(
                                sender,
                                "mahjongpaper.command.help.previous",
                                "Prev",
                                page - 1,
                                page > 1)),
                unparsed("page", Integer.toString(page)),
                unparsed("pages", Integer.toString(pageCount)),
                component(
                        "next",
                        helpPageButton(
                                sender,
                                "mahjongpaper.command.help.next",
                                "Next",
                                page + 1,
                                page < pageCount)));
    }

    private Component helpPageButton(
            CommandSender sender,
            String labelKey,
            String fallback,
            int targetPage,
            boolean enabled) {
        String label = support.text(sender, labelKey, fallback);
        if (!enabled) {
            return render(
                    "<dark_gray>[<label>]</dark_gray>", unparsed("label", label));
        }
        String command = "/mahjong help " + targetPage;
        return render(
                "<navigate><dark_gray>[</dark_gray><yellow><label></yellow>"
                        + "<dark_gray>]</dark_gray></navigate>",
                styling(
                        "navigate",
                        ClickEvent.runCommand(command),
                        HoverEvent.showText(render(
                                "<gray><command></gray>",
                                unparsed("command", command)))),
                unparsed("label", label));
    }

    private int helpPageCount(CommandSender sender) {
        long visible = CommandHelpCatalog.ENTRIES.stream()
                .filter(entry -> !entry.adminOnly() || sender.hasPermission("mahjongpaper.admin"))
                .count();
        return Math.max(1, (int) Math.ceil((double) visible / CommandHelpCatalog.HELP_PAGE_SIZE));
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
        return CommandHelpCatalog.ENTRIES.stream()
                .filter(entry -> !entry.adminOnly() || sender.hasPermission("mahjongpaper.admin"))
                .flatMap(entry -> entry.names().stream())
                .distinct()
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

    private static void validateHelpCoverage(Set<String> routes) {
        LinkedHashSet<String> documented = new LinkedHashSet<>();
        CommandHelpCatalog.ENTRIES.forEach(entry -> documented.addAll(entry.names()));
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

    private record HelpPageKey(String locale, boolean admin, int page) {
        private HelpPageKey {
            Objects.requireNonNull(locale, "locale");
            if (page < 1) {
                throw new IllegalArgumentException("page must be positive");
            }
        }
    }
}
