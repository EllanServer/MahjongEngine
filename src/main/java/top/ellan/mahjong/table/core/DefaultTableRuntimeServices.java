package top.ellan.mahjong.table.core;

import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.compat.protection.ProtectionService;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.gameroom.GameRoomManager;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.metrics.MetricsCollector;
import top.ellan.mahjong.rank.DatabasePlayerRankStorage;
import top.ellan.mahjong.rank.PlayerRankStorage;
import top.ellan.mahjong.runtime.AsyncService;
import top.ellan.mahjong.runtime.ServerScheduler;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

public final class DefaultTableRuntimeServices implements TableRuntimeServices {
    private final Plugin bukkitPlugin;
    private final MessageService messages;
    private final DebugService debug;
    private final ServerScheduler scheduler;
    private final AsyncService async;
    private final Supplier<PluginSettings> settings;
    private final Supplier<CraftEngineService> craftEngine;
    private final ProtectionService protection;
    private final Supplier<DatabaseService> database;
    private final Supplier<PlayerRankStorage> playerRankStorage;
    private final MetricsCollector metrics;
    private final Supplier<MahjongTableManager> tableManager;
    private final Supplier<GameRoomManager> gameRoomManager;

    public DefaultTableRuntimeServices(
        Plugin bukkitPlugin,
        MessageService messages,
        DebugService debug,
        ServerScheduler scheduler,
        AsyncService async,
        Supplier<PluginSettings> settings,
        Supplier<CraftEngineService> craftEngine,
        ProtectionService protection,
        Supplier<DatabaseService> database,
        MetricsCollector metrics,
        Supplier<MahjongTableManager> tableManager,
        Supplier<GameRoomManager> gameRoomManager
    ) {
        this(
            bukkitPlugin,
            messages,
            debug,
            scheduler,
            async,
            settings,
            craftEngine,
            protection,
            database,
            fallbackRankStorage(database),
            metrics,
            tableManager,
            gameRoomManager
        );
    }

    private static Supplier<PlayerRankStorage> fallbackRankStorage(Supplier<DatabaseService> database) {
        PlayerRankStorage storage = new DatabasePlayerRankStorage(database);
        return () -> storage;
    }

    public DefaultTableRuntimeServices(
        Plugin bukkitPlugin,
        MessageService messages,
        DebugService debug,
        ServerScheduler scheduler,
        AsyncService async,
        Supplier<PluginSettings> settings,
        Supplier<CraftEngineService> craftEngine,
        ProtectionService protection,
        Supplier<DatabaseService> database,
        Supplier<PlayerRankStorage> playerRankStorage,
        MetricsCollector metrics,
        Supplier<MahjongTableManager> tableManager,
        Supplier<GameRoomManager> gameRoomManager
    ) {
        this.bukkitPlugin = Objects.requireNonNull(bukkitPlugin, "bukkitPlugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.debug = Objects.requireNonNull(debug, "debug");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.async = Objects.requireNonNull(async, "async");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.craftEngine = Objects.requireNonNull(craftEngine, "craftEngine");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.database = Objects.requireNonNull(database, "database");
        this.playerRankStorage = Objects.requireNonNull(playerRankStorage, "playerRankStorage");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.tableManager = Objects.requireNonNull(tableManager, "tableManager");
        this.gameRoomManager = gameRoomManager;
    }

    @Override
    public Plugin bukkitPlugin() {
        return this.bukkitPlugin;
    }

    @Override
    public MessageService messages() {
        return this.messages;
    }

    @Override
    public DebugService debug() {
        return this.debug;
    }

    @Override
    public ServerScheduler scheduler() {
        return this.scheduler;
    }

    @Override
    public AsyncService async() {
        return this.async;
    }

    @Override
    public PluginSettings settings() {
        return this.settings.get();
    }

    @Override
    public CraftEngineService craftEngine() {
        return this.craftEngine.get();
    }

    @Override
    public ProtectionService protection() {
        return this.protection;
    }

    @Override
    public DatabaseService database() {
        return this.database.get();
    }

    @Override
    public PlayerRankStorage playerRankStorage() {
        return this.playerRankStorage.get();
    }

    @Override
    public MetricsCollector metrics() {
        return this.metrics;
    }

    @Override
    public MahjongTableManager tableManager() {
        return this.tableManager.get();
    }

    @Override
    public GameRoomManager gameRoomManager() {
        return this.gameRoomManager != null ? this.gameRoomManager.get() : null;
    }
}
