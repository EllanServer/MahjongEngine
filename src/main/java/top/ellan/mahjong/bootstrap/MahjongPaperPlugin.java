package top.ellan.mahjong.bootstrap;

import top.ellan.mahjong.command.MahjongCommand;
import top.ellan.mahjong.command.RulePackCommandCoordinator;
import top.ellan.mahjong.compat.CraftEngineCompatibility;
import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.compat.protection.ProtectionService;
import top.ellan.mahjong.config.LocalizedConfigResource;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.config.PluginSettingsListener;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.gb.jni.GbNativeWarmupService;
import top.ellan.mahjong.gameroom.GameRoomManager;
import top.ellan.mahjong.gameroom.GameRoomSelectionPreviewService;
import top.ellan.mahjong.gameroom.GameRoomSelectionService;
import top.ellan.mahjong.gameroom.GameRoomWandListener;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.metrics.InMemoryMetricsCollector;
import top.ellan.mahjong.metrics.MetricsCollector;
import top.ellan.mahjong.rank.PlayerRankStorage;
import top.ellan.mahjong.rank.PlayerRankStorageFactory;
import top.ellan.mahjong.render.display.DisplayVisibilityRegistry;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import top.ellan.mahjong.runtime.AsyncService;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.DefaultTableRuntimeServices;
import top.ellan.mahjong.table.core.MahjongTableManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class MahjongPaperPlugin extends JavaPlugin {
    private final MessageService messages = new MessageService();
    private final MetricsCollector metrics = new InMemoryMetricsCollector();
    private final Set<PluginSettingsListener> settingsListeners = ConcurrentHashMap.newKeySet();
    private volatile PluginSettings settings;
    private DebugService debug;
    private AsyncService async;
    private ServerScheduler scheduler;
    private DatabaseService database;
    private PlayerRankStorage playerRankStorage;
    private CraftEngineService craftEngine;
    private ProtectionService protection;
    private MahjongTableManager tableManager;
    private GameRoomManager gameRoomManager;
    private GameRoomSelectionService gameRoomSelectionService;
    private GameRoomSelectionPreviewService gameRoomSelectionPreviewService;
    private MahjongArchitectureRuntime architectureRuntime;
    private CommandMap legacyPaperCommandMap;
    private Command legacyPaperCommand;

    @Override
    public void onEnable() {
        LocalizedConfigResource.saveIfMissing(this, Locale.getDefault());
        this.async = new AsyncService(this.getLogger());
        this.scheduler = new ServerScheduler(this);
        String reloadFailure = this.reloadMahjongConfiguration();
        if (reloadFailure != null) {
            this.getLogger().severe("MahjongPaper failed to load configuration during startup: " + reloadFailure);
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.protection = new ProtectionService(this);
        this.playerRankStorage = PlayerRankStorageFactory.create(
            this,
            this::database,
            () -> this.settings,
            this.scheduler,
            this.getLogger()
        );

        if (this.database != null) {
            this.getLogger().info("Database enabled: " + this.database.databaseType());
            this.debug.log("database", "Database service initialized with type=" + this.database.databaseType());
        }
        this.architectureRuntime = MahjongArchitectureRuntime.start(
            this,
            this.settings,
            this.database,
            this.getLogger()
        );
        this.getLogger().info(
            "Actor architecture ready: eventStore="
                + this.architectureRuntime.status().eventStoreAvailable()
                + ", activeRulePacks="
                + this.architectureRuntime.status().rulePacks()
                    .map(status -> status.loadedActive().size())
                    .orElse(0)
        );
        this.async.execute("gb-native-warmup", () -> GbNativeWarmupService.warmupOnce(this.getLogger()));

        this.tableManager = new MahjongTableManager(new DefaultTableRuntimeServices(
            this,
            this.messages,
            this.debug,
            this.scheduler,
            this.async,
            () -> this.settings,
            () -> this.craftEngine,
            this.protection,
            () -> this.database,
            () -> this.playerRankStorage,
            this.metrics,
            () -> this.tableManager,
            () -> this.gameRoomManager
        ));
        this.craftEngine.enableFurnitureInteractionBridge(this.tableManager);
        this.scheduler.runGlobal(this.craftEngine::cleanupMahjongFurniture);
        this.tableManager.loadPersistentTables();

        this.gameRoomSelectionService = new GameRoomSelectionService();
        this.gameRoomSelectionPreviewService = new GameRoomSelectionPreviewService(this.gameRoomSelectionService, this.scheduler);
        this.gameRoomManager = new GameRoomManager(
            this.tableManager,
            () -> this.debug,
            this.scheduler,
            this.messages,
            () -> this.settings,
            this.gameRoomStoragePath(this.settings)
        );
        this.gameRoomManager.load();

        MahjongCommand mahjongCommand = new MahjongCommand(
            this.messages,
            this.tableManager,
            this.debug,
            this.async,
            this.scheduler,
            this::database,
            () -> this.playerRankStorage,
            this::reloadMahjongConfiguration,
            () -> this.gameRoomManager,
            this.gameRoomSelectionService,
            new RulePackCommandCoordinator(this.architectureRuntime, this.scheduler)
        );
        if (!this.registerMahjongCommand(mahjongCommand)) {
            return;
        }

        this.getServer().getPluginManager().registerEvents(this.tableManager, this);
        this.getServer().getPluginManager().registerEvents(this.tableManager.eventListener(), this);
        this.getServer().getPluginManager().registerEvents(new top.ellan.mahjong.gameroom.GameRoomListener(() -> this.gameRoomManager, this.messages, () -> this.settings), this);
        this.getServer().getPluginManager().registerEvents(new GameRoomWandListener(this.gameRoomSelectionService, this.gameRoomSelectionPreviewService, this.messages, () -> this.settings), this);
        this.scheduler.runGlobal(() -> {
            this.craftEngine.initializeAfterStartup();
            if (this.tableManager != null) {
                this.tableManager.refreshPersistentTablesAfterStartup();
            }
        });
        this.scheduler.runGlobal(this::scanOrphanedEntities);
        this.getLogger().info("MahjongPaper enabled.");
        this.debug.log("lifecycle", "Plugin bootstrap complete.");
    }

    private void scanOrphanedEntities() {
        try {
            java.util.Set<String> active = new java.util.HashSet<>(this.tableManager.tableIds());
            top.ellan.mahjong.render.display.ManagedEntityAudit.EntityReport report =
                top.ellan.mahjong.render.display.ManagedEntityAudit.scan(this, active);
            this.getLogger().info(
                "Managed entity audit: total=" + report.total() + " orphaned=" + report.orphanCount()
                    + " (run /mahjong cleanup to remove orphans)");
        } catch (RuntimeException error) {
            this.getLogger().warning("Managed entity audit failed: " + error.getMessage());
        }
    }

    @Override
    public void onDisable() {
        this.unregisterLegacyPaperCommand();
        if (this.craftEngine != null) {
            this.craftEngine.disableFurnitureInteractionBridge();
            this.craftEngine.clearTrackedCullables();
        }
        if (this.gameRoomManager != null) {
            this.gameRoomManager.shutdown();
        }
        if (this.gameRoomSelectionPreviewService != null) {
            this.gameRoomSelectionPreviewService.cancelAll();
            this.gameRoomSelectionPreviewService = null;
        }
        if (this.tableManager != null) {
            this.tableManager.shutdown();
        }
        if (this.architectureRuntime != null) {
            this.architectureRuntime.close();
            this.architectureRuntime = null;
        }
        if (this.gameRoomManager != null) {
            this.gameRoomManager.save();
            this.gameRoomManager = null;
        }
        TableDisplayRegistry.clear();
        DisplayVisibilityRegistry.clear();
        DisplayInteractionRayRegistry.clear();
        // Close async executor BEFORE the database pool: the async queue may
        // still hold pending persistRoundResult / persistMatchRanks tasks that
        // need a live DataSource. Closing the DB first would cause those tasks
        // to throw SQLException on getConnection(), which AsyncService swallows
        // (see AsyncService.execute error handler) — losing the final round
        // results of any in-progress match. async.close() drains the queue
        // (with a bounded wait) so by the time database.close() runs, no DB
        // task is in flight.
        if (this.async != null) {
            this.async.close();
            this.async = null;
        }
        if (this.playerRankStorage != null) {
            this.playerRankStorage.close();
            this.playerRankStorage = null;
        }
        if (this.database != null) {
            this.database.close();
            this.database = null;
        }
        this.scheduler = null;
        this.craftEngine = null;
        this.protection = null;
        if (this.debug != null) {
            this.debug.log("lifecycle", "Plugin shutdown complete.");
        }
    }

    private void handleDatabaseStartupFailure(DatabaseService.InitializationException ex) {
        Throwable rootCause = Objects.requireNonNullElse(ex.rootCause(), ex);
        this.getLogger().severe("Database initialization failed. MahjongPaper will continue with persistence disabled.");
        this.getLogger().severe("Reason: " + ex.userFacingReason());
        this.getLogger().severe("Detail: " + rootCause.getClass().getSimpleName() + ": " + Objects.toString(rootCause.getMessage(), "(no additional detail)"));
        this.getLogger().severe("Hint: use database.connection.type=h2 for local storage, or start MariaDB/MySQL and verify the configured connection settings.");
        if (this.debug != null && this.debug.isCategoryEnabled("database")) {
            this.getLogger().log(Level.SEVERE, "Database startup stack trace:", ex);
        }
    }

    private boolean registerMahjongCommand(MahjongCommand mahjongCommand) {
        PaperCommandRegistrationResult paperResult = this.registerPaperCommand(mahjongCommand);
        if (paperResult == PaperCommandRegistrationResult.REGISTERED) {
            return true;
        }
        if (paperResult == PaperCommandRegistrationResult.FAILED) {
            this.getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        PluginCommand command = this.getCommand("mahjong");
        if (command == null) {
            if (this.registerLegacyPaperCommand(mahjongCommand)) {
                return true;
            }
            this.getLogger().severe(
                "MahjongPaper could not register its command through Paper lifecycle events, plugin.yml, or the legacy Paper command map; disabling plugin."
            );
            this.getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        command.setExecutor(mahjongCommand);
        command.setTabCompleter(mahjongCommand);
        return true;
    }

    private boolean registerLegacyPaperCommand(MahjongCommand delegate) {
        CommandMap commandMap = this.getServer().getCommandMap();
        LegacyPaperMahjongCommand command = new LegacyPaperMahjongCommand(this, delegate);
        boolean registeredPrimaryLabel = commandMap.register("mahjong", "mahjongpaper", command);
        if (!registeredPrimaryLabel) {
            removeLegacyPaperCommand(commandMap, command);
            return false;
        }
        this.legacyPaperCommandMap = commandMap;
        this.legacyPaperCommand = command;
        return true;
    }

    private void unregisterLegacyPaperCommand() {
        CommandMap commandMap = this.legacyPaperCommandMap;
        Command command = this.legacyPaperCommand;
        this.legacyPaperCommandMap = null;
        this.legacyPaperCommand = null;
        if (commandMap != null && command != null) {
            removeLegacyPaperCommand(commandMap, command);
        }
    }

    private static void removeLegacyPaperCommand(CommandMap commandMap, Command command) {
        commandMap.getKnownCommands().entrySet().removeIf(entry -> entry.getValue() == command);
        command.unregister(commandMap);
    }

    private PaperCommandRegistrationResult registerPaperCommand(MahjongCommand mahjongCommand) {
        try {
            Class<?> lifecycleEventsClass = Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents");
            Class<?> lifecycleEventTypeClass = Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType");
            Class<?> lifecycleEventHandlerClass = Class.forName("io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler");
            Class<?> lifecycleEventManagerClass = Class.forName("io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager");
            Object commandsEventType = lifecycleEventsClass.getField("COMMANDS").get(null);
            Object lifecycleManager = this.getClass().getMethod("getLifecycleManager").invoke(this);
            Object handler = Proxy.newProxyInstance(
                lifecycleEventHandlerClass.getClassLoader(),
                new Class<?>[] { lifecycleEventHandlerClass },
                (proxy, method, args) -> {
                    if ("run".equals(method.getName()) && args != null && args.length == 1) {
                        this.registerPaperCommandOnEvent(args[0], mahjongCommand);
                    }
                    return null;
                }
            );

            Method registerEventHandler = lifecycleEventManagerClass.getMethod(
                "registerEventHandler",
                lifecycleEventTypeClass,
                lifecycleEventHandlerClass
            );
            registerEventHandler.invoke(lifecycleManager, commandsEventType, handler);
            return PaperCommandRegistrationResult.REGISTERED;
        } catch (ClassNotFoundException | NoSuchMethodException ex) {
            return PaperCommandRegistrationResult.UNAVAILABLE;
        } catch (IllegalAccessException | InvocationTargetException | NoSuchFieldException ex) {
            this.getLogger().log(Level.SEVERE, "Failed to register MahjongPaper command through Paper lifecycle events.", ex);
            return PaperCommandRegistrationResult.FAILED;
        }
    }

    private void registerPaperCommandOnEvent(Object event, MahjongCommand mahjongCommand) throws ReflectiveOperationException {
        Object registrar = event.getClass().getMethod("registrar").invoke(event);
        Class<?> commandsClass = Class.forName("io.papermc.paper.command.brigadier.Commands");
        Class<?> basicCommandClass = Class.forName("io.papermc.paper.command.brigadier.BasicCommand");
        Object basicCommand = Proxy.newProxyInstance(
            basicCommandClass.getClassLoader(),
            new Class<?>[] { basicCommandClass },
            (proxy, method, args) -> this.invokePaperBasicCommand(proxy, mahjongCommand, method, args)
        );
        Method register = commandsClass.getMethod(
            "register",
            String.class,
            String.class,
            Collection.class,
            basicCommandClass
        );
        register.invoke(
            registrar,
            "mahjong",
            "Manage MahjongPaper tables and rounds. Use /mahjong help for command explanations.",
            java.util.List.of(),
            basicCommand
        );
    }

    private Object invokePaperBasicCommand(Object proxy, MahjongCommand mahjongCommand, Method method, Object[] args) throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "execute" -> {
                mahjongCommand.onCommand(this.paperCommandSender(args), null, "mahjong", (String[]) args[1]);
                yield null;
            }
            case "suggest" -> mahjongCommand.onTabComplete(this.paperCommandSender(args), null, "mahjong", (String[]) args[1]);
            case "canUse" -> mahjongCommand.canUse((org.bukkit.command.CommandSender) args[0]);
            case "permission" -> mahjongCommand.permission();
            case "toString" -> "MahjongPaperBasicCommand";
            case "hashCode" -> System.identityHashCode(mahjongCommand);
            case "equals" -> args != null && args.length == 1 && args[0] == proxy;
            default -> null;
        };
    }

    private org.bukkit.command.CommandSender paperCommandSender(Object[] args) throws ReflectiveOperationException {
        Object sourceStack = args[0];
        return (org.bukkit.command.CommandSender) sourceStack.getClass().getMethod("getSender").invoke(sourceStack);
    }

    private enum PaperCommandRegistrationResult {
        REGISTERED,
        UNAVAILABLE,
        FAILED
    }

    /** Command-map adapter for Paper versions that support paper-plugin.yml but predate lifecycle commands. */
    private static final class LegacyPaperMahjongCommand extends Command implements PluginIdentifiableCommand {
        private final MahjongPaperPlugin plugin;
        private final MahjongCommand delegate;

        private LegacyPaperMahjongCommand(MahjongPaperPlugin plugin, MahjongCommand delegate) {
            super(
                "mahjong",
                "Manage MahjongPaper tables and rounds. Use /mahjong help for command explanations.",
                "/mahjong <help|create|botmatch|mode|join|leave|list|spectate|unspectate|table|addbot|removebot|rule|start|state|riichi|tsumo|ron|pon|minkan|chii|kan|skip|kyuushu|settlement|rank|leaderboard|render|inspect|clear|forceend|deletetable|reload>",
                java.util.List.of()
            );
            this.plugin = plugin;
            this.delegate = delegate;
            this.setPermission(delegate.permission());
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return this.delegate.onCommand(sender, this, commandLabel, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            List<String> suggestions = this.delegate.onTabComplete(sender, this, alias, args);
            return suggestions == null ? List.of() : suggestions;
        }

        @Override
        public Plugin getPlugin() {
            return this.plugin;
        }
    }

    public String reloadMahjongConfiguration() {
        PluginSettings previousSettings = this.settings;
        PluginSettings reloadedSettings;
        try {
            reloadedSettings = PluginSettings.load(this.getDataFolder().toPath().resolve("config.yml"));
        } catch (IOException | RuntimeException exception) {
            this.getLogger().log(Level.SEVERE, "Failed to load config.yml with Sparrow YAML.", exception);
            return "config.yml could not be parsed: " + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName());
        }
        if (this.architectureRuntime != null
            && previousSettings != null
            && (!Objects.equals(previousSettings.database(), reloadedSettings.database())
                || !Objects.equals(previousSettings.rules(), reloadedSettings.rules()))) {
            return "Database and signed rule-pack settings are restart-scoped while the actor runtime is active.";
        }
        CraftEngineCompatibility.Result craftEngineCompatibility =
            CraftEngineCompatibility.inspect(this.getServer().getPluginManager());
        if (!craftEngineCompatibility.compatible()) {
            return craftEngineCompatibility.failureMessage();
        }

        DebugService reloadedDebug = new DebugService(this.getLogger(), reloadedSettings.debug());
        CraftEngineService reloadedCraftEngine;
        try {
            reloadedCraftEngine = new CraftEngineService(
                this,
                this.scheduler,
                this.async,
                reloadedDebug,
                this.messages,
                () -> reloadedSettings,
                reloadedSettings.craftEngine()
            );
        } catch (LinkageError error) {
            String failure = craftEngineCompatibility.directBridgeFailure(error);
            this.getLogger().log(Level.SEVERE, failure, error);
            return failure;
        }
        boolean preserveArchitectureDatabase = this.architectureRuntime != null;
        DatabaseService reloadedDatabase = preserveArchitectureDatabase ? this.database : null;
        if (!preserveArchitectureDatabase && DatabaseService.isEnabled(reloadedSettings.database())) {
            try {
                reloadedDatabase = new DatabaseService(
                    reloadedSettings.database(),
                    reloadedDebug,
                    this.async,
                    this.getLogger(),
                    this.getDataFolder().toPath(),
                    reloadedSettings.rankingEnabled(),
                    reloadedSettings.rankingEastRoom(),
                    reloadedSettings.rankingSouthRoom()
                );
            } catch (DatabaseService.InitializationException ex) {
                this.handleDatabaseStartupFailure(ex);
                if (reloadedSettings.databaseFailOnError()) {
                    return ex.userFacingReason();
                }
            }
        }

        CraftEngineService previousCraftEngine = this.craftEngine;
        DatabaseService previousDatabase = this.database;
        // Wait for every operation that captured the old DatabaseService,
        // including retries currently sleeping in backoff. A timed-out reload
        // is aborted instead of closing a pool that may still be in use and
        // silently losing the corresponding round/rank write.
        if (previousDatabase != reloadedDatabase
            && previousDatabase != null
            && this.async != null
            && !this.async.awaitQuiescence(5L)) {
            if (reloadedDatabase != null) {
                reloadedDatabase.close();
            }
            String failure = "Timed out waiting for pending persistence operations; configuration reload was cancelled.";
            this.getLogger().warning(failure);
            return failure;
        }
        if (this.tableManager != null) {
            this.tableManager.overheadViews().closeAll();
        }
        if (previousCraftEngine != null) {
            previousCraftEngine.disableFurnitureInteractionBridge();
            previousCraftEngine.clearTrackedCullables();
        }
        if (previousDatabase != null && previousDatabase != reloadedDatabase) {
            previousDatabase.close();
        }

        // Clear static display registries before respawning entities below.
        // TableDisplayRegistry and DisplayVisibilityRegistry are static maps
        // keyed by entity ID; on reload, every table calls clearDisplays() +
        // render() (see end of this method), which spawns a fresh set of
        // display entities with potentially recycled entity IDs. Without
        // clearing the registries here, a new entity can inherit the
        // visibility flags (private viewers, hidden viewers) of a deleted
        // entity that happened to share the same ID, causing the new entity
        // to be incorrectly hidden or shown to the wrong player. onDisable
        // already clears these; reload is the missing symmetric path.
        TableDisplayRegistry.clear();
        DisplayVisibilityRegistry.clear();
        DisplayInteractionRayRegistry.clear();
        // Also clear the static tile item cache: a resourcepack update or a
        // CraftEngine custom-item config change (both possible via reload)
        // would otherwise leave stale ItemStacks cached under the same path
        // key, and tables re-rendered below would keep showing old textures.
        DisplayEntities.clearCaches();

        this.settings = reloadedSettings;
        this.debug = reloadedDebug;
        this.database = reloadedDatabase;
        this.craftEngine = reloadedCraftEngine;
        if (this.gameRoomManager != null) {
            this.gameRoomManager.refreshConfiguration(this.gameRoomStoragePath(reloadedSettings));
        }
        if (this.gameRoomSelectionService != null && !reloadedSettings.gameRooms().enabled()) {
            this.gameRoomSelectionService.clearAll();
        }
        if (this.gameRoomSelectionPreviewService != null && !reloadedSettings.gameRooms().enabled()) {
            this.gameRoomSelectionPreviewService.cancelAll();
        }
        this.debug.log("lifecycle", "Debug logging enabled.");
        this.notifySettingsListeners(previousSettings, reloadedSettings);

        if (this.tableManager != null) {
            this.craftEngine.enableFurnitureInteractionBridge(this.tableManager);
            this.craftEngine.initializeAfterStartup();
            this.getServer().getOnlinePlayers().forEach(player -> this.scheduler.runEntity(player, () -> this.craftEngine.syncTrackedEntitiesFor(player)));
            this.tableManager.tables().forEach(table -> this.scheduler.runRegion(table.center(), () -> {
                table.clearDisplays();
                table.render();
            }));
        }
        return null;
    }

    public void addSettingsListener(PluginSettingsListener listener) {
        if (listener != null) {
            this.settingsListeners.add(listener);
        }
    }

    public void removeSettingsListener(PluginSettingsListener listener) {
        if (listener != null) {
            this.settingsListeners.remove(listener);
        }
    }

    private void notifySettingsListeners(PluginSettings previousSettings, PluginSettings currentSettings) {
        if (previousSettings == null) {
            return;
        }
        for (PluginSettingsListener listener : this.settingsListeners) {
            try {
                listener.onSettingsChanged(previousSettings, currentSettings);
            } catch (RuntimeException exception) {
                this.getLogger().log(Level.WARNING, "A PluginSettingsListener callback failed.", exception);
            }
        }
    }

    public MessageService messages() {
        return this.messages;
    }

    public MetricsCollector metrics() {
        return this.metrics;
    }

    public PluginSettings settings() {
        return this.settings;
    }

    public DatabaseService database() {
        return this.database;
    }

    public PlayerRankStorage playerRankStorage() {
        return this.playerRankStorage;
    }

    public DebugService debug() {
        return this.debug;
    }

    public AsyncService async() {
        return this.async;
    }

    public ServerScheduler scheduler() {
        return this.scheduler;
    }

    public CraftEngineService craftEngine() {
        return this.craftEngine;
    }

    public MahjongTableManager tableManager() {
        return this.tableManager;
    }

    public GameRoomManager gameRoomManager() {
        return this.gameRoomManager;
    }

    private java.nio.file.Path gameRoomStoragePath(PluginSettings settings) {
        PluginSettings effectiveSettings = settings != null ? settings : this.settings;
        String file = effectiveSettings == null ? "game-rooms.yml" : effectiveSettings.gameRooms().file();
        return this.getDataFolder().toPath().resolve(file);
    }
}
