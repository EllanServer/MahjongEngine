package top.ellan.mahjong.plugin.table;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.table.MatchCompletion;
import top.ellan.mahjong.application.table.MatchCompletionPort;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.domain.lobby.LobbyPhase;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.dialog.MahjongDialogService;
import top.ellan.mahjong.plugin.lobby.LobbyRuntimeCoordinator;
import top.ellan.mahjong.plugin.match.MatchAutomationService;
import top.ellan.mahjong.plugin.match.MatchPersistenceCleanup;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.plugin.platform.CraftEnginePlatformRuntime;
import top.ellan.mahjong.plugin.runtime.FailureSupport;
import top.ellan.mahjong.plugin.runtime.RuntimeServices;
import top.ellan.mahjong.spi.PlayerId;

/** Event-driven lifecycle for exactly one addressed table and its bounded participant roster. */
public final class TableLifecycleCoordinator implements MatchCompletionPort {
    private static final Duration MATCH_REUSE_DELAY = Duration.ofSeconds(3);

    private final MahjongPaperPlugin plugin;
    private final Executor ioExecutor;
    private final Clock clock;
    private final TaskScheduler deadlines;
    private final TableActorRegistry actors;
    private final LiveTableDirectory liveTables;
    private final MatchAutomationService automation;
    private final LobbyRuntimeCoordinator lobbies;
    private final CraftEnginePlatformRuntime platform;
    private final MahjongDialogService dialogs;
    private final Supplier<RuntimeServices> services;
    private final BooleanSupplier stopping;

