package top.ellan.mahjong.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.application.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.FairRuleExecutor;
import top.ellan.mahjong.application.InteractionRouter;
import top.ellan.mahjong.application.TableActorRegistry;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.persistence.sql.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.SqlSchemaMigrator;
import top.ellan.mahjong.platform.paper.BoundedPlatformExecutors;
import top.ellan.mahjong.runtime.EmbeddedRuleTrustRoot;
import top.ellan.mahjong.runtime.HttpArtifactDownloader;
import top.ellan.mahjong.runtime.HttpRegistrySource;
import top.ellan.mahjong.runtime.OfficialTrustRoot;
import top.ellan.mahjong.runtime.RuleActivationStore;
import top.ellan.mahjong.runtime.RulePackAdminService;
import top.ellan.mahjong.runtime.RulePackException;
import top.ellan.mahjong.runtime.RulePackGarbageCollector;
import top.ellan.mahjong.runtime.RulePackInstaller;
import top.ellan.mahjong.runtime.RulePackInventoryReader;
import top.ellan.mahjong.runtime.RulePackLoader;
import top.ellan.mahjong.runtime.RulePackPaths;
import top.ellan.mahjong.runtime.RulePackRuntime;
import top.ellan.mahjong.runtime.RulePackRuntimeStatus;

/**
 * Restart-scoped composition root for the actor/rule-pack architecture. Legacy controllers are not
 * wired into these services; matches cross the boundary only after their migration mode allows it.
 */
