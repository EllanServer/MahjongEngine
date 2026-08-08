package top.ellan.mahjong.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.ellan.mahjong.command.subcommand.AddBotSubcommand;
import top.ellan.mahjong.command.subcommand.BotMatchSubcommand;
import top.ellan.mahjong.command.subcommand.ChiiSubcommand;
import top.ellan.mahjong.command.subcommand.ClearSubcommand;
import top.ellan.mahjong.command.subcommand.CreateSubcommand;
import top.ellan.mahjong.command.subcommand.CleanupSubcommand;
import top.ellan.mahjong.command.subcommand.DebugSubcommand;
import top.ellan.mahjong.command.subcommand.DeleteTableSubcommand;
import top.ellan.mahjong.command.subcommand.ForceEndSubcommand;
import top.ellan.mahjong.command.subcommand.HelpSubcommand;
import top.ellan.mahjong.command.subcommand.InspectSubcommand;
import top.ellan.mahjong.command.subcommand.JoinSubcommand;
import top.ellan.mahjong.command.subcommand.KanSubcommand;
import top.ellan.mahjong.command.subcommand.KyuushuSubcommand;
import top.ellan.mahjong.command.subcommand.LeaderboardSubcommand;
import top.ellan.mahjong.command.subcommand.LeaveSubcommand;
import top.ellan.mahjong.command.subcommand.ListSubcommand;
import top.ellan.mahjong.command.subcommand.ModeSubcommand;
import top.ellan.mahjong.command.subcommand.RankSubcommand;
import top.ellan.mahjong.command.subcommand.ReloadSubcommand;
import top.ellan.mahjong.command.subcommand.RemoveBotSubcommand;
import top.ellan.mahjong.command.subcommand.RenderSubcommand;
import top.ellan.mahjong.command.subcommand.RiichiSubcommand;
import top.ellan.mahjong.command.subcommand.RoomSubcommand;
import top.ellan.mahjong.command.subcommand.RuleSubcommand;
import top.ellan.mahjong.command.subcommand.RulesSubcommand;
import top.ellan.mahjong.command.subcommand.SettlementSubcommand;
import top.ellan.mahjong.command.subcommand.SimpleReactionSubcommand;
import top.ellan.mahjong.command.subcommand.SpectateSubcommand;
import top.ellan.mahjong.command.subcommand.StartSubcommand;
import top.ellan.mahjong.command.subcommand.StateSubcommand;
import top.ellan.mahjong.command.subcommand.TableSubcommand;
import top.ellan.mahjong.command.subcommand.TsumoSubcommand;
import top.ellan.mahjong.command.subcommand.UnspectateSubcommand;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.rank.DatabasePlayerRankStorage;
import top.ellan.mahjong.rank.PlayerRankStorage;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.gameroom.GameRoomManager;
import top.ellan.mahjong.gameroom.GameRoomSelectionService;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.runtime.AsyncService;
import top.ellan.mahjong.riichi.ReactionType;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.MahjongTableSession;
import top.ellan.mahjong.model.MahjongVariant;
import java.util.function.Supplier;

public final class MahjongCommand implements CommandExecutor, TabCompleter {
    private final MahjongCommandContext context;
    private final Map<String, MahjongSubcommand> subcommands;

    public MahjongCommand(
        MessageService messages,
        MahjongTableManager tableManager,
        DebugService debug,
        AsyncService async,
        ServerScheduler scheduler,
        Supplier<DatabaseService> database,
        Supplier<String> reloadConfiguration,
        Supplier<GameRoomManager> gameRoomManager,
        GameRoomSelectionService selectionService
    ) {
        this(
            messages,
            tableManager,
            debug,
            async,
            scheduler,
            database,
            fallbackRankStorage(database),
            reloadConfiguration,
            gameRoomManager,
            selectionService
        );
    }

