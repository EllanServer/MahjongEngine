package top.ellan.mahjong.plugin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.application.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.FairRuleExecutor;
import top.ellan.mahjong.application.InteractionRouter;
import top.ellan.mahjong.application.TableActorRegistry;
import top.ellan.mahjong.craftengine.CraftEngineBackendConfig;
import top.ellan.mahjong.craftengine.CraftEngineBundleInstaller;
import top.ellan.mahjong.craftengine.CraftEngineInteractionListener;
import top.ellan.mahjong.craftengine.CraftEngineReloadListener;
import top.ellan.mahjong.craftengine.CraftEngineSceneBackend;
import top.ellan.mahjong.craftengine.CraftEngineVersion;
import top.ellan.mahjong.craftengine.DirectCraftEngineMutationGateway;
import top.ellan.mahjong.craftengine.SparrowPrivateProjectionGateway;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.persistence.sql.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.JdbcTableAnchorRepository;
import top.ellan.mahjong.persistence.sql.MatchInstanceRecord;
import top.ellan.mahjong.persistence.sql.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.SqlSchemaMigrator;
import top.ellan.mahjong.persistence.sql.StoredTableAnchor;
import top.ellan.mahjong.platform.paper.BoundedPlatformExecutors;
import top.ellan.mahjong.platform.paper.PaperRegionScheduler;
import top.ellan.mahjong.platform.paper.PaperTableAnchorRegistry;
import top.ellan.mahjong.presentation.DefaultTableSceneMapper;
import top.ellan.mahjong.presentation.LatestSceneProjector;
import top.ellan.mahjong.presentation.RadialTableLayout;
import top.ellan.mahjong.presentation.SceneGraphDiffer;
import top.ellan.mahjong.runtime.EmbeddedRuleTrustRoot;
import top.ellan.mahjong.runtime.HttpArtifactDownloader;
import top.ellan.mahjong.runtime.HttpRegistrySource;
import top.ellan.mahjong.runtime.OfficialTrustRoot;
import top.ellan.mahjong.runtime.RuleActivationStore;
import top.ellan.mahjong.runtime.RulePackAdminService;
import top.ellan.mahjong.runtime.RulePackException;
import top.ellan.mahjong.runtime.RulePackGarbageCollector;
import top.ellan.mahjong.runtime.RulePackInstaller;
import top.ellan.mahjong.runtime.RulePackInventory;
import top.ellan.mahjong.runtime.RulePackInventoryReader;
import top.ellan.mahjong.runtime.RulePackLoader;
import top.ellan.mahjong.runtime.RulePackPaths;
import top.ellan.mahjong.runtime.RulePackRuntime;
import top.ellan.mahjong.runtime.RulePackVerification;
import top.ellan.mahjong.spi.RuleId;

