package top.ellan.mahjong.plugin;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.bukkit.Location;
import top.ellan.mahjong.application.concurrent.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.lobby.runtime.LobbyTableDirectory;
import top.ellan.mahjong.application.lobby.usecase.CreateLobbyRequest;
import top.ellan.mahjong.application.lobby.usecase.LobbyUseCases;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.platform.paper.BoundedPlatformExecutors;
import top.ellan.mahjong.plugin.bootstrap.rules.RulePackBootstrap;
import top.ellan.mahjong.plugin.bootstrap.rules.RulePackRuntimeServices;
import top.ellan.mahjong.plugin.bootstrap.sql.DatabaseBootstrap;
import top.ellan.mahjong.plugin.bootstrap.sql.DatabaseRuntime;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.plugin.lobby.LobbyRuntimeCoordinator;
import top.ellan.mahjong.plugin.lobby.LobbyRuntimeServices;
import top.ellan.mahjong.plugin.match.NewRulePackMatch;
import top.ellan.mahjong.plugin.match.RulePackMatchCoordinator;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.plugin.platform.CraftEnginePlatformRuntime;
import top.ellan.mahjong.plugin.recovery.MatchRecoveryService;
import top.ellan.mahjong.plugin.runtime.FailureSupport;
import top.ellan.mahjong.plugin.runtime.RuntimeServices;
import top.ellan.mahjong.plugin.table.LiveTableDirectory;
import top.ellan.mahjong.runtime.RulePackAdminService;
import top.ellan.mahjong.runtime.RulePackInventory;
import top.ellan.mahjong.runtime.RulePackVerification;
import top.ellan.mahjong.spi.RuleId;

