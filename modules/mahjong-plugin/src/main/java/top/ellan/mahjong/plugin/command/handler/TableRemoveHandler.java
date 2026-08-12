package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
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
        return Set.of("remove");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        support.requireAdmin(sender);
        if (arguments.length != 2) {
            throw CommandSupport.usage("/mahjong remove <table-id>");
        }
        TableId tableId = TableId.parse(arguments[1]);
        support.complete(
                sender,
                support.runtime().remove(tableId),
                ignored -> CommandSupport.message(
                        "mahjongpaper.command.table_removed", "Removed table %s.", tableId));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        return arguments.length == 2 && sender.hasPermission("mahjongpaper.admin")
                ? CommandSupport.filter(arguments[1], support.tableIds())
                : List.of();
    }
}