/** Restart-scoped 2.0 composition root. No legacy gameplay component is reachable from here. */
public final class MahjongRuntime implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final MahjongPaperPlugin plugin;
    private final PluginConfiguration configuration;
    private final BoundedPlatformExecutors executors;
    private final FairRuleExecutor ruleExecutor;
    private final BoundedDeadlineScheduler deadlines;
    private final TableActorRegistry actors = new TableActorRegistry();
    private final InteractionRouter interactions = new InteractionRouter(actors);
    private final PaperTableAnchorRegistry paperAnchors = new PaperTableAnchorRegistry();
    private final LiveTableDirectory liveTables = new LiveTableDirectory();
    private final SparrowPrivateProjectionGateway privateProjection;
    private final CraftEngineSceneBackend sceneBackend;
    private final LatestSceneProjector sceneProjector;
    private final AtomicReference<Services> services = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private final AtomicReference<String> detail = new AtomicReference<>("initializing");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> started = new CompletableFuture<>();

    public MahjongRuntime(MahjongPaperPlugin plugin, PluginConfiguration configuration) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        executors = new BoundedPlatformExecutors(processors);
        ruleExecutor =
                new FairRuleExecutor(
                        Math.max(2, Math.min(processors, 8)), 1_024, "mahjong-rule");
        deadlines =
                new BoundedDeadlineScheduler(8_192, executors.actor(), "mahjong-deadline");

        Plugin craftEngine = requireCraftEngine();
        privateProjection = new SparrowPrivateProjectionGateway(plugin, paperAnchors);
        DirectCraftEngineMutationGateway mutations =
                new DirectCraftEngineMutationGateway(
                        plugin,
                        paperAnchors,
                        privateProjection,
                        configuration.interactionFurniture());
        sceneBackend =
                new CraftEngineSceneBackend(
                        mutations,
                        new PaperRegionScheduler(plugin),
                        paperAnchors,
                        interactions,
                        CraftEngineBackendConfig.DEFAULT,
                        failure ->
                                plugin.getLogger()
                                        .warning(
                                                "CraftEngine table failure "
                                                        + failure.tableId()
                                                        + ": "
                                                        + failure.reason()));
        sceneProjector =
                new LatestSceneProjector(
                        executors.render(),
                        new DefaultTableSceneMapper(
                                new RadialTableLayout(0.09D),
                                configuration.tileBackFurniture()),
                        sceneBackend,
                        new SceneGraphDiffer());
        registerPlatformListeners(mutations);
        installCraftEngineBundle(craftEngine);
        try {
            if (CraftEngineItems.byId("mahjongpaper:table_visual") != null) {
                sceneBackend.onCraftEngineReloaded();
            }
        } catch (RuntimeException notLoadedYet) {
            plugin.getLogger().fine("CraftEngine assets are not loaded yet");
        }
    }

    public void start() {
        CompletableFuture.runAsync(this::initializeServices, executors.io())
                .whenComplete(
                        (ignored, failure) -> {
                            if (failure == null) {
                                started.complete(null);
                                return;
                            }
                            Throwable cause = unwrap(failure);
                            state.set(State.FAILED);
                            detail.set(safeMessage(cause));
                            plugin.getLogger()
                                    .log(Level.SEVERE, "MahjongPaper 2.0 initialization failed", cause);
                            started.completeExceptionally(cause);
                        });
    }

    public CompletionStage<Void> started() {
        return started;
    }

    public String status() {
        Services current = services.get();
        String suffix = current == null ? "" : ", tables=" + liveTables.list().size();
        return state.get() + ": " + detail.get() + suffix;
    }

    public LiveTableDirectory liveTables() {
        return liveTables;
    }

    public CompletionStage<StartedRulePackMatch> create(
            NewRulePackMatch command, Location paperAnchor) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(paperAnchor, "paperAnchor");
        Services current = requireServices();
        RulePackMatchCoordinator coordinator =
                current.coordinator()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Database or rule runtime is unavailable"));
        if (!liveTables.reserve(command.tableId(), command.participants())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A participant already belongs to a live table"));
        }
        paperAnchors.register(command.tableId(), paperAnchor);
        CompletionStage<StartedRulePackMatch> creation =
                CompletableFuture.supplyAsync(() -> coordinator.create(command), executors.actor())
                        .thenCompose(value -> value);
        return creation.whenComplete(
                (startedMatch, failure) -> {
                    if (failure == null) {
                        liveTables.completeReservation(startedMatch);
                    } else {
                        liveTables.releaseReservation(command.tableId());
                        paperAnchors.remove(command.tableId());
                    }
                });
    }

    public CompletionStage<Void> remove(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        Services current = requireServices();
        StartedRulePackMatch match =
                liveTables
                        .remove(tableId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown live table"));
        actors.remove(tableId, match.actor());
        sceneProjector.remove(tableId);
        sceneBackend.removeTable(tableId);
        return match.actor()
                .closeAndDrain()
                .thenCompose(
                        ignored ->
                                CompletableFuture.runAsync(
                                        () -> {
                                            try {
                                                if (current.matches().isPresent()) {
                                                    current.matches()
                                                            .orElseThrow()
                                                            .updateStatus(
                                                                    match.binding().matchId(),
                                                                    TableLifecycle.CLOSED,
                                                                    Instant.now(Clock.systemUTC()));
                                                }
                                                if (current.anchorRepository().isPresent()) {
                                                    current.anchorRepository()
                                                            .orElseThrow()
                                                            .delete(tableId);
                                                }
                                            } catch (SQLException failure) {
                                                throw new CompletionException(failure);
                                            }
                                        },
                                        executors.io()));
    }

    public CompletionStage<RulePackInventory> listRules() {
        Services current = requireServices();
        return submitIo(current.inventory()::read);
    }

    public CompletionStage<?> installRule(RuleId ruleId, Optional<String> version) {
        RulePackAdminService admin = requireAdmin();
        return submitIo(() -> admin.install(ruleId, version));
    }

    public CompletionStage<List<RulePackVerification>> verifyRules(Optional<RuleId> ruleId) {
        RulePackAdminService admin = requireAdmin();
        return submitIo(() -> admin.verify(ruleId));
    }

    public CompletionStage<?> activateRule(RuleId ruleId, String version) {
        RulePackAdminService admin = requireAdmin();
        return submitIo(() -> admin.activate(ruleId, version));
    }

    public CompletionStage<?> collectRuleGarbage() {
        RulePackAdminService admin = requireAdmin();
        return submitIo(admin::collectGarbage);
    }

    private void initializeServices() {
        RuleServices rules = initializeRules();
        DatabaseServices database = initializeDatabase();
        Optional<RulePackMatchCoordinator> coordinator = Optional.empty();
        if (rules.runtime().isPresent() && database.matches().isPresent()) {
            coordinator =
                    Optional.of(
                            new RulePackMatchCoordinator(
                                    executors.actor(),
                                    executors.io(),
                                    ruleExecutor,
                                    deadlines,
                                    actors,
                                    database.matches().orElseThrow(),
                                    database.events().orElseThrow(),
                                    rules.runtime().orElseThrow(),
                                    sceneProjector,
                                    Clock.systemUTC()));
        }
        Services initialized =
                new Services(
                        database.dataSource(),
                        database.matches(),
                        database.anchors(),
                        database.events(),
                        rules.runtime(),
                        rules.admin(),
                        rules.inventory(),
                        coordinator);
        services.set(initialized);
        if (coordinator.isPresent()) {
            recoverMatches(initialized, coordinator.orElseThrow()).toCompletableFuture().join();
        } else if (database.matches().isPresent()) {
            blockAllRecoverableMatches(database.matches().orElseThrow());
        }
        if (coordinator.isPresent()) {
            state.set(State.READY);
            detail.set("rule-pack-only runtime ready");
        } else {
            state.set(State.DEGRADED);
            detail.set("lobby/admin only; active matches are fail-closed");
        }
    }

    private RuleServices initializeRules() {
        RulePackPaths paths =
                new RulePackPaths(plugin.getDataFolder().toPath().resolve("rules"));
        RulePackLoader loader = new RulePackLoader(plugin.getPluginMeta().getVersion());
        RuleActivationStore activation = new RuleActivationStore(paths.activationState());
        RulePackInventoryReader inventory = new RulePackInventoryReader(paths, activation);
        RulePackRuntime runtime = new RulePackRuntime(paths, loader, activation);
        Optional<RulePackRuntime> running = Optional.empty();
        try {
            runtime.start();
            running = Optional.of(runtime);
        } catch (IOException | RulePackException failure) {
            closeRuleRuntime(runtime);
            plugin.getLogger().log(Level.SEVERE, "Rule-pack runtime failed closed", failure);
        }

        Optional<RulePackAdminService> admin = Optional.empty();
        if (!configuration.registryUrl().isBlank()) {
            try {
                URI registryUri = URI.create(configuration.registryUrl());
                if (!"https".equalsIgnoreCase(registryUri.getScheme())) {
                    throw new IllegalArgumentException("rules.registry-url must use HTTPS");
                }
                OfficialTrustRoot trustRoot =
                        EmbeddedRuleTrustRoot.load(MahjongRuntime.class.getClassLoader());
                HttpClient client =
                        HttpClient.newBuilder()
                                .executor(executors.io())
                                .connectTimeout(Duration.ofSeconds(10))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build();
                HttpRegistrySource registry = new HttpRegistrySource(client, registryUri);
                RulePackInstaller installer =
                        new RulePackInstaller(
                                paths,
                                registry,
                                new HttpArtifactDownloader(client),
                                trustRoot,
                                loader,
                                Clock.systemUTC());
                RulePackGarbageCollector garbageCollector =
                        new RulePackGarbageCollector(
                                paths,
                                () -> {
                                    Services current = services.get();
                                    if (current == null || current.matches().isEmpty()) {
                                        throw new IllegalStateException(
                                                "Database is unavailable; GC cannot prove safety");
                                    }
                                    return current.matches().orElseThrow().referencedRulePacks();
                                },
                                activation,
                                Clock.systemUTC());
                admin =
                        Optional.of(
                                new RulePackAdminService(
                                        paths,
                                        registry,
                                        trustRoot,
                                        loader,
                                        installer,
                                        activation,
                                        garbageCollector));
            } catch (IOException | RulePackException | IllegalArgumentException failure) {
                plugin.getLogger().log(Level.WARNING, "Rule-pack administration disabled", failure);
            }
        }
        return new RuleServices(running, admin, inventory);
    }

    private DatabaseServices initializeDatabase() {
        HikariDataSource dataSource = null;
        try {
            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("MahjongPaper-SQL");
            hikari.setJdbcUrl(configuration.database().jdbcUrl());
            hikari.setUsername(configuration.database().username());
            hikari.setPassword(configuration.database().password());
            hikari.setMaximumPoolSize(configuration.database().maximumPoolSize());
            hikari.setMinimumIdle(0);
            hikari.setConnectionTimeout(2_000L);
            hikari.setValidationTimeout(1_000L);
            hikari.setInitializationFailTimeout(-1L);
            dataSource = new HikariDataSource(hikari);
            HikariDataSource opened = dataSource;
            SqlConnectionFactory connections = opened::getConnection;
            new SqlSchemaMigrator(connections).migrate();
            JdbcEventStore events = new JdbcEventStore(connections, executors.io());
            if (!events.probe()) {
                throw new SQLException("Database probe failed");
            }
            return new DatabaseServices(
                    Optional.of(opened),
                    Optional.of(new JdbcMatchRepository(connections)),
                    Optional.of(new JdbcTableAnchorRepository(connections)),
                    Optional.of(events));
        } catch (RuntimeException | SQLException failure) {
            if (dataSource != null) {
                dataSource.close();
            }
            plugin.getLogger()
                    .log(
                            Level.SEVERE,
                            "Database unavailable; matches cannot start or advance",
                            failure);
            return new DatabaseServices(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    private CompletionStage<Void> recoverMatches(
            Services initialized, RulePackMatchCoordinator coordinator) {
        JdbcMatchRepository matches = initialized.matches().orElseThrow();
        JdbcTableAnchorRepository anchors = initialized.anchorRepository().orElseThrow();
        List<MatchInstanceRecord> recoverable;
        Map<TableId, StoredTableAnchor> anchorsByTable = new HashMap<>();
        try {
            recoverable = matches.recoverableMatches();
            anchors.list().forEach(value -> anchorsByTable.put(value.tableId(), value));
        } catch (SQLException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        List<CompletableFuture<Void>> recoveries =
                recoverable.stream()
                        .map(
                                match ->
                                        recoverOne(
                                                        match,
                                                        anchorsByTable.get(match.tableId()),
                                                        coordinator,
                                                        matches)
                                                .exceptionally(
                                                        failure -> {
                                                            plugin.getLogger()
                                                                    .log(
                                                                            Level.WARNING,
                                                                            "Table recovery isolated: "
                                                                                    + match.tableId(),
                                                                            unwrap(failure));
                                                            return null;
                                                        })
                                                .toCompletableFuture())
                        .toList();
        return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
    }

    private CompletionStage<Void> recoverOne(
            MatchInstanceRecord match,
            StoredTableAnchor anchor,
            RulePackMatchCoordinator coordinator,
            JdbcMatchRepository matches) {
        if (match.status() == TableLifecycle.NEEDS_ADMIN_REVIEW) {
            return CompletableFuture.completedFuture(null);
        }
        if (anchor == null) {
            return markReview(match, "missing table anchor", matches);
        }
        return registerPaperAnchor(anchor)
                .thenCompose(
                        ignored ->
                                coordinator.recover(
                                        match.binding().matchId(), CompetitionRef.none()))
                .thenCompose(
                        startedMatch -> {
                            if (liveTables.registerRecovered(startedMatch)) {
                                return CompletableFuture.completedFuture(null);
                            }
                            actors.remove(startedMatch.tableId(), startedMatch.actor());
                            startedMatch.actor().close();
                            return markReview(
                                    match,
                                    "participant or table recovery conflict",
                                    matches);
                        });
    }

    private CompletionStage<Void> registerPaperAnchor(StoredTableAnchor anchor) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler()
                    .execute(
                            plugin,
                            () -> {
                                try {
                                    World world = resolveWorld(anchor.worldId());
                                    paperAnchors.register(
                                            anchor.tableId(),
                                            new Location(
                                                    world,
                                                    anchor.x(),
                                                    anchor.y(),
                                                    anchor.z(),
                                                    anchor.yaw(),
                                                    anchor.pitch()));
                                    result.complete(null);
                                } catch (RuntimeException failure) {
                                    result.completeExceptionally(failure);
                                }
                            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private CompletionStage<Void> markReview(
            MatchInstanceRecord match, String reason, JdbcMatchRepository repository) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        repository.updateStatus(
                                match.binding().matchId(),
                                TableLifecycle.NEEDS_ADMIN_REVIEW,
                                Instant.now(Clock.systemUTC()));
                    } catch (SQLException failure) {
                        throw new CompletionException(failure);
                    }
                    plugin.getLogger()
                            .warning("Match " + match.binding().matchId() + " requires review: " + reason);
                },
                executors.io());
    }

    private void blockAllRecoverableMatches(JdbcMatchRepository matches) {
        try {
            for (MatchInstanceRecord match : matches.recoverableMatches()) {
                if (match.status() != TableLifecycle.NEEDS_ADMIN_REVIEW) {
                    matches.updateStatus(
                            match.binding().matchId(),
                            TableLifecycle.BLOCKED_RULE_PACK,
                            Instant.now(Clock.systemUTC()));
                }
            }
        } catch (SQLException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not block recoverable matches", failure);
        }
    }

    private void registerPlatformListeners(DirectCraftEngineMutationGateway mutations) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new CraftEngineInteractionListener(
                                interactions,
                                (player, result, failure) -> {
                                    String message =
                                            failure == null
                                                    ? result.code() + ": " + result.reasonCode()
                                                    : "ACTION_FAILED: "
                                                            + safeMessage(unwrap(failure));
                                    player.getScheduler()
                                            .run(
                                                    plugin,
                                                    ignored ->
                                                            player.sendActionBar(
                                                                    Component.text(message)),
                                                    null);
                                },
                                mutations.managedKey(),
                                mutations.interactionKey()),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new CraftEngineReloadListener(sceneBackend), plugin);
        plugin.getServer().getPluginManager().registerEvents(privateProjection, plugin);
    }

    private void installCraftEngineBundle(Plugin craftEngine) {
        CompletableFuture.runAsync(
                        () -> {
                            try {
                                new CraftEngineBundleInstaller(
                                                plugin,
                                                configuration.craftEngineBundleFolder())
                                        .install(craftEngine);
                            } catch (IOException failure) {
                                throw new CompletionException(failure);
                            }
                        },
                        executors.io())
                .whenComplete(
                        (ignored, failure) -> {
                            if (failure == null) {
                                plugin.getLogger()
                                        .info(
                                                "CraftEngine bundle verified. Use /ce reload all if CraftEngine has not loaded it yet.");
                            } else {
                                plugin.getLogger()
                                        .log(
                                                Level.SEVERE,
                                                "CraftEngine bundle installation failed; scenes remain closed",
                                                unwrap(failure));
                            }
                        });
    }

    private Plugin requireCraftEngine() {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            throw new IllegalStateException("CraftEngine is a hard dependency");
        }
        CraftEngineVersion.requireSupported(craftEngine.getPluginMeta().getVersion());
        return craftEngine;
    }

    private Services requireServices() {
        Services current = services.get();
        if (current == null || state.get() == State.STARTING) {
            throw new IllegalStateException("MahjongPaper is still initializing");
        }
        if (state.get() == State.FAILED
                || state.get() == State.STOPPING
                || state.get() == State.STOPPED) {
            throw new IllegalStateException("MahjongPaper is stopping");
        }
        return current;
    }

    private RulePackAdminService requireAdmin() {
        return requireServices()
                .admin()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Signed registry administration is unavailable"));
    }

    private <T> CompletionStage<T> submitIo(Callable<T> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return operation.call();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                },
                executors.io());
    }

    private static World resolveWorld(String worldId) {
        World world = null;
        try {
            world = Bukkit.getWorld(java.util.UUID.fromString(worldId));
        } catch (IllegalArgumentException ignored) {
            // Legacy names remain readable for manually reviewed anchors.
        }
        if (world == null) {
            world = Bukkit.getWorld(worldId);
        }
        if (world == null) {
            throw new IllegalStateException("World is not loaded: " + worldId);
        }
        return world;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static void closeRuleRuntime(RulePackRuntime runtime) {
        try {
            runtime.close();
        } catch (IOException ignored) {
            // Startup already failed; there is no additional recovery action.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state.set(State.STOPPING);
        List<CompletionStage<Void>> drains = actors.closeAll();
        CompletableFuture<?>[] futures =
                drains.stream()
                        .map(CompletionStage::toCompletableFuture)
                        .toArray(CompletableFuture<?>[]::new);
        if (futures.length > 0) {
            try {
                CompletableFuture.allOf(futures)
                        .get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException failure) {
                plugin.getLogger().warning("Timed out draining persistence outboxes");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException failure) {
                plugin.getLogger()
                        .log(Level.WARNING, "A persistence outbox failed during shutdown", failure);
            }
        }
        privateProjection.close();
        deadlines.close();
        ruleExecutor.close();
        Services current = services.getAndSet(null);
        if (current != null) {
            current.ruleRuntime().ifPresent(MahjongRuntime::closeRuleRuntime);
            current.dataSource().ifPresent(HikariDataSource::close);
        }
        executors.close(SHUTDOWN_TIMEOUT);
        state.set(State.STOPPED);
        detail.set("stopped");
    }

    private enum State {
        STARTING,
        READY,
        DEGRADED,
        FAILED,
        STOPPING,
        STOPPED
    }

    private record RuleServices(
            Optional<RulePackRuntime> runtime,
            Optional<RulePackAdminService> admin,
            RulePackInventoryReader inventory) {}

    private record DatabaseServices(
            Optional<HikariDataSource> dataSource,
            Optional<JdbcMatchRepository> matches,
            Optional<JdbcTableAnchorRepository> anchors,
            Optional<JdbcEventStore> events) {}

    private record Services(
            Optional<HikariDataSource> dataSource,
            Optional<JdbcMatchRepository> matches,
            Optional<JdbcTableAnchorRepository> anchorRepository,
            Optional<JdbcEventStore> events,
            Optional<RulePackRuntime> ruleRuntime,
            Optional<RulePackAdminService> admin,
            RulePackInventoryReader inventory,
            Optional<RulePackMatchCoordinator> coordinator) {}
}
