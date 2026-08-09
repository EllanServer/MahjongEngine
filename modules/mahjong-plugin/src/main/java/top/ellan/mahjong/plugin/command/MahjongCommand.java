package top.ellan.mahjong.plugin.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.plugin.command.handler.LobbyActionHandler;
import top.ellan.mahjong.plugin.command.handler.RulePackAdminHandler;
import top.ellan.mahjong.plugin.command.handler.TableCreateHandler;
import top.ellan.mahjong.plugin.command.handler.TableQueryHandler;
import top.ellan.mahjong.plugin.command.handler.TableRemoveHandler;

/** Thin O(1) command router; use cases live behind dedicated handlers. */
public final class MahjongCommand implements CommandExecutor, TabCompleter {
    private final CommandSupport support;
    private final Map<String, SubcommandHandler> handlers;

    public MahjongCommand(MahjongPaperPlugin plugin, MahjongRuntime runtime) {
        support = new CommandSupport(plugin, runtime);
        LinkedHashMap<String, SubcommandHandler> routes = new LinkedHashMap<>();
        register(routes, new TableCreateHandler(support));
        register(routes, new LobbyActionHandler(support));
        register(routes, new TableQueryHandler(support));
        register(routes, new TableRemoveHandler(support));
        register(routes, new RulePackAdminHandler(support));
        handlers = Map.copyOf(routes);
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        try {
            if (arguments.length == 0) {
                support.reply(sender, "MahjongPaper 2.0 - " + support.runtime().status());
                support.reply(
                        sender,
                        "/mahjong <create|join|leave|spectate|ready|start|mode|list|state|remove|rules>");
                return true;
            }
            SubcommandHandler handler =
                    handlers.get(arguments[0].toLowerCase(Locale.ROOT));
            if (handler == null) {
                throw new IllegalArgumentException("Unknown subcommand");
            }
            handler.execute(sender, arguments);
        } catch (RuntimeException failure) {
            support.reply(sender, "FAILED: " + CommandSupport.safeMessage(failure));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] arguments) {
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