    public TableLifecycleCoordinator(
            MahjongPaperPlugin plugin,
            Executor ioExecutor,
            Clock clock,
            TaskScheduler deadlines,
            TableActorRegistry actors,
            LiveTableDirectory liveTables,
            MatchAutomationService automation,
            LobbyRuntimeCoordinator lobbies,
            CraftEnginePlatformRuntime platform,
            MahjongDialogService dialogs,
            Supplier<RuntimeServices> services,
            BooleanSupplier stopping) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.liveTables = Objects.requireNonNull(liveTables, "liveTables");
        this.automation = Objects.requireNonNull(automation, "automation");
        this.lobbies = Objects.requireNonNull(lobbies, "lobbies");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.services = Objects.requireNonNull(services, "services");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
    }

    public CompletionStage<TableActionResult> leave(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (lobbies.directory().findByPlayer(playerId).isPresent()) {
            return lobbies.useCases().leave(playerId);
        }
        StartedRulePackMatch match = liveTables.findByPlayer(playerId).orElse(null);
        if (match == null) {
            return rejected("not-at-table");
        }
        ParticipantRole role = roleOf(match, playerId).orElse(null);
        if (role == ParticipantRole.SPECTATOR) {
            liveTables.markDeparting(playerId);
            return accepted(match, "spectator-left");
        }
        if (role != ParticipantRole.PLAYER) {
            return rejected("player-required");
        }
        if (match.actor().snapshot().lifecycle().terminal()) {
            liveTables.markDeparting(playerId);
            return accepted(match, "leave-after-match");
        }
        return automation.setAutomated(playerId, true).thenApply(result -> {
            if (result.code() != TableActionCode.ACCEPTED_MEMORY) {
                return result;
            }
            liveTables.markDeparting(playerId);
            return new TableActionResult(
                    TableActionCode.ACCEPTED_MEMORY, result.revision(), "leave-deferred");
        });
    }

    public CompletionStage<TableActionResult> unspectate(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (lobbies.directory().findByPlayer(playerId).isPresent()) {
            return lobbies.useCases().unspectate(playerId);
        }
        StartedRulePackMatch match = liveTables.findByPlayer(playerId).orElse(null);
        if (match == null || roleOf(match, playerId).orElse(null) != ParticipantRole.SPECTATOR) {
            return rejected("not-spectating");
        }
        liveTables.markDeparting(playerId);
        return accepted(match, "spectator-left");
    }

    public CompletionStage<Void> remove(TableId tableId, RuntimeServices current) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(current, "current");
        Optional<CompletionStage<Void>> lobbyRemoval = lobbies.remove(tableId);
        if (lobbyRemoval.isPresent()) {
            return forgetAfter(lobbyRemoval.orElseThrow(), tableId);
        }
        StartedRulePackMatch match = liveTables.remove(tableId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown live table"));
        actors.remove(tableId, match.actor());
        return match.actor()
                .closeAndDrain()
                .whenComplete((ignored, failure) -> {
                    platform.removeTable(tableId);
                    dialogs.forget(tableId);
                    MatchPersistenceCleanup.releaseRulePackLease(
                            current.rules(), match, tableId);
                })
                .thenCompose(ignored -> CompletableFuture.runAsync(
                        () -> MatchPersistenceCleanup.closeMatch(
                                current.database(), match, Instant.now(clock)),
                        ioExecutor));
    }

    public CompletionStage<Void> removeOwnedLobby(TableId tableId, PlayerId ownerId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(ownerId, "ownerId");
        HostedLobby lobby = lobbies.directory()
                .find(tableId)
                .filter(candidate -> candidate.state().ownerId().equals(ownerId))
                .filter(candidate -> candidate.state().phase() == LobbyPhase.WAITING)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Only the owner may remove a waiting lobby"));
        return forgetAfter(lobbies.remove(lobby.tableId()).orElseThrow(), tableId);
    }

    public boolean isDeparting(TableId tableId, PlayerId playerId) {
        return liveTables.isDeparting(tableId, playerId);
    }

    /** Force-closes one explicitly addressed match, then reopens its retained lobby shell. */
    public CompletionStage<Void> forceEnd(TableId tableId, Set<PlayerId> departedPlayers) {
        StartedRulePackMatch match =
                liveTables
                        .find(Objects.requireNonNull(tableId, "tableId"))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown live table"));
        return forceEnd(tableId, match.binding(), departedPlayers);
    }

    /** Force-closes only the expected match generation, never a reused table's newer match. */
    public CompletionStage<Void> forceEnd(
            TableId tableId, MatchBinding binding, Set<PlayerId> departedPlayers) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(binding, "binding");
        departedPlayers = Set.copyOf(
                Objects.requireNonNull(departedPlayers, "departedPlayers"));
        RuntimeServices current = services.get();
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Mahjong runtime is unavailable"));
        }
        StartedRulePackMatch match =
                liveTables
                        .removeExact(tableId, binding)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown live table generation"));
        actors.remove(tableId, match.actor());
        Set<PlayerId> departed = departedPlayers;
        return match.actor()
                .closeAndDrain()
                .thenCompose(
                        ignored ->
                                CompletableFuture.runAsync(
                                        () ->
                                                MatchPersistenceCleanup.closeMatchRecord(
                                                        current.database(),
                                                        match,
                                                        Instant.now(clock)),
                                        ioExecutor))
                .thenCompose(
                        ignored ->
                                lobbies.reopenAfterMatch(
                                        match.tableId(), match.anchor(), departed))
                .whenComplete(
                        (reopened, failure) ->
                                finishRecycle(current, match, reopened, failure))
                .thenApply(ignored -> null);
    }

    @Override
    public void completed(MatchCompletion completion) {
        if (stopping.getAsBoolean()) {
            return;
        }
        try {
            deadlines.schedule(() -> recycle(completion), MATCH_REUSE_DELAY);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not schedule completed table reuse " + completion.tableId(),
                    failure);
        }
    }

    /** Invoked at the exact recovery boundary; only this match's bounded roster is inspected. */
    public void reconcileRecoveredMatch(StartedRulePackMatch match) {
        Objects.requireNonNull(match, "match");
        runOnGlobalRegion(() -> match.participants().stream()
                .filter(participant -> participant.role() == ParticipantRole.PLAYER)
                .forEach(participant -> {
                    if (plugin.getServer().getPlayer(participant.playerId().value()) == null) {
                        automation.disconnected(participant.playerId());
                    } else {
                        automation.connected(participant.playerId());
                    }
                }));
    }

    private void recycle(MatchCompletion completion) {
        if (stopping.getAsBoolean()) {
            return;
        }
        LiveTableDirectory.Removed removed = liveTables
                .removeCompleted(completion.tableId(), completion.binding())
                .orElse(null);
        if (removed == null) {
            return;
        }
        StartedRulePackMatch match = removed.match();
        actors.remove(match.tableId(), match.actor());
        RuntimeServices current = services.get();
        match.actor()
                .closeAndDrain()
                .thenCompose(ignored -> lobbies.reopenAfterMatch(
                        match.tableId(), match.anchor(), removed.departedPlayers()))
                .whenComplete((reopened, failure) -> finishRecycle(current, match, reopened, failure));
    }

    private void finishRecycle(
            RuntimeServices current,
            StartedRulePackMatch match,
            Optional<HostedLobby> reopened,
            Throwable failure) {
        if (current != null) {
            MatchPersistenceCleanup.releaseRulePackLease(
                    current.rules(), match, match.tableId());
        }
        if (failure != null) {
            dialogs.forget(match.tableId());
            platform.removeTable(match.tableId());
            plugin.getLogger().log(
                    Level.WARNING,
                    "Completed table could not be reopened " + match.tableId(),
                    FailureSupport.unwrap(failure));
            return;
        }
        if (reopened.isEmpty()) {
            dialogs.forget(match.tableId());
            platform.removeTable(match.tableId());
            return;
        }
        dialogs.matchRecycled(match.tableId());
        reconcileReopenedPresence(reopened.orElseThrow());
    }

    private void reconcileReopenedPresence(HostedLobby lobby) {
        runOnGlobalRegion(() -> lobby.state().seats().stream()
                .filter(seat -> seat.occupant().isPresent())
                .filter(seat -> !lobby.state().isBotSeat(seat))
                .map(seat -> seat.occupant().orElseThrow())
                .filter(playerId -> plugin.getServer().getPlayer(playerId.value()) != null)
                .forEach(playerId -> lobbies.seatInteractions().connected(playerId)));
    }

    private void runOnGlobalRegion(Runnable operation) {
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, operation);
        } catch (RuntimeException shuttingDown) {
            plugin.getLogger().fine("Table-local presence reconciliation was skipped");
        }
    }

    private CompletionStage<Void> forgetAfter(CompletionStage<Void> removal, TableId tableId) {
        return removal.whenComplete((ignored, failure) -> {
            if (failure == null) {
                dialogs.forget(tableId);
            }
        });
    }

    private static Optional<ParticipantRole> roleOf(
            StartedRulePackMatch match, PlayerId playerId) {
        return match.participants().stream()
                .filter(participant -> participant.playerId().equals(playerId))
                .map(participant -> participant.role())
                .findFirst();
    }

    private static CompletionStage<TableActionResult> accepted(
            StartedRulePackMatch match, String reason) {
        return CompletableFuture.completedFuture(new TableActionResult(
                TableActionCode.ACCEPTED_MEMORY,
                match.actor().snapshot().revision(),
                reason));
    }

    private static CompletionStage<TableActionResult> rejected(String reason) {
        return CompletableFuture.completedFuture(
                new TableActionResult(TableActionCode.REJECTED_BY_RULES, 0, reason));
    }
}
