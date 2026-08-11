package top.ellan.mahjong.plugin.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.plugin.command.handler.LobbyActionHandler;
import top.ellan.mahjong.plugin.command.handler.MatchAutomationHandler;
import top.ellan.mahjong.plugin.command.handler.MatchRefereeHandler;
import top.ellan.mahjong.plugin.command.handler.PlayerRecordHandler;
import top.ellan.mahjong.plugin.command.handler.RulePackAdminHandler;
import top.ellan.mahjong.plugin.command.handler.TableCreateHandler;
import top.ellan.mahjong.plugin.command.handler.TableQueryHandler;
import top.ellan.mahjong.plugin.command.handler.TableRemoveHandler;

/** Thin O(1) command router; use cases live behind dedicated handlers. */
public final class MahjongCommand implements BasicCommand {
    private final CommandSupport support;
    private final Map<String, SubcommandHandler> handlers;

    public MahjongCommand(MahjongPaperPlugin plugin, MahjongRuntime runtime) {
        support = new CommandSupport(plugin, runtime);
        LinkedHashMap<String, SubcommandHandler> routes = new LinkedHashMap<>();
        register(routes, new TableCreateHandler(support));
        register(routes, new LobbyActionHandler(support));
        register(routes, new MatchAutomationHandler(support));
        register(routes, new MatchRefereeHandler(support));
        register(routes, new TableQueryHandler(support));
        register(routes, new PlayerRecordHandler(support));
        register(routes, new TableRemoveHandler(support));
        register(routes, new RulePackAdminHandler(support));
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
            if (first.matches("[0-9]+")) {
                sendHelp(sender, Integer.parseInt(first));
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

    private static final int HELP_PAGE_SIZE = 10;

    /** Paginated, line-by-line help matching the v1.5.0 layout. */
    private void sendHelp(CommandSender sender, int requestedPage) {
        List<String> names = handlers.keySet().stream().sorted().toList();
        int pageCount = Math.max(1, (int) Math.ceil((double) names.size() / HELP_PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, pageCount));
        int start = (page - 1) * HELP_PAGE_SIZE;
        int end = Math.min(start + HELP_PAGE_SIZE, names.size());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("===== MahjongPaper Help =====", NamedTextColor.GOLD));
        lines.add(Component.text("Subcommands", NamedTextColor.AQUA));
        lines.add(
                Component.text(
                        String.format(
                                "Page %d/%d (%d commands)", page, pageCount, names.size()),
                        NamedTextColor.GRAY));
        for (int index = start; index < end; index++) {
            lines.add(
                    Component.text("  - /mahjong " + names.get(index), NamedTextColor.DARK_AQUA));
        }
        if (pageCount > 1) {
            lines.add(
                    Component.text(
                            String.format(
                                    "Page %d/%d - run /mahjong <page> to navigate",
                                    page, pageCount),
                            NamedTextColor.GRAY));
        }
        lines.add(Component.text("==============================", NamedTextColor.GOLD));
        lines.forEach(sender::sendMessage);
    }

    private List<String> onTabComplete(CommandSender sender, String[] arguments) {
        if (arguments.length == 1) {
            return CommandSupport.filter(arguments[0], handlers.keySet().stream().sorted().toList());
        }
        SubcommandHandler handler = handlers.get(arguments[0].toLowerCase(Locale.ROOT));
        return handler == null ? List.of() : handler.complete(sender, arguments);
    }

    private static void register(
            Map<String, SubcommandHandler> routes, SubcommandHandler handler) {
        for (String name : handler.names()) {
            if (routes.putIfAbsent(name, handler) != null) {
                throw new IllegalStateException("Duplicate command route: " + name);
            }
        }
    }
}
