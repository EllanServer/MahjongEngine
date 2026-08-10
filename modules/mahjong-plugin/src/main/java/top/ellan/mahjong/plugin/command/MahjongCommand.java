package top.ellan.mahjong.plugin.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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
                support.reply(
                        sender,
                        CommandSupport.message(
                                "mahjongpaper.command.help",
                                "/mahjong <create|join|leave|spectate|ready|owner|bot|start|mode|auto|referee|list|state|history|rank|remove|rules>"));
                return;
            }
            SubcommandHandler handler =
                    handlers.get(arguments[0].toLowerCase(Locale.ROOT));
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
