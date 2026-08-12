package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;

/** Lightweight lobby and active-match diagnostics. */
public final class TableQueryHandler implements SubcommandHandler {
    private final CommandSupport support;

    public TableQueryHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("list", "state");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if ("list".equalsIgnoreCase(arguments[0])) {
            list(sender, arguments);
        } else {
            state(sender, arguments);
        }
    }

    private void list(CommandSender sender, String[] arguments) {
        if (arguments.length != 1) {
            throw CommandSupport.usage("/mahjong list");
        }
        state(sender, new String[] {"state"});
    }

    private void state(CommandSender sender, String[] arguments) {
        if (arguments.length > 2) {
            throw CommandSupport.usage("/mahjong state [table-id]");
        }
        TableId tableId =
                arguments.length == 2 ? TableId.parse(arguments[1]) : tableFor(sender);
        HostedLobby lobby = support.runtime().lobbyTables().find(tableId).orElse(null);
        if (lobby != null) {
            support.reply(
                    sender,
                    CommandSupport.message(
                            "mahjongpaper.command.state_detail", "%s", lobby.state()));
            return;
        }
        StartedRulePackMatch match =
                support.runtime()
                        .liveTables()
                        .find(tableId)
                        .orElseThrow(() -> CommandSupport.failure(
                                "mahjongpaper.command.unknown_table", "Unknown table."));
        var snapshot = match.actor().snapshot();
        support.reply(
                sender,
                CommandSupport.message(
                        "mahjongpaper.command.table_state",
                        "%s - lifecycle %s - revision %s - mailbox %s - rule in flight %s - outbox %s",
                        tableId,
                        snapshot.lifecycle(),
                        snapshot.revision(),
                        snapshot.mailboxDepth(),
                        snapshot.ruleCalculationInFlight(),
                        snapshot.outboxHealth()));
    }

    private TableId tableFor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.console_table_required",
                    "The console must provide a table id.");
        }
        PlayerId playerId = new PlayerId(player.getUniqueId());
        return support.runtime()
                .lobbyTables()
                .findByPlayer(playerId)
                .map(HostedLobby::tableId)
                .or(
                        () ->
                                support.runtime()
                                        .liveTables()
                                        .findByPlayer(playerId)
                                        .map(StartedRulePackMatch::tableId))
                .orElseThrow(() -> CommandSupport.failure(
                        "mahjongpaper.command.not_at_table", "You do not belong to a table."));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        return arguments.length == 2 && "state".equalsIgnoreCase(arguments[0])
                ? CommandSupport.filter(arguments[1], support.currentTableIds(sender))
                : List.of();
    }
}
