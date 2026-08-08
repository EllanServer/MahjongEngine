package top.ellan.mahjong.command.subcommand;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.command.MahjongCommandContext;
import top.ellan.mahjong.command.MahjongSubcommand;

public final class RulesSubcommand extends AbstractMahjongSubcommand {
    public RulesSubcommand(MahjongCommandContext context) {
        super(context);
    }

    public MahjongSubcommand create() {
        return this.subcommand("rules", true, false);
    }

    @Override
    protected void execute(CommandSender sender, Player player, String[] args) {
        this.context.rulePackCommands().execute(sender, args);
    }

    @Override
    protected List<String> suggest(Player player, String[] args) {
        return this.context.rulePackCommands().suggestions(args);
    }
}
