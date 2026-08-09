package top.ellan.mahjong.plugin.command.handler;

import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.plugin.StartedRulePackMatch;
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
            throw new IllegalArgumentException("Usage: /mahjong list");
        }
        var lobbies = support.runtime().lobbyTables().list();
        var matches = support.runtime().liveTables().list();
        support.reply(sender, "Tables: lobbies=" + lobbies.size() + ", matches=" + matches.size());
        for (HostedLobby lobby : lobbies) {
            support.reply(
                    sender,
                    lobby.tableId()
                            + " LOBBY "
                            + lobby.state().ruleId()
                            + '/'
                            + lobby.state().profileId()
                            + " seats="
                            + lobby.state().occupiedSeatCount()
                            + '/'
                            + lobby.state().seats().size());
        }
        for (StartedRulePackMatch match : matches) {
            support.reply(
                    sender,
                    match.tableId()
                            + " "
                            + match.actor().snapshot().lifecycle()
                            + " "
                            + match.binding().rulePack());
        }
    }

    private void state(CommandSender sender, String[] arguments) {
        if (arguments.length > 2) {
            throw new IllegalArgumentException("Usage: /mahjong state [table-id]");
        }
        TableId tableId =
                arguments.length == 2 ? TableId.parse(arguments[1]) : tableFor(sender);
        HostedLobby lobby = support.runtime().lobbyTables().find(tableId).orElse(null);
        if (lobby != null) {
            support.reply(sender, lobby.state().toString());
            return;
        }
        StartedRulePackMatch match =
                support.runtime()
                        .liveTables()
                        .find(tableId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown table"));
        var snapshot = match.actor().snapshot();
        support.reply(
                sender,
                tableId
                        + " lifecycle="
                        + snapshot.lifecycle()
                        + " revision="
                        + snapshot.revision()
                        + " mailbox="
                        + snapshot.mailboxDepth()
                        + " ruleInFlight="
                        + snapshot.ruleCalculationInFlight()
                        + " outbox="
                        + snapshot.outboxHealth());
    }

    private TableId tableFor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("Console must provide a table id");
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
                .orElseThrow(() -> new IllegalArgumentException("You do not belong to a table"));
    }
}
