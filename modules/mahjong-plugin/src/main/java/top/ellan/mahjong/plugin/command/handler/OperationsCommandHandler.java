package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;

/** Explicit-ID operational entry point; it deliberately exposes no server-wide table listing. */
public final class OperationsCommandHandler implements SubcommandHandler {
    private static final List<String> OPERATIONS =
            List.of("status", "force-end", "remove", "reload-rooms");
    private final CommandSupport support;

    public OperationsCommandHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("ops", "forceend");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        support.requireAdmin(sender);
        if ("forceend".equalsIgnoreCase(arguments[0])) {
            if (arguments.length != 2) {
                throw CommandSupport.usage("/mahjong forceend <table-id>");
            }
            forceEnd(sender, new String[] {"ops", "force-end", arguments[1]});
            return;
        }
        if (arguments.length < 2) {
            throw usage();
        }
        switch (arguments[1].toLowerCase(java.util.Locale.ROOT)) {
            case "status" -> status(sender, arguments);
            case "force-end" -> forceEnd(sender, arguments);
            case "remove" -> remove(sender, arguments);
            case "reload-rooms" -> reloadRooms(sender, arguments);
            default -> throw usage();
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("mahjongpaper.admin")) {
            return List.of();
        }
        if (arguments.length == 1 && "forceend".equalsIgnoreCase(arguments[0])) {
            return support.currentTableIds(sender);
        }
        if (arguments.length == 2) {
            return CommandSupport.filter(arguments[1], OPERATIONS);
        }
        if (arguments.length == 3
                && ("status".equalsIgnoreCase(arguments[1])
                        || "force-end".equalsIgnoreCase(arguments[1])
                        || "remove".equalsIgnoreCase(arguments[1]))) {
            return CommandSupport.filter(arguments[2], support.currentTableIds(sender));
        }
        return List.of();
    }

    private void status(CommandSender sender, String[] arguments) {
        TableId tableId = requireTable(arguments, "status");
        var live = support.runtime().liveTables().find(tableId).orElse(null);
        if (live != null) {
            var snapshot = live.actor().snapshot();
            support.reply(
                    sender,
                    CommandSupport.message(
                            "mahjongpaper.command.ops.live_status",
                            "Table %s: LIVE, lifecycle=%s, revision=%s, participants=%s, mailbox=%s, rule-task=%s",
                            tableId,
                            snapshot.lifecycle(),
                            snapshot.revision(),
                            live.participants().size(),
                            snapshot.mailboxDepth(),
                            snapshot.ruleCalculationInFlight()));
            return;
        }
        var lobby = support.runtime().lobbyTables().find(tableId).orElse(null);
        if (lobby != null) {
            long occupied =
                    lobby.state().seats().stream()
                            .filter(seat -> seat.occupant().isPresent())
                            .count();
            support.reply(
                    sender,
                    CommandSupport.message(
                            "mahjongpaper.command.ops.lobby_status",
                            "Table %s: LOBBY, phase=%s, revision=%s, occupied=%s/%s",
                            tableId,
                            lobby.state().phase(),
                            lobby.state().revision(),
                            occupied,
                            lobby.state().seats().size()));
            return;
        }
        throw tableNotFound(tableId);
    }

    private void forceEnd(CommandSender sender, String[] arguments) {
        TableId tableId = requireTable(arguments, "force-end");
        if (support.runtime().liveTables().find(tableId).isEmpty()) {
            throw tableNotFound(tableId);
        }
        support.complete(
                sender,
                support.runtime().lifecycle().forceEnd(tableId, Set.of()),
                ignored ->
                        CommandSupport.message(
                                "mahjongpaper.command.ops.force_ended",
                                "Force-ended live table %s and reopened its lobby.",
                                tableId));
    }

    private void remove(CommandSender sender, String[] arguments) {
        TableId tableId = requireTable(arguments, "remove");
        if (support.runtime().liveTables().find(tableId).isEmpty()
                && support.runtime().lobbyTables().find(tableId).isEmpty()) {
            throw tableNotFound(tableId);
        }
        support.complete(
                sender,
                support.runtime().remove(tableId),
                ignored ->
                        CommandSupport.message(
                                "mahjongpaper.command.ops.removed",
                                "Removed table %s.",
                                tableId));
    }

    private void reloadRooms(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            throw CommandSupport.usage("/mahjong ops reload-rooms");
        }
        support.complete(
                sender,
                support.runtime().gameRooms().registry().reload(),
                ignored ->
                        CommandSupport.message(
                                "mahjongpaper.command.ops.rooms_reloaded",
                                "Reloaded %s indexed game rooms.",
                                support.runtime().gameRooms().registry().size()));
    }

    private static TableId requireTable(String[] arguments, String operation) {
        if (arguments.length != 3) {
            throw CommandSupport.usage("/mahjong ops " + operation + " <table-id>");
        }
        return TableId.parse(arguments[2]);
    }

    private static RuntimeException tableNotFound(TableId tableId) {
        return CommandSupport.failure(
                "mahjongpaper.command.ops.table_not_found",
                "Table %s is not loaded.",
                tableId);
    }

    private static RuntimeException usage() {
        return CommandSupport.usage(
                "/mahjong ops <status|force-end|remove|reload-rooms> [...]");
    }
}
