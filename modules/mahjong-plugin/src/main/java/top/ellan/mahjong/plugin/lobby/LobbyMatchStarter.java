package top.ellan.mahjong.plugin.lobby;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.table.TableActionEndpoint;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.application.lobby.port.LobbyStartPort;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableLobby;
import top.ellan.mahjong.plugin.LiveTableDirectory;
import top.ellan.mahjong.plugin.NewRulePackMatch;
import top.ellan.mahjong.plugin.StartedRulePackMatch;
import top.ellan.mahjong.spi.MatchSeed;

/** Performs the atomic lobby-to-pinned-rule-match handoff and fail-closed recovery. */
public final class LobbyMatchStarter implements LobbyStartPort {
    private final LobbyHostRegistry hosts;
    private final LiveTableDirectory liveMatches;
    private final TableActorRegistry actors;
    private final Executor ioExecutor;
    private final Clock clock;
    private final Logger logger;
    private final Supplier<LobbyRuntimeServices> services;

    public LobbyMatchStarter(
            LobbyHostRegistry hosts,
            LiveTableDirectory liveMatches,
            TableActorRegistry actors,
            Executor ioExecutor,
            Clock clock,
            Logger logger,
            Supplier<LobbyRuntimeServices> services) {
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.liveMatches = Objects.requireNonNull(liveMatches, "liveMatches");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override
    public void requestStart(TableLobby lobby, TableActionEndpoint lobbyActor) {
        HostedLobby hosted =
                hosts.directory()
                        .find(lobby.tableId())
                        .filter(candidate -> candidate.actor() == lobbyActor)
                        .orElseThrow(() -> new IllegalStateException("Lobby is no longer hosted"));
        LobbyRuntimeServices current = services.get();
        var coordinator =
                current.matchCoordinator()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Database or rule runtime is unavailable"));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        NewRulePackMatch command =
                new NewRulePackMatch(
                        lobby.tableId(),
                        lobby.ruleId(),
                        lobby.profileId(),
                        new MatchSeed(random.nextLong(), random.nextLong()),
                        lobby.matchParticipants(),
                        lobby.configuration(),
                        CompetitionRef.none(),
                        hosted.anchor());
        coordinator.createFromLobby(command, lobbyActor)
                .whenComplete(
                        (started, failure) -> {
                            if (failure == null) {
                                activationSucceeded(hosted, started, current);
                            } else {
                                activationFailed(hosted, unwrap(failure), current);
                            }
                        });
    }

    private void activationSucceeded(
            HostedLobby lobby,
            StartedRulePackMatch started,
            LobbyRuntimeServices services) {
        if (liveMatches.registerRecovered(started)) {
            hosts.activated(lobby);
            return;
        }
        actors.remove(started.tableId(), started.actor());
        started.actor().close();
        hosts.failClosed(lobby, "active table ownership conflict");
        services.matches()
                .ifPresent(
                        matches ->
                                CompletableFuture.runAsync(
                                        () -> {
                                            try {
                                                matches.updateStatus(
                                                        started.binding().matchId(),
                                                        TableLifecycle.NEEDS_ADMIN_REVIEW,
                                                        Instant.now(clock));
                                            } catch (SQLException failure) {
                                                logger.log(
                                                        Level.WARNING,
                                                        "Could not mark activation conflict",
                                                        failure);
                                            }
                                        },
                                        ioExecutor));
    }

    private void activationFailed(
            HostedLobby lobby,
            Throwable activationFailure,
            LobbyRuntimeServices services) {
        Optional<LobbyRepositoryPort> repository = services.lobbies();
        if (repository.isEmpty()) {
            lobby.actor().startFailed("match-start-unavailable");
            return;
        }
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return repository.orElseThrow().find(lobby.tableId()).isPresent();
                            } catch (Exception lookupFailure) {
                                activationFailure.addSuppressed(lookupFailure);
                                return false;
                            }
                        },
                        ioExecutor)
                .thenAccept(
                        durableLobbyExists -> {
                            if (durableLobbyExists) {
                                lobby.actor().startFailed("match-start-failed");
                            } else {
                                hosts.failClosed(lobby, "durable lobby was consumed");
                            }
                            logger.log(
                                    Level.WARNING,
                                    "Lobby start isolated: " + lobby.tableId(),
                                    activationFailure);
                        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
