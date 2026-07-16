package top.ellan.mahjong.command.subcommand;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.command.MahjongCommandContext;
import top.ellan.mahjong.command.MahjongSubcommand;
import top.ellan.mahjong.table.core.MahjongTableSession;

public final class DeleteTableSubcommand extends AbstractMahjongSubcommand {
    public DeleteTableSubcommand(MahjongCommandContext context) { super(context); }
    public MahjongSubcommand create() { return this.subcommand("deletetable", true); }
    @Override protected void execute(CommandSender sender, Player player, String[] args) {
        MahjongTableSession target = this.context.resolveAdminTable(player, args);
        if (target == null) { return; }
        if (!this.context.tableManager().canBreakTable(player, target)) {
            this.context.messages().send(player, "command.deletetable_protected");
            return;
        }
        this.context.tableManager().deleteTable(target.id());
        this.context.messages().send(player, "command.deletetable_success", this.context.messages().tag("table_id", target.id()));
    }
    @Override protected List<String> suggest(Player player, String[] args) {
        return args.length == 2 ? this.context.matchPrefix(args[1], this.context.tableManager().tableIds()) : List.of();
    }
}
