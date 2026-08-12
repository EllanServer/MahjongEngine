package top.ellan.mahjong.plugin.match;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.table.TableActionEndpoint;
import top.ellan.mahjong.domain.match.CompetitionRef;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.persistence.sql.event.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;
import top.ellan.mahjong.persistence.sql.recovery.MatchRecoveryData;
import top.ellan.mahjong.persistence.sql.recovery.RuleStateReplayVerifier;
import top.ellan.mahjong.persistence.sql.recovery.VerifiedRecovery;
import top.ellan.mahjong.runtime.lifecycle.RulePackRuntime;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;

/** Coordinates durable match creation and recovery without owning actor construction details. */
public final class RulePackMatchCoordinator {
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(30);

    private final Executor ioExecutor;
    private final FairRuleExecutor rules;
    private final JdbcMatchRepository matches;
    private final JdbcEventStore events;
    private final RulePackRuntime rulePacks;
    private final MatchActorFactory actorFactory;
    private final Clock clock;

    public RulePackMatchCoordinator(
            Executor actorDispatcher,
            Executor ioExecutor,
            FairRuleExecutor rules,
            FairRuleExecutor automation,
            TaskScheduler deadlines,
            TableActorRegistry actors,
            JdbcMatchRepository matches,
            JdbcEventStore events,
            RulePackRuntime rulePacks,
            SceneProjectionPort projector,
            TablePresentationCuePort presentationCues,
            TableOpeningPresentationPort openingPresentations,
            Clock clock) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.events = Objects.requireNonNull(events, "events");
        this.rulePacks = Objects.requireNonNull(rulePacks, "rulePacks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actorFactory =
                new MatchActorFactory(
                        actorDispatcher,
                        ioExecutor,
                        rules,
                        automation,
                        deadlines,
                        actors,
                        matches,
                        events,
                        projector,
                        presentationCues,
                        openingPresentations,
                        clock);
    }

    public CompletionStage<StartedRulePackMatch> create(NewRulePackMatch command) {
        return create(command, Optional.empty(), false);
    }

    public CompletionStage<StartedRulePackMatch> createFromLobby(
            NewRulePackMatch command, TableActionEndpoint lobbyEndpoint) {
        Objects.requireNonNull(lobbyEndpoint, "lobbyEndpoint");
        return create(command, Optional.of(lobbyEndpoint), true);
    }

    private CompletionStage<StartedRulePackMatch> create(
            NewRulePackMatch command,
            Optional<TableActionEndpoint> replacedEndpoint,
            boolean consumeLobby) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(replacedEndpoint, "replacedEndpoint");
        if (!events.available()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Database is unavailable; new recoverable matches are disabled"));
        }
        RulePackProvider provider =
                rulePacks
                        .providerForNewMatch(command.ruleId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No active rule pack for " + command.ruleId()));
        RulePackRef reference = rulePacks.activeReference(command.ruleId()).orElseThrow();
        // Lease the generation this match is bound to so a running replacement cannot unload it
        // while the match is still playing.
        rulePacks.acquire(reference, command.tableId());
        return rules.submit(
                        command.ruleId(),
                        INITIALIZATION_TIMEOUT,
                        () -> RuleMatchStateFactory.initialize(command, provider, reference, clock))
                .thenCompose(
                        initialized ->
                                persistAndStart(
                                        command,
                                        provider,
                                        initialized,
                                        replacedEndpoint,
                                        consumeLobby))
                .whenComplete(
                        (started, failure) -> {
                            if (failure != null) {
                                rulePacks.release(reference, command.tableId());
                            }
                        });
    }

