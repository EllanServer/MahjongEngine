package top.ellan.mahjong.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import kotlin.Pair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.db.MahjongSoulRankRules;
import top.ellan.mahjong.rank.DatabasePlayerRankStorage;
import top.ellan.mahjong.rank.PlayerRankStorage;
import top.ellan.mahjong.rank.PlayerRankStorageException;
import top.ellan.mahjong.gameroom.GameRoomManager;
import top.ellan.mahjong.gameroom.GameRoomSelectionService;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.ReactionResponses;
import top.ellan.mahjong.riichi.ReactionType;
import top.ellan.mahjong.riichi.model.MahjongTile;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.MahjongTableSession;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.runtime.AsyncService;
import top.ellan.mahjong.runtime.ServerScheduler;

public final class MahjongCommandContext {
    public static final String ADMIN_PERMISSION = "mahjongpaper.admin";
    private static final int HELP_PAGE_SIZE = 10;
    static final java.util.List<String> HELP_KEY_ORDER = java.util.List.of(
        "command.help.help",
        "command.help.create",
        "command.help.botmatch",
        "command.help.mode",
        "command.help.join",
        "command.help.leave",
        "command.help.list",
        "command.help.spectate",
        "command.help.unspectate",
        "command.help.table",
        "command.help.addbot",
        "command.help.removebot",
        "command.help.rule",
        "command.help.rules",
        "command.help.start",
        "command.help.state",
        "command.help.riichi",
        "command.help.tsumo",
        "command.help.ron",
        "command.help.pon",
        "command.help.minkan",
        "command.help.chii",
        "command.help.kan",
        "command.help.skip",
        "command.help.kyuushu",
        "command.help.settlement",
        "command.help.rank",
        "command.help.leaderboard",
        "command.help.render",
        "command.help.inspect",
        "command.help.clear",
        "command.help.forceend",
        "command.help.deletetable",
        "command.help.reload",
        "command.help.room"
    );
    static final java.util.Set<String> ADMIN_HELP_KEY_SET = java.util.Set.of(
        "command.help.create",
        "command.help.botmatch",
        "command.help.list",
        "command.help.render",
        "command.help.inspect",
        "command.help.clear",
        "command.help.forceend",
        "command.help.deletetable",
        "command.help.reload",
        "command.help.rules",
        "command.help.room"
    );
    private static final String[] HELP_KEYS = HELP_KEY_ORDER.toArray(new String[0]);
    private static final Set<String> ADMIN_HELP_KEYS = ADMIN_HELP_KEY_SET;
    private final MessageService messages;
    private final MahjongTableManager tableManager;
    private final DebugService debug;
    private final AsyncService async;
    private final ServerScheduler scheduler;
    private final Supplier<DatabaseService> database;
    private final Supplier<PlayerRankStorage> playerRankStorage;
    private final Supplier<String> reloadConfiguration;
    private final Supplier<GameRoomManager> gameRoomManager;
    private final GameRoomSelectionService selectionService;
    private final RulePackCommandHandler rulePackCommands;

