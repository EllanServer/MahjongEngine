package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;

/** v1.5-compatible rule entry point backed by the native rule-settings dialog. */
public final class RuleDialogHandler implements SubcommandHandler {
    private final CommandSupport support;

    public RuleDialogHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("rule");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        Player player = support.requirePlayer(sender);
        if (arguments.length > 2) {
            throw CommandSupport.usage("/mahjong rule [summary]");
        }
        PlayerId playerId = new PlayerId(player.getUniqueId());
        HostedLobby lobby =
                support.runtime()
                        .lobbyTables()
                        .findByPlayer(playerId)
                        .orElseThrow(
                                () ->
                                        CommandSupport.failure(
                                                "mahjongpaper.command.not_at_lobby",
                                                "You do not belong to a waiting lobby."));
        if (arguments.length == 2 && "summary".equalsIgnoreCase(arguments[1])) {
            support.reply(
                    sender,
                    CommandSupport.message(
                            "mahjongpaper.command.rule_summary",
                            "%s/%s: %s",
                            lobby.state().ruleId(),
                            lobby.state().profileId(),
                            lobby.state().configuration()));
            return;
        }
        if (arguments.length == 2) {
            throw CommandSupport.usage("/mahjong rule [summary]");
        }
        support.runtime().dialogs().openRules(player, Optional.of(lobby.tableId()));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length == 2) {
            return CommandSupport.filter(arguments[1], List.of("summary"));
        }
        return List.of();
    }
}