    public CompletionStage<StartedRulePackMatch> recover(
            MatchId matchId, CompetitionRef competitionRef) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(competitionRef, "competitionRef");
        CompletionStage<VerifiedInput> verified =
                CompletableFuture.supplyAsync(
                                () -> loadRecoveryInput(matchId),
                                ioExecutor)
                        .thenCompose(
                                input ->
                                        rules.submit(
                                                        input.data()
                                                                .match()
                                                                .binding()
                                                                .rulePack()
                                                                .ruleId(),
                                                        RECOVERY_TIMEOUT,
                                                        () ->
                                                                RuleStateReplayVerifier.replay(
                                                                        input.provider(),
                                                                        input.data()))
                                                .thenApply(result -> new VerifiedInput(input, result)));
        return markRulePackBlockedOnFailure(matchId, verified)
                .thenCompose(input -> restoreActor(input, competitionRef));
    }

    private RecoveryInput loadRecoveryInput(MatchId matchId) {
        try {
            MatchRecoveryData data = matches.recover(matchId);
            RulePackRef pinned = data.match().binding().rulePack();
            RulePackProvider provider = rulePacks.providerForPinnedMatch(pinned);
            // A recovered match may pin a superseded generation; lease it the same way.
            rulePacks.acquire(pinned, data.match().tableId());
            return new RecoveryInput(data, provider);
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private CompletionStage<StartedRulePackMatch> persistAndStart(
            NewRulePackMatch command,
            RulePackProvider provider,
            RuleMatchStateFactory.Initialized initialized,
            Optional<TableActionEndpoint> replacedEndpoint,
            boolean consumeLobby) {
        MatchInstanceRecord metadata =
                new MatchInstanceRecord(
                        initialized.binding(),
                        command.tableId(),
                        TableLifecycle.ACTIVE,
                        initialized.binding().createdAt(),
                        0);
        return CompletableFuture.runAsync(
                        () -> persistInitialMatch(command, initialized, metadata, consumeLobby),
                        ioExecutor)
                .thenCompose(
                        ignored ->
                                actorFactory.createOrMarkReview(
                                        command.tableId(),
                                        command.participants(),
                                        command.competitionRef(),
                                        provider,
                                        initialized.binding(),
                                        initialized.state(),
                                        TableLifecycle.ACTIVE,
                                        0,
                                        0,
                                        replacedEndpoint,
                                        true));
    }

    private void persistInitialMatch(
            NewRulePackMatch command,
            RuleMatchStateFactory.Initialized initialized,
            MatchInstanceRecord metadata,
            boolean consumeLobby) {
        try {
            if (consumeLobby) {
                matches.createRecoverableMatchFromLobby(
                        metadata,
                        command.participants(),
                        initialized.snapshot(),
                        command.anchor());
            } else {
                matches.createRecoverableMatch(
                        metadata,
                        command.participants(),
                        initialized.snapshot(),
                        command.anchor());
            }
        } catch (java.sql.SQLException failure) {
            throw new CompletionException(failure);
        }
    }

    private CompletionStage<StartedRulePackMatch> restoreActor(
            VerifiedInput input, CompetitionRef competitionRef) {
        MatchRecoveryData data = input.recovery().data();
        if (data.participants().isEmpty()) {
            return CompletableFuture.supplyAsync(
                    () -> failRecoveryWithoutParticipants(data),
                    ioExecutor);
        }
        return CompletableFuture.runAsync(
                        () -> markActive(data),
                        ioExecutor)
                .thenCompose(
                        ignored ->
                                actorFactory.createOrMarkReview(
                                        data.match().tableId(),
                                        data.participants(),
                                        competitionRef,
                                        input.recovery().provider(),
                                        data.match().binding(),
                                        input.verified().state(),
                                        TableLifecycle.ACTIVE,
                                        input.verified().stateRevision(),
                                        input.verified().eventSequence(),
                                        Optional.empty(),
                                        false));
    }

    private StartedRulePackMatch failRecoveryWithoutParticipants(MatchRecoveryData data) {
        try {
            matches.updateStatus(
                    data.match().binding().matchId(),
                    TableLifecycle.NEEDS_ADMIN_REVIEW,
                    Instant.now(clock));
        } catch (java.sql.SQLException failure) {
            throw new CompletionException(failure);
        }
        throw new CompletionException(
                new IllegalStateException("Recovered match has no participants"));
    }

    private void markActive(MatchRecoveryData data) {
        try {
            matches.updateStatus(
                    data.match().binding().matchId(),
                    TableLifecycle.ACTIVE,
                    Instant.now(clock));
        } catch (java.sql.SQLException failure) {
            throw new CompletionException(failure);
        }
    }

    private <T> CompletionStage<T> markRulePackBlockedOnFailure(
            MatchId matchId, CompletionStage<T> source) {
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete(
                (value, failure) -> {
                    if (failure == null) {
                        result.complete(value);
                        return;
                    }
                    Throwable cause = unwrap(failure);
                    CompletableFuture.runAsync(
                                    () -> markBlocked(matchId, cause),
                                    ioExecutor)
                            .whenComplete(
                                    (ignored, updateFailure) -> {
                                        if (updateFailure != null) {
                                            cause.addSuppressed(unwrap(updateFailure));
                                        }
                                        result.completeExceptionally(cause);
                                    });
                });
        return result;
    }

    private void markBlocked(MatchId matchId, Throwable cause) {
        try {
            matches.updateStatus(
                    matchId,
                    TableLifecycle.BLOCKED_RULE_PACK,
                    Instant.now(clock));
        } catch (java.sql.SQLException updateFailure) {
            cause.addSuppressed(updateFailure);
        }
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

    private record RecoveryInput(MatchRecoveryData data, RulePackProvider provider) {
        private RecoveryInput {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(provider, "provider");
        }
    }

    private record VerifiedInput(RecoveryInput recovery, VerifiedRecovery verified) {
        private VerifiedInput {
            Objects.requireNonNull(recovery, "recovery");
            Objects.requireNonNull(verified, "verified");
        }
    }
}