    public MahjongCommandContext(
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
            selectionService,
            RulePackCommandHandler.unavailable("architecture runtime not configured")
        );
    }

    public MahjongCommandContext(
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

    public MahjongCommandContext(
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
        this.messages = Objects.requireNonNull(messages, "messages");
        this.tableManager = Objects.requireNonNull(tableManager, "tableManager");
        this.debug = Objects.requireNonNull(debug, "debug");
        this.async = Objects.requireNonNull(async, "async");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.database = Objects.requireNonNull(database, "database");
        this.playerRankStorage = Objects.requireNonNull(playerRankStorage, "playerRankStorage");
        this.reloadConfiguration = Objects.requireNonNull(reloadConfiguration, "reloadConfiguration");
        this.gameRoomManager = gameRoomManager;
        this.selectionService = selectionService;
        this.rulePackCommands = Objects.requireNonNull(rulePackCommands, "rulePackCommands");
    }

    private static Supplier<PlayerRankStorage> fallbackRankStorage(Supplier<DatabaseService> database) {
        PlayerRankStorage storage = new DatabasePlayerRankStorage(database);
        return () -> storage;
    }

    public MessageService messages() {
        return this.messages;
    }

    public DebugService debug() {
        return this.debug;
    }

    public MahjongTableManager tableManager() {
        return this.tableManager;
    }

    public RulePackCommandHandler rulePackCommands() {
        return this.rulePackCommands;
    }

    public MahjongTableSession requireTable(Player player) {
        MahjongTableSession table = this.tableManager.tableFor(player.getUniqueId());
        if (table == null) {
            this.messages.send(player, "command.not_in_table");
        }
        return table;
    }

    public MahjongTableSession requireViewedTable(Player player) {
        MahjongTableSession table = this.tableManager.sessionForViewer(player.getUniqueId());
        if (table == null) {
            this.messages.send(player, "command.not_in_table");
        }
        return table;
    }

    public void sendHelp(Player player) {
        this.sendHelp(player, 1);
    }

    public void sendHelp(Player player, int requestedPage) {
        Locale locale = this.messages.resolveLocale(player);
        List<String> visibleKeys = this.visibleHelpKeys(player);
        int pageCount = Math.max(1, (int) Math.ceil((double) visibleKeys.size() / HELP_PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, pageCount));
        int start = (page - 1) * HELP_PAGE_SIZE;
        int end = Math.min(start + HELP_PAGE_SIZE, visibleKeys.size());

        player.sendMessage(this.messages.render(locale, "command.help.header"));
        player.sendMessage(this.messages.render(locale, "command.help.subtitle"));
        player.sendMessage(this.messages.render(
            locale,
            "command.help.page_status",
            this.messages.number(locale, "page", page),
            this.messages.number(locale, "pages", pageCount),
            this.messages.number(locale, "count", visibleKeys.size())
        ));
        for (int index = start; index < end; index++) {
            player.sendMessage(Component.text("  - ", NamedTextColor.DARK_AQUA).append(this.messages.render(locale, visibleKeys.get(index))));
        }
        player.sendMessage(this.helpNavigation(locale, page, pageCount));
        player.sendMessage(this.messages.render(locale, "command.help.footer"));
    }

    public List<String> suggestedHelpPages(Player player, String rawPrefix) {
        int pageCount = this.helpPageCount(player);
        List<String> pages = new ArrayList<>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            pages.add(String.valueOf(page));
        }
        return this.matchPrefix(rawPrefix, pages);
    }

    public void sendReaction(Player player, ReactionType type, Pair<MahjongTile, MahjongTile> pair, String actionKey) {
        MahjongTableSession table = this.requireTable(player);
        if (table == null) {
            return;
        }
        boolean ok = table.react(player.getUniqueId(), ReactionResponses.of(type, pair));
        Locale locale = this.messages.resolveLocale(player);
        String action = this.messages.plain(locale, actionKey);
        this.messages.send(
            player,
            ok ? "command.reaction_submitted" : "command.reaction_failed",
            this.messages.tag("action", action)
        );
    }

    public boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        this.messages.send(sender, "command.admin_required");
        return false;
    }

    public boolean requireTableManager(Player player, MahjongTableSession table) {
        if (player == null || table == null) {
            return false;
        }
        if (player.hasPermission(ADMIN_PERMISSION) || table.isOwner(player.getUniqueId())) {
            return true;
        }
        this.messages.send(player, "command.table_owner_required");
        return false;
    }

    public MahjongTableSession resolvePlayerVisibleTable(Player player, String[] args) {
        if (args.length >= 2) {
            for (MahjongTableSession table : this.tableManager.tables()) {
                if (table.id().equalsIgnoreCase(args[1])) {
                    return table;
                }
            }
            this.messages.send(player, "command.table_not_found", this.messages.tag("table_id", args[1]));
            return null;
        }

        MahjongTableSession table = this.tableManager.sessionForViewer(player.getUniqueId());
        if (table == null) {
            table = this.tableManager.nearestTable(player.getLocation());
        }
        if (table == null) {
            this.messages.send(player, "command.table_panel_no_table");
        }
        return table;
    }

    public void handleReload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            this.messages.send(sender, "command.admin_required");
            return;
        }
        String failure = this.reloadConfiguration.get();
        if (failure == null) {
            this.messages.send(sender, "command.reload_success");
            return;
        }
        this.messages.send(sender, "command.reload_failed", this.messages.tag("reason", failure));
    }

    public MahjongTableSession resolveAdminTable(Player player, String[] args) {
        if (args.length >= 2) {
            for (MahjongTableSession table : this.tableManager.tables()) {
                if (table.id().equalsIgnoreCase(args[1])) {
                    return table;
                }
            }
            this.messages.send(player, "command.table_not_found", this.messages.tag("table_id", args[1]));
            return null;
        }

        MahjongTableSession table = this.tableManager.sessionForViewer(player.getUniqueId());
        if (table == null) {
            table = this.tableManager.nearestTable(player.getLocation());
        }
        if (table == null) {
            this.messages.send(player, "command.admin_table_required");
        }
        return table;
    }

    public java.util.OptionalInt parseIndex(String raw) {
        try {
            return java.util.OptionalInt.of(Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            return java.util.OptionalInt.empty();
        }
    }

    public java.util.Optional<top.ellan.mahjong.model.MahjongTile> parseTile(String raw) {
        String normalized = this.normalizeTileToken(raw);
        if (normalized.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(top.ellan.mahjong.model.MahjongTile.valueOf(normalized));
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    public boolean requireRiichiVariant(Player player, MahjongTableSession table, String actionName) {
        if (player == null || table == null) {
            return false;
        }
        if (table.currentVariant() == MahjongVariant.RIICHI) {
            return true;
        }
        this.messages.send(
            player,
            "command.variant_action_unavailable",
            this.messages.tag("action", actionName),
            this.messages.tag("variant", table.currentVariant().name())
        );
        return false;
    }

    public List<String> suggestedRiichiIndices(Player player) {
        MahjongTableSession table = this.tableManager.tableFor(player.getUniqueId());
        return table == null
            ? List.of()
            : table.suggestedRiichiIndices(player.getUniqueId()).stream().map(String::valueOf).toList();
    }

    public List<String> suggestedKanTiles(Player player) {
        MahjongTableSession table = this.tableManager.tableFor(player.getUniqueId());
        return table == null ? List.of() : table.suggestedKanTiles(player.getUniqueId());
    }

    public List<String> suggestedChiiFirstTiles(Player player) {
        ReactionOptions options = this.currentReactionOptions(player);
        if (options == null || options.getChiiPairs().isEmpty()) {
            return List.of();
        }
        Set<String> firstTiles = new LinkedHashSet<>();
        options.getChiiPairs().forEach(pair -> firstTiles.add(pair.getFirst().name().toLowerCase(Locale.ROOT)));
        return List.copyOf(firstTiles);
    }

    public List<String> suggestedChiiSecondTiles(Player player, String firstArg) {
        ReactionOptions options = this.currentReactionOptions(player);
        if (options == null || options.getChiiPairs().isEmpty()) {
            return List.of();
        }
        String normalizedFirst = firstArg.toUpperCase(Locale.ROOT);
        Set<String> secondTiles = new LinkedHashSet<>();
        options.getChiiPairs().forEach(pair -> {
            if (pair.getFirst().name().equals(normalizedFirst)) {
                secondTiles.add(pair.getSecond().name().toLowerCase(Locale.ROOT));
            }
        });
        return List.copyOf(secondTiles);
    }

    public ReactionOptions currentReactionOptions(Player player) {
        MahjongTableSession table = this.tableManager.tableFor(player.getUniqueId());
        return table == null ? null : table.availableReactions(player.getUniqueId());
    }

    public List<String> matchPrefix(String raw, Collection<String> values) {
        String prefix = raw.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(value);
            }
        }
        return matches;
    }

    public void showRank(Player player) {
        PlayerRankStorage storage = this.playerRankStorage();
        if (storage == null || !storage.rankingEnabled()) {
            this.messages.send(player, "command.rank_unavailable");
            return;
        }
        this.messages.send(player, "command.rank_loading");
        this.async.execute("load-rank-" + player.getUniqueId(), () -> {
            try {
                Map<MahjongVariant, MahjongSoulRankProfile> profiles = storage.loadProfiles(player.getUniqueId(), player.getName());
                this.scheduler.runEntity(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Locale locale = this.messages.resolveLocale(player);
                    for (MahjongVariant mode : MahjongVariant.values()) {
                        MahjongSoulRankProfile profile = profiles.get(mode);
                        this.messages.send(
                            player,
                            "command.rank_summary",
                            this.messages.tag("mode", this.modeLabel(locale, mode)),
                            this.messages.tag("rank", this.rankLabel(locale, profile)),
                            this.messages.tag("points", MahjongSoulRankRules.formatPoints(profile)),
                            this.messages.number(locale, "matches", profile.totalMatches()),
                            this.messages.number(locale, "first", profile.firstPlaces()),
                            this.messages.number(locale, "second", profile.secondPlaces()),
                            this.messages.number(locale, "third", profile.thirdPlaces()),
                            this.messages.number(locale, "fourth", profile.fourthPlaces()),
                            this.messages.tag("avg_place", MahjongSoulRankRules.formatAveragePlace(profile)),
                            this.messages.tag("first_rate", MahjongSoulRankRules.formatFirstRate(profile)),
                            this.messages.tag("top_two_rate", MahjongSoulRankRules.formatTopTwoRate(profile)),
                            this.messages.tag("fourth_rate", MahjongSoulRankRules.formatFourthRate(profile)),
                            this.messages.tag("progress", this.rankProgressLabel(locale, profile))
                        );
                    }
                });
            } catch (PlayerRankStorageException ex) {
                this.scheduler.runEntity(player, () -> {
                    if (player.isOnline()) {
                        String key = switch (ex.reason()) {
                            case SYNC_PENDING -> "command.rank_sync_pending";
                            case CORRUPT_REMOTE_DATA -> "command.rank_sync_corrupt";
                            default -> "command.rank_failed";
                        };
                        this.messages.send(player, key);
                    }
                });
            }
        });
    }

    public void sendMeldLayoutDebug(Player player, MahjongTableSession table, String[] args) {
        TableRenderSnapshot snapshot = table.captureRenderSnapshot(System.nanoTime(), 0L);
        TableRenderPrecomputeResult precompute = table.precomputeRender(snapshot);
        TableRenderLayout.LayoutPlan layout = precompute.layout();

        List<SeatWind> targets = this.debugTargetWinds(player, table, args);
        if (targets.isEmpty()) {
            player.sendMessage(Component.text("[MahjongDebug] no target seat found"));
            return;
        }

        player.sendMessage(Component.text("[MahjongDebug] table=" + table.id() + " seats=" + targets));
        for (SeatWind wind : targets) {
            TableSeatRenderSnapshot seatSnapshot = snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan seatPlan = layout.seat(wind);
            if (seatSnapshot == null || seatPlan == null) {
                player.sendMessage(Component.text("[MahjongDebug] " + wind.name() + " snapshot/plan missing"));
                continue;
            }
            String nameLoc = this.formatPoint(seatPlan.playerNameLocation());
            String labelLoc = this.formatPoint(seatPlan.statusLabelLocation());
            player.sendMessage(Component.text(
                "[MahjongDebug] " + wind.name()
                    + " name=" + seatSnapshot.displayName()
                    + " plate=" + nameLoc
                    + " status=" + labelLoc
                    + " melds=" + seatSnapshot.melds().size()
                    + " placements=" + seatPlan.meldPlacements().size()
            ));

            List<TableRenderLayout.TilePlacement> placements = seatPlan.meldPlacements();
            for (int i = 0; i < placements.size(); i++) {
                TableRenderLayout.TilePlacement placement = placements.get(i);
                player.sendMessage(Component.text(
                    "[MahjongDebug] "
                        + wind.name()
                        + " meld#" + i
                        + " tile=" + placement.tile().name()
                        + " pose=" + placement.pose().name()
                        + " yaw=" + this.formatDecimal(placement.yaw())
                        + " at=" + this.formatPoint(placement.point())
                ));
            }
        }
    }

    private String rankLabel(Locale locale, MahjongSoulRankProfile profile) {
        String tierKey = switch (profile.tier()) {
            case NOVICE -> "rank.tier.novice";
            case ADEPT -> "rank.tier.adept";
            case EXPERT -> "rank.tier.expert";
            case MASTER -> "rank.tier.master";
            case SAINT -> "rank.tier.saint";
            case CELESTIAL -> "rank.tier.celestial";
        };
        return this.messages.plain(locale, tierKey) + " " + profile.level();
    }

    private List<String> visibleHelpKeys(Player player) {
        List<String> keys = new ArrayList<>();
        for (String key : HELP_KEYS) {
            if (ADMIN_HELP_KEYS.contains(key) && !player.hasPermission(ADMIN_PERMISSION)) {
                continue;
            }
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    private int helpPageCount(Player player) {
        return Math.max(1, (int) Math.ceil((double) this.visibleHelpKeys(player).size() / HELP_PAGE_SIZE));
    }

    private Component helpNavigation(Locale locale, int page, int pageCount) {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text("  "));
        builder.append(this.helpPageButton(locale, "command.help.previous", page - 1, page > 1));
        builder.append(Component.text(" "));
        builder.append(this.messages.render(
            locale,
            "command.help.page_compact",
            this.messages.number(locale, "page", page),
            this.messages.number(locale, "pages", pageCount)
        ));
        builder.append(Component.text(" "));
        builder.append(this.helpPageButton(locale, "command.help.next", page + 1, page < pageCount));
        return builder.build();
    }

    private Component helpPageButton(Locale locale, String labelKey, int targetPage, boolean enabled) {
        String label = this.messages.plain(locale, labelKey);
        Component button = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text(label, enabled ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY))
            .append(Component.text("]", NamedTextColor.DARK_GRAY));
        if (!enabled) {
            return button;
        }
        String command = "/mahjong help " + targetPage;
        return button
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    public void showLeaderboard(Player player, MahjongVariant requestedMode) {
        DatabaseService database = this.database();
        if (database == null || !database.rankingEnabled()) {
            this.messages.send(player, "command.rank_unavailable");
            return;
        }
        MahjongVariant mode = requestedMode == null ? MahjongVariant.RIICHI : requestedMode;
        this.messages.send(player, "command.leaderboard_loading");
        this.async.execute("load-leaderboard-" + player.getUniqueId(), () -> {
            try {
                List<DatabaseService.LeaderboardEntry> entries = database.loadLeaderboard(mode, 10);
                this.scheduler.runEntity(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Locale locale = this.messages.resolveLocale(player);
                    this.messages.send(player, "command.leaderboard_header", this.messages.tag("mode", this.modeLabel(locale, mode)));
                    if (entries.isEmpty()) {
                        this.messages.send(player, "command.leaderboard_empty", this.messages.tag("mode", this.modeLabel(locale, mode)));
                        return;
                    }
                    for (DatabaseService.LeaderboardEntry entry : entries) {
                        this.messages.send(
                            player,
                            "command.leaderboard_entry",
                            this.messages.number(locale, "position", entry.position()),
                            this.messages.tag("player", entry.profile().displayName()),
                            this.messages.tag("rank", this.rankLabel(locale, entry.profile())),
                            this.messages.tag("points", MahjongSoulRankRules.formatPoints(entry.profile())),
                            this.messages.number(locale, "matches", entry.profile().totalMatches()),
                            this.messages.tag("avg_place", MahjongSoulRankRules.formatAveragePlace(entry.profile())),
                            this.messages.tag("first_rate", MahjongSoulRankRules.formatFirstRate(entry.profile()))
                        );
                    }
                });
            } catch (java.sql.SQLException ex) {
                this.scheduler.runEntity(player, () -> {
                    if (player.isOnline()) {
                        this.messages.send(player, "command.leaderboard_failed");
                    }
                });
            }
        });
    }

    public MahjongVariant parseMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return null;
        }
        try {
            return MahjongVariant.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public List<String> suggestModes(String rawPrefix) {
        return this.matchPrefix(rawPrefix, java.util.Arrays.stream(MahjongVariant.values()).map(Enum::name).toList());
    }

    private String modeLabel(Locale locale, MahjongVariant mode) {
        MahjongVariant safeMode = mode == null ? MahjongVariant.RIICHI : mode;
        return this.messages.plain(locale, safeMode.translationKey());
    }

    private String rankProgressLabel(Locale locale, MahjongSoulRankProfile profile) {
        if (profile.isCelestial()) {
            return this.messages.plain(
                locale,
                "command.rank_progress_celestial",
                this.messages.tag("remaining", String.format(Locale.ROOT, "%.1f", MahjongSoulRankRules.promotionRemaining(profile) / 10.0D))
            );
        }
        return this.messages.plain(
            locale,
            "command.rank_progress",
            this.messages.number(locale, "remaining", MahjongSoulRankRules.promotionRemaining(profile))
        );
    }

    private List<SeatWind> debugTargetWinds(Player player, MahjongTableSession table, String[] args) {
        if (args.length >= 2) {
            String raw = args[1].toUpperCase(Locale.ROOT);
            if ("ALL".equals(raw)) {
                return List.of(SeatWind.values());
            }
            try {
                return List.of(SeatWind.valueOf(raw));
            } catch (IllegalArgumentException ignored) {
                return List.of();
            }
        }
        SeatWind self = table.seatOf(player.getUniqueId());
        return self == null ? List.of(SeatWind.values()) : List.of(self);
    }

    private String formatPoint(TableRenderLayout.Point point) {
        return this.formatDecimal(point.x()) + "," + this.formatDecimal(point.y()) + "," + this.formatDecimal(point.z());
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String normalizeTileToken(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            top.ellan.mahjong.model.MahjongTile.valueOf(upper);
            return upper;
        } catch (IllegalArgumentException ignored) {
            // Fall through to shorthand parsing below.
        }

        String compact = trimmed.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (compact.length() < 2 || compact.length() > 3) {
            return upper;
        }
        char first = compact.charAt(0);
        char second = compact.charAt(1);
        Character suit = null;
        Character number = null;
        if (this.isSuit(first) && Character.isDigit(second)) {
            suit = first;
            number = second;
        } else if (Character.isDigit(first) && this.isSuit(second)) {
            number = first;
            suit = second;
        }
        if (suit == null || number == null) {
            return upper;
        }
        boolean red = compact.endsWith("r");
        int numeric = number == '0' ? 5 : Character.digit(number, 10);
        if (numeric < 1 || numeric > 9) {
            return upper;
        }
        return ("" + Character.toUpperCase(suit) + numeric) + (red || number == '0' ? "_RED" : "");
    }

    private boolean isSuit(char value) {
        return value == 'm' || value == 'p' || value == 's';
    }

    private DatabaseService database() {
        return this.database.get();
    }

    private PlayerRankStorage playerRankStorage() {
        return this.playerRankStorage.get();
    }

    public GameRoomManager gameRoomManager() {
        return this.gameRoomManager != null ? this.gameRoomManager.get() : null;
    }

    public GameRoomSelectionService selectionService() {
        return this.selectionService;
    }
}
