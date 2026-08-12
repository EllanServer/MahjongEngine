package top.ellan.mahjong.plugin;

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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import top.ellan.mahjong.application.concurrent.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.history.PlayerMatchHistoryEntry;
import top.ellan.mahjong.application.history.PlayerRankingPage;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.lobby.runtime.LobbyTableDirectory;
import top.ellan.mahjong.application.lobby.usecase.CreateLobbyRequest;
import top.ellan.mahjong.application.lobby.usecase.LobbyUseCases;
import top.ellan.mahjong.application.table.MatchCompletion;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.domain.lobby.LobbyPhase;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.platform.paper.concurrent.BoundedPlatformExecutors;
import top.ellan.mahjong.plugin.bootstrap.rules.RulePackBootstrap;
import top.ellan.mahjong.plugin.bootstrap.rules.RulePackOperations;
import top.ellan.mahjong.plugin.bootstrap.rules.RuleResourceStartup;
import top.ellan.mahjong.plugin.bootstrap.rules.RulePackRuntimeServices;
import top.ellan.mahjong.plugin.bootstrap.sql.DatabaseBootstrap;
import top.ellan.mahjong.plugin.bootstrap.sql.DatabaseRuntime;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.plugin.lobby.LobbyRuntimeCoordinator;
import top.ellan.mahjong.plugin.lobby.LobbyRuntimeServices;
import top.ellan.mahjong.plugin.match.NewRulePackMatch;
import top.ellan.mahjong.plugin.match.MatchAutomationService;
import top.ellan.mahjong.plugin.match.MatchPersistenceCleanup;
import top.ellan.mahjong.plugin.match.MatchRefereeService;
import top.ellan.mahjong.plugin.match.RulePackMatchCoordinator;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.plugin.history.PlayerRecordService;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;
import top.ellan.mahjong.plugin.dialog.MahjongDialogService;
import top.ellan.mahjong.plugin.dialog.CompositeSceneProjectionPort;
import top.ellan.mahjong.plugin.platform.CraftEnginePlatformRuntime;
import top.ellan.mahjong.plugin.recovery.MatchRecoveryService;
import top.ellan.mahjong.plugin.runtime.ActorDrain;
import top.ellan.mahjong.plugin.runtime.FailureSupport;
import top.ellan.mahjong.plugin.runtime.RuntimeServices;
import top.ellan.mahjong.plugin.runtime.RuleExecutionPools;
import top.ellan.mahjong.plugin.table.LiveTableDirectory;
import top.ellan.mahjong.runtime.admin.RulePackInventory;
import top.ellan.mahjong.runtime.admin.RulePackVerification;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackDescriptor;

