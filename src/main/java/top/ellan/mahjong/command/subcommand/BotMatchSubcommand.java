package top.ellan.mahjong.command.subcommand;

import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.command.MahjongCommandContext;
import top.ellan.mahjong.command.MahjongSubcommand;
import top.ellan.mahjong.table.core.MahjongTableSession;

public final class BotMatchSubcommand extends AbstractMahjongSubcommand {
    // Keep in sync with SessionRuleCoordinator.ruleValues("mode") and SessionRulePresetResolver.resolve()
    private static final List<String> PRESETS = List.of(
        "MAJSOUL_HANCHAN", "MAJSOUL_TONPUU",
        "HANCHAN", "TONPUU",
        "GB", "SICHUAN"
    );

    public BotMatchSubcommand(MahjongCommandContext context) { super(context); }
    public MahjongSubcommand create() { return this.subcommand("botmatch", true); }
    @Override protected void execute(CommandSender sender, Player player, String[] args) {
        if (this.context.tableManager().sessionForViewer(player.getUniqueId()) != null) {
            this.context.messages().send(player, "command.botmatch_failed_in_table");
            return;
        }
        String preset = args.length >= 2 ? args[1] : "MAJSOUL_HANCHAN";
        MahjongTableSession table = this.context.tableManager().createBotMatch(player, preset);
        if (table == null) {
            // Placement validation may already have supplied a more specific reason.
            // Keep the command-level rejection as the terminal fallback for callers
            // that reject without reporting one.
            this.context.messages().send(player, "command.botmatch_failed_in_table");
            return;
        }
        this.context.messages().send(
            player,
            "command.botmatch_created",
            this.context.messages().tag("table_id", table.id()),
            this.context.messages().tag("mode", preset.toLowerCase(Locale.ROOT))
        );
        this.context.messages().send(player, "command.botmatch_created_hint");
    }
    @Override protected List<String> suggest(Player player, String[] args) {
        return args.length == 2 ? this.context.matchPrefix(args[1], PRESETS) : List.of();
    }
}
