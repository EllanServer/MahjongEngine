package top.ellan.mahjong.plugin.lobby;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.application.lobby.port.SeatInteractionPort;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.lobby.runtime.LobbyTableDirectory;
import top.ellan.mahjong.application.lobby.usecase.CreateLobbyRequest;
import top.ellan.mahjong.application.lobby.usecase.LobbySeatInteractionService;
import top.ellan.mahjong.application.lobby.usecase.LobbyUseCases;
import top.ellan.mahjong.craftengine.scene.CraftEngineSceneBackend;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLobby;
import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;
import top.ellan.mahjong.platform.paper.PaperTableAnchorService;
import top.ellan.mahjong.plugin.LiveTableDirectory;
import top.ellan.mahjong.presentation.projection.LatestSceneProjector;

/** Facade for pre-match lifecycle; the plugin composition root only delegates to this component. */
public final class LobbyRuntimeCoordinator implements AutoCloseable {
    private static final LobbyRuntimeServices UNBOUND =
            new LobbyRuntimeServices(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    private final Executor ioExecutor;
    private final Clock clock;
    private final Logger logger;
    private final LiveTableDirectory liveMatches;
    private final PaperTableAnchorService anchors;
    private final LobbyHostRegistry hosts;
    private final LobbyUseCases useCases;
    private final LobbySeatInteractionService seatInteractions;
    private final LobbyMatchStarter starter;
    private final AtomicReference<LobbyRuntimeServices> services =
            new AtomicReference<>(UNBOUND);

    public LobbyRuntimeCoordinator(
            Executor actorExecutor,
            Executor ioExecutor,
            TableActorRegistry actors,
            SceneProjectionPort projections,
            LatestSceneProjector sceneProjector,
            CraftEngineSceneBackend sceneBackend,
            PaperTableAnchorService anchors,
            LiveTableDirectory liveMatches,
            Clock clock,
            Logger logger) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.liveMatches = Objects.requireNonNull(liveMatches, "liveMatches");
        hosts =
                new LobbyHostRegistry(
                        actorExecutor,
                        ioExecutor,
                        actors,
                        projections,
                        sceneProjector,
                        sceneBackend,
                        anchors,
                        clock,
                        logger);
        useCases = new LobbyUseCases(hosts.directory());
        seatInteractions =
                new LobbySeatInteractionService(hosts.directory(), liveMatches::seatOf);
        starter =
                new LobbyMatchStarter(
                        hosts,
                        liveMatches,
                        actors,
                        ioExecutor,
                        clock,
                        logger,
                        services::get);
    }

    public void bind(LobbyRuntimeServices initialized) {
        Objects.requireNonNull(initialized, "initialized");
        if (!services.compareAndSet(UNBOUND, initialized)) {
            throw new IllegalStateException("Lobby runtime services already bound");
        }
    }

    public LobbyTableDirectory directory() {
        return hosts.directory();
    }

    public LobbyUseCases useCases() {
        return useCases;
    }

    public SeatInteractionPort seatInteractions() {
        return seatInteractions;
    }

    public CompletionStage<HostedLobby> create(
            CreateLobbyRequest request, Location paperAnchor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(paperAnchor, "paperAnchor");
        LobbyRuntimeServices current = requireBound();
        TableId tableId = request.anchor().tableId();
        if (liveMatches.ownsPlayer(request.ownerId())
                || !hosts.directory().reserve(tableId, request.ownerId())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Player already belongs to another table"));
        }
        anchors.register(request.anchor(), paperAnchor);
        TableLobby initial =
                TableLobby.create(
                        tableId,
                        request.ownerId(),
                        request.ruleId(),
                        request.profileId(),
                        request.configuration(),
                        request.seatCount(),
                        Instant.now(clock));
        CompletionStage<Void> persisted = persistInitial(current.lobbies(), initial, request.anchor());
        AtomicBoolean durableCreated = new AtomicBoolean();
        return persisted
                .thenApply(
                        ignored -> {
                            durableCreated.set(current.lobbies().isPresent());
                            return hosts.host(
                                        initial,
                                        request.anchor(),
                                        current.lobbies(),
                                        starter,
                                        true);
                        })
                .whenComplete(
                        (hosted, failure) -> {
                            if (failure != null) {
                                hosts.directory().releaseReservation(tableId);
                                anchors.remove(request.anchor());
                                if (durableCreated.get()) {
                                    cleanupFailedCreate(current, tableId);
                                }
                            }
                        });
    }