/** Restart-scoped 2.0 composition root. All concrete setup lives in classified bootstraps. */
public final class MahjongRuntime implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final MahjongPaperPlugin plugin;
    private final PluginConfiguration configuration;
    private final Clock clock = Clock.systemUTC();
    private final BoundedPlatformExecutors executors;
    private final FairRuleExecutor ruleExecutor;
    private final BoundedDeadlineScheduler deadlines;
    private final TableActorRegistry actors = new TableActorRegistry();
    private final LiveTableDirectory liveTables = new LiveTableDirectory();
    private final CraftEnginePlatformRuntime platform;
    private final LobbyRuntimeCoordinator lobbyRuntime;
    private final MatchRecoveryService recovery;
    private final AtomicReference<RuntimeServices> services = new AtomicReference<>();
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
        platform =
                new CraftEnginePlatformRuntime(
                        plugin,
                        configuration,
                        executors,
                        deadlines,
                        actors);
        lobbyRuntime =
                new LobbyRuntimeCoordinator(
                        executors.actor(),
                        executors.io(),
                        actors,
                        platform.sceneProjector(),
                        platform.sceneProjector(),
                        platform.sceneBackend(),
                        platform.anchorService(),
                        liveTables,
                        clock,
                        plugin.getLogger());
        recovery =
                new MatchRecoveryService(
                        platform.anchorService(),
                        liveTables,
                        actors,
                        executors.io(),
                        clock,
                        plugin.getLogger());
        platform.start(lobbyRuntime.seatInteractions());
    }

    public void start() {
        CompletableFuture.runAsync(this::initializeServices, executors.io())
                .whenComplete(
                        (ignored, failure) -> {
                            if (failure == null) {
                                started.complete(null);
                                return;
                            }
                            Throwable cause = FailureSupport.unwrap(failure);
                            state.set(State.FAILED);
                            detail.set(FailureSupport.safeMessage(cause));
                            plugin.getLogger()
                                    .log(
                                            Level.SEVERE,
                                            "MahjongPaper 2.0 initialization failed",
                                            cause);
                            started.completeExceptionally(cause);
                        });
    }

    public CompletionStage<Void> started() {
        return started;
    }

    public String status() {
        RuntimeServices current = services.get();
        String suffix =
                current == null
                        ? ""
                        : ", matches="
                                + liveTables.list().size()
                                + ", lobbies="
                                + lobbyRuntime.directory().list().size();
        return state.get() + ": " + detail.get() + suffix;
    }

    public LiveTableDirectory liveTables() {
        return liveTables;
    }

    public LobbyTableDirectory lobbyTables() {
        return lobbyRuntime.directory();
    }

    public LobbyUseCases lobbyUseCases() {
        return lobbyRuntime.useCases();
    }

    public CompletionStage<HostedLobby> createLobby(
            CreateLobbyRequest request,
            Location paperAnchor) {
        requireServices();
        return lobbyRuntime.create(request, paperAnchor);
    }

    public CompletionStage<StartedRulePackMatch> create(
            NewRulePackMatch command,
            Location paperAnchor) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(paperAnchor, "paperAnchor");
        RulePackMatchCoordinator coordinator = requireCoordinator();
        if (!liveTables.reserve(command.tableId(), command.participants())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A participant already belongs to a live table"));
        }
        platform.registerAnchor(command.tableId(), paperAnchor);
        CompletionStage<StartedRulePackMatch> creation;
        try {
            creation = coordinator.create(command);
        } catch (RuntimeException failure) {
            liveTables.releaseReservation(command.tableId());
            platform.removeTable(command.tableId());
            return CompletableFuture.failedFuture(failure);
        }
        return creation.whenComplete(
                (startedMatch, failure) -> {
                    if (failure == null) {
                        liveTables.completeReservation(startedMatch);
                    } else {
                        liveTables.releaseReservation(command.tableId());
                        platform.removeTable(command.tableId());
                    }
                });
    }

    public CompletionStage<Void> remove(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        RuntimeServices current = requireServices();
        Optional<CompletionStage<Void>> lobbyRemoval = lobbyRuntime.remove(tableId);
        if (lobbyRemoval.isPresent()) {
            return lobbyRemoval.orElseThrow();
        }
        StartedRulePackMatch match =
                liveTables
                        .remove(tableId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown live table"));
        actors.remove(tableId, match.actor());
        return match.actor()
                .closeAndDrain()
                .whenComplete((ignored, failure) -> platform.removeTable(tableId))
                .thenCompose(
                        ignored ->
                                CompletableFuture.runAsync(
                                        () -> removePersistedMatch(current, match),
                                        executors.io()));
    }

    public CompletionStage<RulePackInventory> listRules() {
        RuntimeServices current = requireServices();
        return submitIo(current.rules().inventory()::read);
    }

    public CompletionStage<?> installRule(RuleId ruleId, Optional<String> version) {
        return submitIo(() -> requireAdmin().install(ruleId, version));
    }

    public CompletionStage<List<RulePackVerification>> verifyRules(Optional<RuleId> ruleId) {
        return submitIo(() -> requireAdmin().verify(ruleId));
    }

    public CompletionStage<?> activateRule(RuleId ruleId, String version) {
        return submitIo(() -> requireAdmin().activate(ruleId, version));
    }

    public CompletionStage<?> collectRuleGarbage() {
        return submitIo(requireAdmin()::collectGarbage);
    }

    private void initializeServices() {
        DatabaseRuntime database =
                new DatabaseBootstrap(
                                configuration.database(),
                                executors.io(),
                                plugin.getLogger())
                        .initialize();
        RulePackRuntimeServices rules = null;
        RuntimeServices initialized = null;
        try {
            rules =
                    new RulePackBootstrap(
                                    plugin.getDataFolder().toPath(),
                                    plugin.getClass().getClassLoader(),
                                    plugin.getPluginMeta().getVersion(),
                                    configuration.registryUrl(),
                                    executors.io(),
                                    () ->
                                            database.matches()
                                                    .orElseThrow(
                                                            () ->
                                                                    new IllegalStateException(
                                                                            "Database is unavailable; GC cannot prove safety"))
                                                    .referencedRulePacks(),
                                    clock,
                                    plugin.getLogger())
                            .initialize();
            Optional<RulePackMatchCoordinator> coordinator =
                    createCoordinator(database, rules);
            initialized = new RuntimeServices(database, rules, coordinator);
            services.set(initialized);
            bindAndRecover(initialized);
            updateReadyState(coordinator);
        } catch (RuntimeException failure) {
            if (initialized != null) {
                services.compareAndSet(initialized, null);
                initialized.close();
            } else {
                if (rules != null) {
                    rules.close();
                }
                database.close();
            }
            throw failure;
        }
    }

    private Optional<RulePackMatchCoordinator> createCoordinator(
            DatabaseRuntime database,
            RulePackRuntimeServices rules) {
        if (!database.supportsMatches() || rules.runtime().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new RulePackMatchCoordinator(
                        executors.actor(),
                        executors.io(),
                        ruleExecutor,
                        deadlines,
                        actors,
                        database.matches().orElseThrow(),
                        database.events().orElseThrow(),
                        rules.runtime().orElseThrow(),
                        platform.sceneProjector(),
                        clock));
    }

    private void bindAndRecover(RuntimeServices initialized) {
        DatabaseRuntime database = initialized.database();
        lobbyRuntime.bind(
                new LobbyRuntimeServices(
                        database.lobbies(),
                        database.anchors(),
                        database.matches(),
                        initialized.coordinator()));
        lobbyRuntime.recover().toCompletableFuture().join();
        if (initialized.coordinator().isPresent()) {
            recovery.recover(
                            database.matches().orElseThrow(),
                            database.anchors().orElseThrow(),
                            initialized.coordinator().orElseThrow())
                    .toCompletableFuture()
                    .join();
        } else {
            database.matches().ifPresent(recovery::blockRecoverableMatches);
        }
    }

    private void updateReadyState(Optional<RulePackMatchCoordinator> coordinator) {
        if (coordinator.isPresent()) {
            state.set(State.READY);
            detail.set("rule-pack-only runtime ready");
        } else {
            state.set(State.DEGRADED);
            detail.set("lobby/admin only; active matches are fail-closed");
        }
    }

    private void removePersistedMatch(
            RuntimeServices current,
            StartedRulePackMatch match) {
        try {
            current.database()
                    .matches()
                    .ifPresent(
                            matches -> {
                                try {
                                    matches.updateStatus(
                                            match.binding().matchId(),
                                            TableLifecycle.CLOSED,
                                            Instant.now(clock));
                                } catch (SQLException failure) {
                                    throw new CompletionException(failure);
                                }
                            });
            current.database()
                    .anchors()
                    .ifPresent(
                            anchors -> {
                                try {
                                    anchors.delete(match.tableId());
                                } catch (SQLException failure) {
                                    throw new CompletionException(failure);
                                }
                            });
        } catch (CompletionException failure) {
            throw failure;
        }
    }

    private RuntimeServices requireServices() {
        RuntimeServices current = services.get();
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

    private RulePackMatchCoordinator requireCoordinator() {
        return requireServices()
                .coordinator()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Database or rule runtime is unavailable"));
    }

    private RulePackAdminService requireAdmin() {
        return requireServices()
                .rules()
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state.set(State.STOPPING);
        lobbyRuntime.close();
        drainActors();
        platform.close();
        deadlines.close();
        ruleExecutor.close();
        RuntimeServices current = services.getAndSet(null);
        if (current != null) {
            current.close();
        }
        executors.close(SHUTDOWN_TIMEOUT);
        state.set(State.STOPPED);
        detail.set("stopped");
    }

    private void drainActors() {
        List<CompletionStage<Void>> drains = actors.closeAll();
        CompletableFuture<?>[] futures =
                drains.stream()
                        .map(CompletionStage::toCompletableFuture)
                        .toArray(CompletableFuture<?>[]::new);
        if (futures.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(futures)
                    .get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            plugin.getLogger().warning("Timed out draining persistence outboxes");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException failure) {
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "A persistence outbox failed during shutdown",
                            failure);
        }
    }

    private enum State {
        STARTING,
        READY,
        DEGRADED,
        FAILED,
        STOPPING,
        STOPPED
    }
}
