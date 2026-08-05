package top.ellan.mahjong.command.subcommand;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.command.MahjongCommandContext;
import top.ellan.mahjong.command.MahjongSubcommand;
import top.ellan.mahjong.render.display.ManagedEntityAudit;
import top.ellan.mahjong.table.core.MahjongTableSession;

public final class DebugSubcommand extends AbstractMahjongSubcommand {
    public DebugSubcommand(MahjongCommandContext context) { super(context); }
    public MahjongSubcommand create() { return this.subcommand("debug", true); }
    @Override protected void execute(CommandSender sender, Player player, String[] args) {
        if (args.length > 0 && "entities".equals(args[0])) {
            this.sendEntityReport(sender);
            return;
        }
        MahjongTableSession table = this.context.requireViewedTable(player);
        if (table == null) { return; }
        this.context.sendMeldLayoutDebug(player, table, args);
    }

    private void sendEntityReport(CommandSender sender) {
        Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("MahjongPaper");
        if (plugin == null) {
            sender.sendMessage("MahjongPaper plugin instance not found.");
            return;
        }
        Set<String> active = new HashSet<>(this.context.tableManager().tableIds());
        ManagedEntityAudit.EntityReport report = ManagedEntityAudit.scan(plugin, active);
        sender.sendMessage("Mahjong managed entities: total=" + report.total()
            + " orphaned=" + report.orphanCount());
        for (Map.Entry<String, Integer> entry : report.perTable().entrySet()) {
            sender.sendMessage("  table " + entry.getKey() + ": " + entry.getValue() + " entities");
        }
        if (report.orphanCount() > 0) {
            sender.sendMessage("  use /mahjong cleanup to remove orphaned entities");
        }
    }
}