    public MahjongCommand(
        MessageService messages,
        MahjongTableManager tableManager,
        DebugService debug,
        AsyncService async,
        ServerScheduler scheduler,
        Supplier<DatabaseService> database,
        Supplier<PlayerRankStorage> playerRankStorage,
        Supplier<String> reloadConfiguration,
        Supplier<GameRoomManager> gameRoomManager,
        GameRoomSelectionService selectionService
    ) {
        this(
            messages,
            tableManager,
            debug,
            async,
            scheduler,
            database,
            playerRankStorage,
            reloadConfiguration,
            gameRoomManager,
            selectionService,
            RulePackCommandHandler.unavailable("architecture runtime not configured")
        );
    }

    public MahjongCommand(
        MessageService messages,
        MahjongTableManager tableManager,
        DebugService debug,
        AsyncService async,
        ServerScheduler scheduler,
        Supplier<DatabaseService> database,
        Supplier<PlayerRankStorage> playerRankStorage,
        Supplier<String> reloadConfiguration,
        Supplier<GameRoomManager> gameRoomManager,
        GameRoomSelectionService selectionService,
        RulePackCommandHandler rulePackCommands
    ) {
        this.context = new MahjongCommandContext(
            messages,
            tableManager,
            debug,
            async,
            scheduler,
            database,
            playerRankStorage,
            reloadConfiguration,
            gameRoomManager,
            selectionService,
            rulePackCommands
        );
        this.subcommands = this.createSubcommands();
    }

    private static Supplier<PlayerRankStorage> fallbackRankStorage(Supplier<DatabaseService> database) {
        PlayerRankStorage storage = new DatabasePlayerRankStorage(database);
        return () -> storage;
    }

