package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;

/** Command fallback for the same native dialogs opened by clicking a physical table. */
public final class TableDialogHandler implements SubcommandHandler {
    private final CommandSupport support;

    public TableDialogHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("table", "gui", "settlement");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        Player player = support.requirePlayer(sender);
        if (arguments.length > 2) {
            throw CommandSupport.usage("/mahjong " + arguments[0] + " [table-id]");
        }
        Optional<TableId> tableId = arguments.length == 2
                ? Optional.of(TableId.parse(arguments[1])) : Optional.empty();
        if ("settlement".equalsIgnoreCase(arguments[0])) {
            support.runtime().dialogs().openSettlement(player, tableId);
        } else {
            support.runtime().dialogs().openTable(player, tableId);
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            return List.of();
        }
        List<String> ids = java.util.stream.Stream.concat(
                        support.runtime().lobbyTables().list().stream()
                                .map(lobby -> lobby.tableId().toString()),
                        support.runtime().liveTables().list().stream()
                                .map(match -> match.tableId().toString()))
                .sorted().toList();
        return CommandSupport.filter(arguments[1], ids);
    }
}
