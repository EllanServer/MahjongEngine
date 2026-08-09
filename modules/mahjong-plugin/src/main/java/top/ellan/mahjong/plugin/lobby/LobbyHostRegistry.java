package top.ellan.mahjong.plugin.lobby;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.security.SecureActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.lobby.actor.LobbyTableActor;
import top.ellan.mahjong.application.lobby.command.LobbyReducer;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.application.lobby.port.LobbyStartPort;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.lobby.runtime.LatestLobbySnapshotWriter;
import top.ellan.mahjong.application.lobby.runtime.LobbyTableDirectory;
import top.ellan.mahjong.craftengine.CraftEngineSceneBackend;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLobby;
import top.ellan.mahjong.platform.paper.PaperTableAnchorService;
import top.ellan.mahjong.presentation.LatestSceneProjector;

/** Owns hosted lobby actors, coalescing writers, and scene cleanup as one lifecycle unit. */
public final class LobbyHostRegistry implements AutoCloseable {
    private final Executor actorExecutor;
    private final Executor ioExecutor;
    private final TableActorRegistry actors;
    private final SceneProjectionPort projections;
    private final LatestSceneProjector sceneProjector;
    private final CraftEngineSceneBackend sceneBackend;
    private final PaperTableAnchorService anchors;
    private final Clock clock;
    private final Logger logger;
    private final LobbyTableDirectory directory = new LobbyTableDirectory();
    private final ConcurrentHashMap<TableId, LatestLobbySnapshotWriter> writers =
            new ConcurrentHashMap<>();

    public LobbyHostRegistry(
            Executor actorExecutor,
            Executor ioExecutor,
            TableActorRegistry actors,
            SceneProjectionPort projections,
            LatestSceneProjector sceneProjector,
            CraftEngineSceneBackend sceneBackend,
            PaperTableAnchorService anchors,
            Clock clock,
            Logger logger) {
        this.actorExecutor = Objects.requireNonNull(actorExecutor, "actorExecutor");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.sceneProjector = Objects.requireNonNull(sceneProjector, "sceneProjector");
        this.sceneBackend = Objects.requireNonNull(sceneBackend, "sceneBackend");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LobbyTableDirectory directory() {
        return directory;
    }

    public HostedLobby host(
            TableLobby initial,
            TableAnchor anchor,
            Optional<LobbyRepositoryPort> repository,
            LobbyStartPort starter,
            boolean reserved) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(starter, "starter");
        LatestLobbySnapshotWriter writer = writer(initial.tableId(), repository);
        LobbyTableActor actor =
                new LobbyTableActor(
                        actorExecutor,
                        projections,
                        new SecureActionTokenIssuer(),
                        new LobbyReducer(),
                        state -> {
                            directory.changed(state);
                            if (writer != null) {
                                writer.changed(state);
                            }
                        },
                        starter,
                        TableActorConfig.DEFAULT,
                        initial);
        HostedLobby hosted = new HostedLobby(anchor, actor);
        boolean actorRegistered = false;
        boolean directoryRegistered = false;
        try {
            actors.register(initial.tableId(), actor);
            actorRegistered = true;
            if (reserved) {
                directory.completeReservation(hosted);
            } else if (!directory.register(hosted)) {
                throw new IllegalStateException("Lobby conflicts with an already hosted player");
            }
            directoryRegistered = true;
            if (writer != null && writers.putIfAbsent(initial.tableId(), writer) != null) {
                throw new IllegalStateException("Lobby writer already exists");
            }
            actor.start();
            return hosted;
        } catch (RuntimeException failure) {
            if (directoryRegistered) {
                directory.remove(initial.tableId());
            }
            if (actorRegistered) {
                actors.remove(initial.tableId(), actor);
            }
            if (writer != null) {
                writers.remove(initial.tableId(), writer);
                writer.close();
            }
            actor.close();
            throw failure;
        }
    }

    public void activated(HostedLobby lobby) {
        Objects.requireNonNull(lobby, "lobby");
        directory.remove(lobby.tableId());
        closeWriter(lobby.tableId());
        lobby.actor().close();
    }

    public void failClosed(HostedLobby lobby, String reason) {
        Objects.requireNonNull(lobby, "lobby");
        directory.remove(lobby.tableId());
        actors.remove(lobby.tableId(), lobby.actor());
        closeWriter(lobby.tableId());
        lobby.actor().close();
        sceneProjector.remove(lobby.tableId());
        sceneBackend.removeTable(lobby.tableId());
        anchors.remove(lobby.anchor());
        logger.warning("Lobby " + lobby.tableId() + " closed: " + reason);
    }

    public Optional<HostedLobby> detach(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        Optional<HostedLobby> lobby = directory.remove(tableId);
        lobby.ifPresent(
                hosted -> {
                    actors.remove(tableId, hosted.actor());
                    closeWriter(tableId);
                });
        return lobby;
    }

    public void removeSceneAndAnchor(HostedLobby lobby) {
        sceneProjector.remove(lobby.tableId());
        sceneBackend.removeTable(lobby.tableId());
        anchors.remove(lobby.anchor());
    }

    private LatestLobbySnapshotWriter writer(
            TableId tableId, Optional<LobbyRepositoryPort> repository) {
        return repository
                .map(
                        store ->
                                new LatestLobbySnapshotWriter(
                                        ioExecutor,
                                        store,
                                        clock,
                                        failure ->
                                                logger.log(
                                                        Level.WARNING,
                                                        "Lobby persistence isolated: " + tableId,
                                                        failure)))
                .orElse(null);
    }

    private void closeWriter(TableId tableId) {
        LatestLobbySnapshotWriter writer = writers.remove(tableId);
        if (writer != null) {
            writer.close();
        }
    }

    @Override
    public void close() {
        writers.values().forEach(LatestLobbySnapshotWriter::close);
        writers.clear();
    }
}