    /**
     * Test-only constructor for exercising the dispatch logic with synthetic
     * subcommands. The {@code subcommands} list must be self-consistent (no
     * duplicate names/aliases); see {@link #register(Map, MahjongSubcommand)}
     * for the registration rules used in production.
     */
    MahjongCommand(MahjongCommandContext context, List<MahjongSubcommand> subcommands) {
        this.context = context;
        Map<String, MahjongSubcommand> registry = new LinkedHashMap<>();
        for (MahjongSubcommand command : subcommands) {
            this.register(registry, command);
        }
        this.subcommands = java.util.Collections.unmodifiableMap(registry);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(this.permission())) {
            this.context.messages().send(sender, "command.admin_required");
            return true;
        }
        this.handleCommand(sender, args);
        return true;
    }

    private void handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                this.context.sendHelp(player);
            } else {
                this.context.messages().send(sender, "command.usage");
            }
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        MahjongSubcommand command = this.subcommands.get(sub);
        if (command == null) {
            if (sender instanceof Player player) {
                this.context.debug().log("command", player.getName() + " executed /mahjong " + String.join(" ", args));
                this.context.sendHelp(player);
            } else {
                this.context.messages().send(sender, "common.only_players");
            }
            return;
        }
        if (command.playerOnly() && !(sender instanceof Player)) {
            this.context.messages().send(sender, "common.only_players");
            return;
        }
        if (command.adminOnly() && !this.context.requireAdmin(sender)) {
            return;
        }
        Player player = sender instanceof Player playerSender ? playerSender : null;
        if (player != null && command.playerOnly()) {
            this.context.debug().log("command", player.getName() + " executed /mahjong " + String.join(" ", args));
        }
        command.executor().execute(sender, player, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command bukkitCommand, String alias, String[] args) {
        if (args.length == 0) {
            return this.visibleRootCommands(sender);
        }
        String rootArg = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return this.context.matchPrefix(args[0], this.visibleRootCommands(sender));
        }
        MahjongSubcommand subcommand = this.subcommands.get(rootArg);
        if (subcommand == null || (subcommand.adminOnly() && !sender.hasPermission(MahjongCommandContext.ADMIN_PERMISSION))) {
            return List.of();
        }
        if ("rules".equals(subcommand.name())) {
            return this.context.rulePackCommands().suggestions(args);
        }
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        return subcommand.suggestions().suggest(player, args);
    }

    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(this.permission());
    }

    public String permission() {
        return "mahjongpaper.command";
    }

    private Map<String, MahjongSubcommand> createSubcommands() {
        Map<String, MahjongSubcommand> registry = new LinkedHashMap<>();
        for (MahjongSubcommand command : productionSubcommands(this.context)) {
            this.register(registry, command);
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(registry));
    }

    /**
     * Returns the canonical, ordered list of subcommands wired into the production command.
     * Exposed at package scope so structural tests can audit registration without re-listing
     * subcommand classes by hand. The order matches the on-screen help order.
     */
    static List<MahjongSubcommand> productionSubcommands(MahjongCommandContext context) {
        List<MahjongSubcommand> commands = new ArrayList<>();
        commands.add(new HelpSubcommand(context).create());
        commands.add(new CreateSubcommand(context).create());
        commands.add(new BotMatchSubcommand(context).create());
        commands.add(new ModeSubcommand(context).create());
        commands.add(new JoinSubcommand(context).create());
        commands.add(new LeaveSubcommand(context).create());
        commands.add(new ListSubcommand(context).create());
        commands.add(new SpectateSubcommand(context).create());
        commands.add(new UnspectateSubcommand(context).create());
        commands.add(new TableSubcommand(context).create());
        commands.add(new AddBotSubcommand(context).create());
        commands.add(new RemoveBotSubcommand(context).create());
        commands.add(new RuleSubcommand(context).create());
        commands.add(new RulesSubcommand(context).create());
        commands.add(new StartSubcommand(context).create());
        commands.add(new StateSubcommand(context).create());
        commands.add(new RiichiSubcommand(context).create());
        commands.add(new TsumoSubcommand(context).create());
        commands.add(new SimpleReactionSubcommand(context, "ron", ReactionType.RON, "command.action.ron").create());
        commands.add(new SimpleReactionSubcommand(context, "pon", ReactionType.PON, "command.action.pon").create());
        commands.add(new SimpleReactionSubcommand(context, "minkan", ReactionType.MINKAN, "command.action.minkan").create());
        commands.add(new ChiiSubcommand(context).create());
        commands.add(new KanSubcommand(context).create());
        commands.add(new SimpleReactionSubcommand(context, "skip", ReactionType.SKIP, "command.action.skip").create());
        commands.add(new KyuushuSubcommand(context).create());
        commands.add(new SettlementSubcommand(context).create());
        commands.add(new RankSubcommand(context).create());
        commands.add(new LeaderboardSubcommand(context).create());
        commands.add(new RenderSubcommand(context).create());
        commands.add(new InspectSubcommand(context).create());
        commands.add(new DebugSubcommand(context).create());
        commands.add(new CleanupSubcommand(context).create());
        commands.add(new ClearSubcommand(context).create());
        commands.add(new ForceEndSubcommand(context).create());
        commands.add(new DeleteTableSubcommand(context).create());
        commands.add(new ReloadSubcommand(context).create());
        commands.add(new RoomSubcommand(context).create());
        return List.copyOf(commands);
    }

    private void register(Map<String, MahjongSubcommand> registry, MahjongSubcommand command) {
        registry.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String alias : command.aliases()) {
            registry.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    private List<String> visibleRootCommands(CommandSender sender) {
        List<String> commands = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MahjongSubcommand command : this.subcommands.values()) {
            if (!seen.add(command.name())) {
                continue;
            }
            if (command.adminOnly() && !sender.hasPermission(MahjongCommandContext.ADMIN_PERMISSION)) {
                continue;
            }
            commands.add(command.name());
        }
        if (sender instanceof Player player) {
            MahjongTableSession table = this.context.tableManager().tableFor(player.getUniqueId());
            if (table != null && table.currentVariant() != MahjongVariant.RIICHI) {
                commands.remove("riichi");
                commands.remove("kyuushu");
            }
        }
        return List.copyOf(commands);
    }
}
