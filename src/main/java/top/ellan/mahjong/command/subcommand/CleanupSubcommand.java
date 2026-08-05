package top.ellan.mahjong.command.subcommand;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.command.MahjongCommandContext;
import top.ellan.mahjong.command.MahjongSubcommand;
import top.ellan.mahjong.render.display.ManagedEntityAudit;

/**
 * Removes managed entities whose {@code mahjong:managed_entity} table tag no longer matches
 * any active table. Only entities tagged by this plugin are considered; generic
 * Interaction/ItemDisplay entities (furniture, NPCs, decorations from other plugins) are
 * never touched.
 */
public final class CleanupSubcommand extends AbstractMahjongSubcommand {
    public CleanupSubcommand(MahjongCommandContext context) { super(context); }
    public MahjongSubcommand create() { return this.subcommand("cleanup", true); }
    @Override protected void execute(CommandSender sender, Player player, String[] args) {
        Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("MahjongPaper");
        if (plugin == null) {
            sender.sendMessage("MahjongPaper plugin instance not found.");
            return;
        }
        Set<String> active = new HashSet<>(this.context.tableManager().tableIds());
        int removed = ManagedEntityAudit.cleanupOrphans(plugin, active);
        sender.sendMessage("Removed " + removed + " orphaned mahjong entities (managed by MahjongPaper).");
    }
}