    public Optional<CompletionStage<Void>> remove(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        LobbyRuntimeServices current = requireBound();
        Optional<HostedLobby> detached = hosts.detach(tableId);
        if (detached.isEmpty()) {
            return Optional.empty();
        }
        HostedLobby lobby = detached.orElseThrow();
        CompletionStage<Void> removed =
                lobby.actor()
                        .closeAndDrain()
                        .whenComplete(
                                (ignored, failure) -> hosts.removeSceneAndAnchor(lobby))
                        .thenCompose(
                                ignored ->
                                        CompletableFuture.runAsync(
                                                () -> deleteDurable(current, tableId),
                                                ioExecutor));
        return Optional.of(removed);
    }

    public CompletionStage<Void> recover() {
        LobbyRuntimeServices current = requireBound();
        if (current.lobbies().isEmpty() || current.anchors().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<TableLobby> stored;
        Map<TableId, TableAnchor> anchorsByTable = new HashMap<>();
        Set<TableId> matchTables;
        try {
            stored = current.lobbies().orElseThrow().list();
            current.anchors()
                    .orElseThrow()
                    .list()
                    .forEach(anchor -> anchorsByTable.put(anchor.tableId(), anchor));
            matchTables =
                    current.matches().isEmpty()
                            ? Set.of()
                            : current.matches().orElseThrow().recoverableMatches().stream()
                                    .map(MatchInstanceRecord::tableId)
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
        List<CompletableFuture<Void>> recoveries =
                stored.stream()
                        .filter(lobby -> keepLobby(lobby, matchTables))
                        .map(
                                lobby ->
                                        recoverOne(
                                                        lobby,
                                                        anchorsByTable.get(lobby.tableId()),
                                                        current.lobbies().orElseThrow())
                                                .toCompletableFuture())
                        .toList();
        return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
    }

    private CompletionStage<Void> recoverOne(
            TableLobby lobby, TableAnchor anchor, LobbyRepositoryPort repository) {
        if (anchor == null) {
            logger.warning("Lobby has no table anchor: " + lobby.tableId());
            return CompletableFuture.completedFuture(null);
        }
        return anchors.restore(anchor)
                .thenApply(
                        ignored -> {
                            hosts.host(
                                    lobby,
                                    anchor,
                                    Optional.of(repository),
                                    starter,
                                    false);
                            return (Void) null;
                        })
                .exceptionally(
                        failure -> {
                            anchors.remove(anchor);
                            logger.log(
                                    Level.WARNING,
                                    "Lobby recovery isolated: " + lobby.tableId(),
                                    unwrap(failure));
                            return null;
                        });
    }

    private boolean keepLobby(TableLobby lobby, Set<TableId> matchTables) {
        if (!matchTables.contains(lobby.tableId())) {
            return true;
        }
        logger.warning("Ignoring stale lobby shadowed by match: " + lobby.tableId());
        return false;
    }

    private CompletionStage<Void> persistInitial(
            Optional<LobbyRepositoryPort> repository,
            TableLobby lobby,
            TableAnchor anchor) {
        return repository.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.runAsync(
                        () -> {
                            try {
                                repository.orElseThrow().create(lobby, anchor);
                            } catch (Exception failure) {
                                throw new CompletionException(failure);
                            }
                        },
                        ioExecutor);
    }

    private void cleanupFailedCreate(LobbyRuntimeServices services, TableId tableId) {
        if (services.lobbies().isEmpty() && services.anchors().isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> deleteDurable(services, tableId), ioExecutor)
                .exceptionally(
                        failure -> {
                            logger.log(
                                    Level.WARNING,
                                    "Could not clean failed lobby creation " + tableId,
                                    unwrap(failure));
                            return null;
                        });
    }

    private static void deleteDurable(LobbyRuntimeServices services, TableId tableId) {
        try {
            if (services.lobbies().isPresent()) {
                services.lobbies().orElseThrow().delete(tableId);
            }
            if (services.anchors().isPresent()) {
                services.anchors().orElseThrow().delete(tableId);
            }
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private LobbyRuntimeServices requireBound() {
        LobbyRuntimeServices current = services.get();
        if (current == UNBOUND) {
            throw new IllegalStateException("Lobby runtime is still initializing");
        }
        return current;
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

    @Override
    public void close() {
        hosts.close();
    }
}
