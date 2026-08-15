package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;

/** Administrative table removal. */
public final class TableRemoveHandler implements SubcommandHandler {
    private final CommandSupport support;

    public TableRemoveHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("remove", "deletetable");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            throw CommandSupport.usage(
                    "/mahjong " + arguments[0].toLowerCase(java.util.Locale.ROOT)
                            + " <table-id>");
        }
        boolean adminAlias = "deletetable".equalsIgnoreCase(arguments[0]);
        if (adminAlias) {
            support.requireAdmin(sender);
        }
        TableId tableId = TableId.parse(arguments[1]);
        if (sender instanceof Player player
                && support.runtime()
                        .placement()
                        .canRemove(player, tableId)
                        .filter(allowed -> !allowed)
                        .isPresent()) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.remove_failed_protected",
                    "Land protection does not allow you to remove table %s.",
                    tableId);
        }
        java.util.concurrent.CompletionStage<Void> removal;
        if (sender.hasPermission("mahjongpaper.admin")) {
            removal = support.runtime().remove(tableId);
        } else {
            Player player = support.requirePlayer(sender);
            removal = support.runtime().removeOwnedLobby(
                    tableId, new top.ellan.mahjong.spi.PlayerId(player.getUniqueId()));
        }
        support.complete(
                sender,
                removal,
                ignored -> CommandSupport.message(
                        "mahjongpaper.command.table_removed", "Removed table %s.", tableId));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            return List.of();
        }
        if ("deletetable".equalsIgnoreCase(arguments[0])) {
            return sender.hasPermission("mahjongpaper.admin")
                    ? CommandSupport.filter(arguments[1], support.currentTableIds(sender))
                    : List.of();
        }
        if (sender.hasPermission("mahjongpaper.admin")) {
            return CommandSupport.filter(arguments[1], support.currentTableIds(sender));
        }
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        var owned = support.runtime().lobbyTables()
                .findByPlayer(new top.ellan.mahjong.spi.PlayerId(player.getUniqueId()))
                .filter(lobby -> lobby.state().ownerId().value().equals(player.getUniqueId()))
                .map(lobby -> List.of(lobby.tableId().toString()))
                .orElseGet(List::of);
        return CommandSupport.filter(arguments[1], owned);
    }
}