public final class MahjongArchitectureRuntime implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final Logger logger;
    private final BoundedPlatformExecutors executors;
    private final FairRuleExecutor ruleExecutor;
    private final BoundedDeadlineScheduler deadlines;
    private final TableActorRegistry actors;
    private final InteractionRouter interactions;
    private final Optional<JdbcMatchRepository> matches;
    private final Optional<JdbcEventStore> eventStore;
    private final Optional<RulePackRuntime> ruleRuntime;
    private final Optional<RulePackAdminService> ruleAdmin;
    private final Optional<RulePackInventoryReader> ruleInventory;
    private final Optional<RulePackMatchCoordinator> matchCoordinator;
    private final MahjongArchitectureStatus status;

    private MahjongArchitectureRuntime(
            Logger logger,
            BoundedPlatformExecutors executors,
            FairRuleExecutor ruleExecutor,
            BoundedDeadlineScheduler deadlines,
            TableActorRegistry actors,
            InteractionRouter interactions,
            Optional<JdbcMatchRepository> matches,
            Optional<JdbcEventStore> eventStore,
            Optional<RulePackRuntime> ruleRuntime,
            Optional<RulePackAdminService> ruleAdmin,
            Optional<RulePackInventoryReader> ruleInventory,
            Optional<RulePackMatchCoordinator> matchCoordinator,
            MahjongArchitectureStatus status) {
        this.logger = logger;
        this.executors = executors;
        this.ruleExecutor = ruleExecutor;
        this.deadlines = deadlines;
        this.actors = actors;
        this.interactions = interactions;
        this.matches = matches;
        this.eventStore = eventStore;
        this.ruleRuntime = ruleRuntime;
        this.ruleAdmin = ruleAdmin;
        this.ruleInventory = ruleInventory;
        this.matchCoordinator = matchCoordinator;
        this.status = status;
    }

    public static MahjongArchitectureRuntime start(
            MahjongPaperPlugin plugin,
            PluginSettings settings,
            DatabaseService database,
            Logger logger) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(logger, "logger");
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        BoundedPlatformExecutors executors = new BoundedPlatformExecutors(processors);
        FairRuleExecutor ruleExecutor = new FairRuleExecutor(
            Math.max(2, Math.min(processors, 8)),
            1_024,
            "mahjong-rule"
        );
        BoundedDeadlineScheduler deadlines = new BoundedDeadlineScheduler(
            8_192,
            executors.actor(),
            "mahjong-deadline"
        );
        TableActorRegistry actors = new TableActorRegistry();
        InteractionRouter interactions = new InteractionRouter(actors);

        Optional<JdbcMatchRepository> matches = Optional.empty();
        Optional<JdbcEventStore> eventStore = Optional.empty();
        if (database != null) {
            SqlConnectionFactory connections = database::openConnection;
            try {
                new SqlSchemaMigrator(connections).migrate();
                JdbcEventStore store = new JdbcEventStore(connections, executors.io());
                if (store.probe()) {
                    matches = Optional.of(new JdbcMatchRepository(connections));
                    eventStore = Optional.of(store);
                }
            } catch (RuntimeException | java.sql.SQLException failure) {
                logger.log(
                    Level.WARNING,
                    "Match event store is unavailable; active matches remain paused and new recoverable matches are disabled.",
                    failure
                );
            }
        }

        Optional<RulePackRuntime> ruleRuntime = Optional.empty();
        Optional<RulePackAdminService> ruleAdmin = Optional.empty();
        Optional<RulePackInventoryReader> ruleInventory = Optional.empty();
        Optional<RulePackRuntimeStatus> ruleStatus = Optional.empty();
        Optional<String> adminDisabled = Optional.empty();
        if (settings.rules().enabled()) {
            RulePackPaths paths = new RulePackPaths(plugin.getDataFolder().toPath().resolve("rules"));
            RulePackLoader loader = new RulePackLoader(plugin.getPluginMeta().getVersion());
            RuleActivationStore activation = new RuleActivationStore(paths.activationState());
            ruleInventory = Optional.of(new RulePackInventoryReader(paths, activation));
            RulePackRuntime runtime = new RulePackRuntime(paths, loader, activation);
            try {
                ruleStatus = Optional.of(runtime.start());
                ruleRuntime = Optional.of(runtime);
            } catch (IOException | RulePackException failure) {
                adminDisabled = Optional.of("rule-runtime-start-failed: " + safeMessage(failure));
                logger.log(Level.WARNING, "Rule-pack runtime failed closed; legacy/lobby services remain available.", failure);
                closeQuietly(runtime, logger);
            }

            if (settings.rules().registryUrl().isBlank()) {
                adminDisabled = Optional.of("rules.registryUrl is blank");
            } else {
                try {
                    OfficialTrustRoot trustRoot = EmbeddedRuleTrustRoot.load(
                        MahjongArchitectureRuntime.class.getClassLoader()
                    );
                    HttpClient client = HttpClient.newBuilder()
                        .executor(executors.io())
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
                    HttpRegistrySource registry = new HttpRegistrySource(
                        client,
                        URI.create(settings.rules().registryUrl())
                    );
                    RulePackInstaller installer = new RulePackInstaller(
                        paths,
                        registry,
                        new HttpArtifactDownloader(client),
                        trustRoot,
                        loader,
                        Clock.systemUTC()
                    );
                    JdbcMatchRepository matchRepository = matches.orElse(null);
                    RulePackGarbageCollector garbageCollector = new RulePackGarbageCollector(
                        paths,
                        () -> {
                            if (matchRepository == null) {
                                throw new IllegalStateException(
                                    "Database is unavailable; rule-pack GC cannot prove artifact safety"
                                );
                            }
                            return matchRepository.referencedRulePacks();
                        },
                        activation,
                        Clock.systemUTC()
                    );
                    ruleAdmin = Optional.of(new RulePackAdminService(
                        paths,
                        registry,
                        trustRoot,
                        loader,
                        installer,
                        activation,
                        garbageCollector
                    ));
                    adminDisabled = Optional.empty();
                } catch (IOException | RulePackException | IllegalArgumentException failure) {
                    adminDisabled = Optional.of(safeMessage(failure));
                    logger.log(Level.WARNING, "Signed rule-pack administration is disabled.", failure);
                }
            }
        } else {
            adminDisabled = Optional.of("rules.enabled is false");
        }

        MahjongArchitectureStatus status = new MahjongArchitectureStatus(
            eventStore.isPresent(),
            ruleStatus,
            adminDisabled
        );
        Optional<RulePackMatchCoordinator> matchCoordinator = Optional.empty();
        if (matches.isPresent() && eventStore.isPresent() && ruleRuntime.isPresent()) {
            matchCoordinator = Optional.of(new RulePackMatchCoordinator(
                executors.actor(),
                executors.io(),
                ruleExecutor,
                deadlines,
                actors,
                matches.orElseThrow(),
                eventStore.orElseThrow(),
                ruleRuntime.orElseThrow(),
                Clock.systemUTC()
            ));
        }
        return new MahjongArchitectureRuntime(
            logger,
            executors,
            ruleExecutor,
            deadlines,
            actors,
            interactions,
            matches,
            eventStore,
            ruleRuntime,
            ruleAdmin,
            ruleInventory,
            matchCoordinator,
            status
        );
    }

    public MahjongArchitectureStatus status() {
        return status;
    }

    public TableActorRegistry actors() {
        return actors;
    }

    public InteractionRouter interactions() {
        return interactions;
    }

    public FairRuleExecutor ruleExecutor() {
        return ruleExecutor;
    }

    public BoundedDeadlineScheduler deadlines() {
        return deadlines;
    }

    public Optional<JdbcMatchRepository> matches() {
        return matches;
    }

    public Optional<JdbcEventStore> eventStore() {
        return eventStore;
    }

    public Optional<RulePackRuntime> ruleRuntime() {
        return ruleRuntime;
    }

    public Optional<RulePackAdminService> ruleAdmin() {
        return ruleAdmin;
    }

    public Optional<RulePackInventoryReader> ruleInventory() {
        return ruleInventory;
    }

    public Optional<RulePackMatchCoordinator> matchCoordinator() {
        return matchCoordinator;
    }

    public <T> CompletionStage<T> submitAdmin(Callable<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.call();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, executors.io());
    }

    @Override
    public void close() {
        List<CompletionStage<Void>> drains = actors.closeAll();
        CompletableFuture<?>[] futures = drains.stream()
            .map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture<?>[]::new);
        if (futures.length > 0) {
            try {
                CompletableFuture.allOf(futures).get(
                    SHUTDOWN_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            } catch (TimeoutException failure) {
                logger.warning("Timed out after 10 seconds draining match persistence outboxes.");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException failure) {
                logger.log(Level.WARNING, "A match outbox failed during shutdown.", failure.getCause());
            }
        }
        deadlines.close();
        ruleExecutor.close();
        ruleRuntime.ifPresent(runtime -> closeQuietly(runtime, logger));
        executors.close(SHUTDOWN_TIMEOUT);
    }

    private static void closeQuietly(RulePackRuntime runtime, Logger logger) {
        try {
            runtime.close();
        } catch (IOException failure) {
            logger.log(Level.WARNING, "Unable to close a rule-pack classloader.", failure);
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }
}