/** Restart-scoped 2.0 composition root. All concrete setup lives in classified bootstraps. */
public final class MahjongRuntime implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MATCH_REUSE_DELAY = Duration.ofSeconds(3);

    private final MahjongPaperPlugin plugin;
    private final PluginConfiguration configuration;
    private final Clock clock = Clock.systemUTC();
    private final BoundedPlatformExecutors executors;
    private final RuleExecutionPools ruleExecutors;
    private final BoundedDeadlineScheduler deadlines;
    private final TableActorRegistry actors = new TableActorRegistry();
    private final LiveTableDirectory liveTables = new LiveTableDirectory();
    private final MatchAutomationService automation = new MatchAutomationService(liveTables);
    private final MatchRefereeService referees = new MatchRefereeService(liveTables);
    private final PlayerRecordService playerRecords;
    private final LocalizedMessageCatalog messages;
    private final MahjongDialogService dialogs;
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
        messages = LocalizedMessageCatalog.load(plugin.getClass().getClassLoader());
        dialogs = new MahjongDialogService(plugin, this, messages);
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        executors = new BoundedPlatformExecutors(processors);
        playerRecords = new PlayerRecordService(
                executors.io(),
                () -> Optional.ofNullable(services.get())
                        .flatMap(current -> current.database().playerRecords()));
        ruleExecutors = new RuleExecutionPools(processors);
        deadlines =
                new BoundedDeadlineScheduler(8_192, executors.actor(), "mahjong-deadline");
        platform =
                new CraftEnginePlatformRuntime(
                        plugin,
                        configuration,
                        executors,
                        deadlines,
                        actors,
                        messages);
        lobbyRuntime =
                new LobbyRuntimeCoordinator(
                        executors.actor(),
                        executors.io(),
                        actors,
                        new CompositeSceneProjectionPort(platform.sceneProjector(), dialogs),
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
        platform.start(lobbyRuntime.seatInteractions(), automation, dialogs);
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
                                + liveTables.size()
                                + ", lobbies="
                                + lobbyRuntime.directory().size();
        return state.get() + ": " + detail.get() + suffix;
    }

    public LocalizedMessageCatalog messages() { return messages; }
    public LiveTableDirectory liveTables() { return liveTables; }
    public LobbyTableDirectory lobbyTables() { return lobbyRuntime.directory(); }
    public LobbyUseCases lobbyUseCases() { return lobbyRuntime.useCases(); }
    public MahjongDialogService dialogs() { return dialogs; }
    /** Active provider descriptors are the sole source of rule-setting dialog fields. */
    public List<RulePackDescriptor> ruleDescriptors() { return requireServices().rules().activeDescriptors(); }

    public CompletionStage<top.ellan.mahjong.application.table.TableActionResult> setAutomation(
            top.ellan.mahjong.spi.PlayerId playerId, boolean enabled) {
        return automation.setAutomated(playerId, enabled);
    }

    /** Leaves a lobby immediately or delegates a live seat to automation until match completion. */
    public CompletionStage<TableActionResult> leave(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (lobbyRuntime.directory().findByPlayer(playerId).isPresent()) {
            return lobbyRuntime.useCases().leave(playerId);
        }
        StartedRulePackMatch match = liveTables.findByPlayer(playerId).orElse(null);
        if (match == null) {
            return rejectedAction("not-at-table");
        }
        ParticipantRole role = roleOf(match, playerId).orElse(null);
        if (role == ParticipantRole.SPECTATOR) {
            liveTables.markDeparting(playerId);
            return acceptedAction(match, "spectator-left");
        }
        if (role != ParticipantRole.PLAYER) {
            return rejectedAction("player-required");
        }
        if (match.actor().snapshot().lifecycle().terminal()) {
            liveTables.markDeparting(playerId);
            return acceptedAction(match, "leave-after-match");
        }
        return automation.setAutomated(playerId, true)
                .thenApply(
                        result -> {
                            if (result.code() != TableActionCode.ACCEPTED_MEMORY) {
                                return result;
                            }
                            liveTables.markDeparting(playerId);
                            return new TableActionResult(
                                    TableActionCode.ACCEPTED_MEMORY,
                                    result.revision(),
                                    "leave-deferred");
                        });
    }

    public CompletionStage<TableActionResult> unspectate(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (lobbyRuntime.directory().findByPlayer(playerId).isPresent()) {
            return lobbyRuntime.useCases().unspectate(playerId);
        }
        StartedRulePackMatch match = liveTables.findByPlayer(playerId).orElse(null);
        if (match == null || roleOf(match, playerId).orElse(null) != ParticipantRole.SPECTATOR) {
            return rejectedAction("not-spectating");
        }
        liveTables.markDeparting(playerId);
        return acceptedAction(match, "spectator-left");
    }

    public boolean automationEnabled(PlayerId playerId) {
        return automation.isAutomated(playerId);
    }

    public boolean isDeparting(TableId tableId, PlayerId playerId) {
        return liveTables.isDeparting(tableId, playerId);
    }

    public CompletionStage<TableActionResult> submitReferee(
            TableId tableId, PlayerId authority, RuleAction action) {
        requireServices();
        return referees.submit(tableId, authority, action);
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
            return lobbyRemoval.orElseThrow()
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            dialogs.forget(tableId);
                        }
                    });
        }
        StartedRulePackMatch match =
                liveTables
                        .remove(tableId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown live table"));
        actors.remove(tableId, match.actor());
        return match.actor()
                .closeAndDrain()
                .whenComplete(
                        (ignored, failure) -> {
                            platform.removeTable(tableId);
                            dialogs.forget(tableId);
                            MatchPersistenceCleanup.releaseRulePackLease(
                                    current.rules(), match, tableId);
                        })
                .thenCompose(
                        ignored ->
                                CompletableFuture.runAsync(
                                        () -> MatchPersistenceCleanup.closeMatch(
                                                current.database(), match, Instant.now(clock)),
                                        executors.io()));
    }

    /** Owner-scoped removal; live matches remain an administrative operation. */
    public CompletionStage<Void> removeOwnedLobby(TableId tableId, PlayerId ownerId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(ownerId, "ownerId");
        HostedLobby lobby = lobbyRuntime.directory().find(tableId)
                .filter(candidate -> candidate.state().ownerId().equals(ownerId))
                .filter(candidate -> candidate.state().phase() == LobbyPhase.WAITING)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Only the owner may remove a waiting lobby"));
        CompletionStage<Void> removal = lobbyRuntime.remove(lobby.tableId()).orElseThrow();
        return removal.whenComplete(
                (ignored, failure) -> {
                    if (failure == null) {
                        dialogs.forget(tableId);
                    }
                });
    }

    public CompletionStage<RulePackInventory> listRules() {
        return submitIo(ruleAdmin().inventory()::read);
    }

    public CompletionStage<?> installRule(RuleId ruleId, Optional<String> version) {
        return submitIo(() -> ruleAdmin().install(ruleId, version));
    }

    public CompletionStage<List<RulePackVerification>> verifyRules(Optional<RuleId> ruleId) {
        return submitIo(() -> ruleAdmin().verify(ruleId));
    }

    public CompletionStage<?> activateRule(RuleId ruleId, String version) {
        return submitIo(() -> ruleAdmin().activate(ruleId, version));
    }

    public CompletionStage<String> swapRule(RuleId ruleId, String version) {
        return submitIo(() -> ruleAdmin().swap(ruleId, version));
    }

    public CompletionStage<String> deactivateRule(RuleId ruleId) {
        return submitIo(() -> ruleAdmin().deactivate(ruleId));
    }

    public CompletionStage<String> rollbackRule(RuleId ruleId) {
        return submitIo(() -> ruleAdmin().rollback(ruleId));
    }

    public CompletionStage<?> collectRuleGarbage() {
        return submitIo(ruleAdmin()::collectGarbage);
    }

    private RulePackOperations ruleAdmin() {
        return new RulePackOperations(
                requireServices().rules(), ruleExecutors, platform::activateRuleResource);
    }

    public CompletionStage<List<PlayerMatchHistoryEntry>> playerHistory(
            PlayerId playerId, int page, int pageSize) {
        requireServices();
        return playerRecords.history(playerId, page, pageSize);
    }

    public CompletionStage<PlayerRankingPage> playerRanking(
            PlayerId playerId, RuleId ruleId, int page, int pageSize) {
        requireServices();
        return playerRecords.ranking(playerId, ruleId, page, pageSize);
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
            RuleResourceStartup ruleResources =
                    new RuleResourceStartup(rules, platform::installRuleResources);
            ruleResources.installActive();
            Optional<RulePackMatchCoordinator> coordinator =
                    createCoordinator(database, rules);
            initialized = new RuntimeServices(database, rules, coordinator);
            services.set(initialized);
            bindAndRecover(initialized);
            ruleResources.installLoaded();
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
                        ruleExecutors.rules(),
                        ruleExecutors.automation(),
                        deadlines,
                        actors,
                        database.matches().orElseThrow(),
                        database.events().orElseThrow(),
                        rules.runtime().orElseThrow(),
                        new CompositeSceneProjectionPort(platform.sceneProjector(), dialogs),
                        platform.presentationCues(),
                        platform.openingPresentations(),
                        this::matchCompleted,
                        clock));
    }

    private void matchCompleted(MatchCompletion completion) {
        if (closed.get()) {
            return;
        }
        try {
            deadlines.schedule(() -> recycleCompletedMatch(completion), MATCH_REUSE_DELAY);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not schedule completed table reuse " + completion.tableId(),
                    failure);
        }
    }

    private void recycleCompletedMatch(MatchCompletion completion) {
        if (closed.get()) {
            return;
        }
        LiveTableDirectory.Removed removed =
                liveTables.removeCompleted(completion.tableId(), completion.binding()).orElse(null);
        if (removed == null) {
            return;
        }
        StartedRulePackMatch match = removed.match();
        actors.remove(match.tableId(), match.actor());
        RuntimeServices current = services.get();
        match.actor()
                .closeAndDrain()
                .thenCompose(
                        ignored ->
                                lobbyRuntime.reopenAfterMatch(
                                        match.tableId(),
                                        match.anchor(),
                                        removed.departedPlayers()))
                .whenComplete(
                        (reopened, failure) -> {
                            if (current != null) {
                                MatchPersistenceCleanup.releaseRulePackLease(
                                        current.rules(), match, match.tableId());
                            }
                            dialogs.forget(match.tableId());
                            if (failure != null) {
                                plugin.getLogger().log(
                                        Level.WARNING,
                                        "Completed table could not be reopened " + match.tableId(),
                                        FailureSupport.unwrap(failure));
                                return;
                            }
                            if (reopened.isEmpty()) {
                                platform.removeTable(match.tableId());
                                return;
                            }
                            reconcileReopenedPresence(reopened.orElseThrow());
                        });
    }

    private void reconcileReopenedPresence(HostedLobby lobby) {
        try {
            Bukkit.getGlobalRegionScheduler()
                    .execute(
                            plugin,
                            () ->
                                    lobby.state().seats().stream()
                                            .filter(seat -> seat.occupant().isPresent())
                                            .filter(seat -> !lobby.state().isBotSeat(seat))
                                            .map(seat -> seat.occupant().orElseThrow())
                                            .filter(playerId ->
                                                    plugin.getServer().getPlayer(playerId.value())
                                                            != null)
                                            .forEach(
                                                    playerId ->
                                                            lobbyRuntime.seatInteractions()
                                                                    .connected(playerId)));
        } catch (RuntimeException shuttingDown) {
            plugin.getLogger().fine("Reopened lobby presence reconciliation was skipped");
        }
    }

    private static Optional<ParticipantRole> roleOf(
            StartedRulePackMatch match, PlayerId playerId) {
        return match.participants().stream()
                .filter(participant -> participant.playerId().equals(playerId))
                .map(top.ellan.mahjong.domain.table.TableParticipant::role)
                .findFirst();
    }

    private static CompletionStage<TableActionResult> acceptedAction(
            StartedRulePackMatch match, String reason) {
        return CompletableFuture.completedFuture(
                new TableActionResult(
                        TableActionCode.ACCEPTED_MEMORY,
                        match.actor().snapshot().revision(),
                        reason));
    }

    private static CompletionStage<TableActionResult> rejectedAction(String reason) {
        return CompletableFuture.completedFuture(
                new TableActionResult(TableActionCode.REJECTED_BY_RULES, 0, reason));
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
            reconcileRecoveredAutomation();
        } else {
            database.matches().ifPresent(recovery::blockRecoverableMatches);
        }
    }

    /** One startup pass only; runtime connection changes use O(1) player-to-table routing. */
    private void reconcileRecoveredAutomation() {
        liveTables.list().forEach(match -> match.participants().stream()
                .filter(participant ->
                        participant.role()
                                        == top.ellan.mahjong.domain.table.ParticipantRole.PLAYER
                                && participant.seat().isPresent())
                .forEach(participant -> {
                    if (plugin.getServer().getPlayer(participant.playerId().value()) == null) {
                        automation.disconnected(participant.playerId());
                    } else {
                        automation.connected(participant.playerId());
                    }
                }));
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
        ActorDrain.awaitAll(actors, SHUTDOWN_TIMEOUT, plugin.getLogger());
        dialogs.close();
        platform.close();
        deadlines.close();
        ruleExecutors.close();
        RuntimeServices current = services.getAndSet(null);
        if (current != null) {
            current.close();
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
}
