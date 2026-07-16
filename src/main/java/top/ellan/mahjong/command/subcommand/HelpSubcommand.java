package top.ellan.mahjong.command.subcommand;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.command.MahjongCommandContext;
import top.ellan.mahjong.command.MahjongSubcommand;

public final class HelpSubcommand extends AbstractMahjongSubcommand {
    public HelpSubcommand(MahjongCommandContext context) { super(context); }
    public MahjongSubcommand create() { return this.subcommand("help"); }
    @Override protected void execute(CommandSender sender, Player player, String[] args) {
        if (args.length < 2) {
            this.context.sendHelp(player);
            return;
        }
        this.context.sendHelp(player, this.parsePage(args[1]));
    }

    @Override protected List<String> suggest(Player player, String[] args) {
        return args.length == 2 ? this.context.suggestedHelpPages(player, args[1]) : List.of();
    }

    private int parsePage(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return 1;
        }
    }
}
