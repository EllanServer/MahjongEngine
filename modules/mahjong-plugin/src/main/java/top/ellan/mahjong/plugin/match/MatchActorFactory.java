package top.ellan.mahjong.plugin.match;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.security.SecureActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.application.table.TableActionEndpoint;
import top.ellan.mahjong.application.table.actor.TableActor;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.TableAggregate;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.persistence.sql.event.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;

/** Constructs one table actor and owns atomic registry replacement at match activation. */
final class MatchActorFactory {
    private final Executor actorDispatcher;
    private final Executor ioExecutor;
    private final FairRuleExecutor rules;
    private final TaskScheduler deadlines;
    private final TableActorRegistry actors;
    private final JdbcMatchRepository matches;
    private final JdbcEventStore events;
    private final SceneProjectionPort projector;
    private final Clock clock;

    MatchActorFactory(
            Executor actorDispatcher,
            Executor ioExecutor,
            FairRuleExecutor rules,
            TaskScheduler deadlines,
            TableActorRegistry actors,
            JdbcMatchRepository matches,
            JdbcEventStore events,
            SceneProjectionPort projector,
            Clock clock) {
        this.actorDispatcher = Objects.requireNonNull(actorDispatcher, "actorDispatcher");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.events = Objects.requireNonNull(events, "events");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CompletionStage<StartedRulePackMatch> createOrMarkReview(
            TableId tableId,
            List<TableParticipant> participants,
            CompetitionRef competitionRef,
            RulePackProvider provider,
            MatchBinding binding,
            RuleState state,
            TableLifecycle lifecycle,
            long stateRevision,
            long eventSequence,
            Optional<TableActionEndpoint> replacedEndpoint) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        TableActor actor =
                                createActor(
                                        tableId,
                                        participants,
                                        competitionRef,
                                        provider,
                                        binding,
                                        state,
                                        lifecycle,
                                        stateRevision,
                                        eventSequence,
                                        replacedEndpoint);
                        return new StartedRulePackMatch(binding, tableId, participants, actor);
                    } catch (RuntimeException failure) {
                        markReview(binding, failure);
                        throw failure;
                    }
                },
                ioExecutor);
    }

    private TableActor createActor(
            TableId tableId,
            List<TableParticipant> participants,
            CompetitionRef competitionRef,
            RulePackProvider provider,
            MatchBinding binding,
            RuleState state,
            TableLifecycle lifecycle,
            long stateRevision,
            long eventSequence,
            Optional<TableActionEndpoint> replacedEndpoint) {
        TableAggregate aggregate =
                new TableAggregate(
                        tableId,
                        stateRevision,
                        lifecycle,
                        participants,
                        Optional.of(binding),
                        competitionRef);
        PersistenceOutbox outbox =
                new PersistenceOutbox(
                        binding.matchId(), eventSequence, events, deadlines, clock);
        TableActor actor =
                new TableActor(
                        actorDispatcher,
                        rules,
                        provider,
                        outbox,
                        projector,
                        new SecureActionTokenIssuer(),
                        clock,
                        TableActorConfig.DEFAULT,
                        aggregate,
                        state,
                        eventSequence);
        try {
            register(tableId, replacedEndpoint, actor);
            actor.start();
            return actor;
        } catch (RuntimeException failure) {
            actors.remove(tableId, actor);
            actor.close();
            throw failure;
        }
    }

    private void register(
            TableId tableId,
            Optional<TableActionEndpoint> replacedEndpoint,
            TableActor actor) {
        if (replacedEndpoint.isPresent()) {
            if (!actors.replace(tableId, replacedEndpoint.orElseThrow(), actor)) {
                throw new IllegalStateException("Lobby actor was replaced during activation");
            }
            return;
        }
        actors.register(tableId, actor);
    }

    private void markReview(MatchBinding binding, RuntimeException failure) {
        try {
            matches.updateStatus(
                    binding.matchId(),
                    TableLifecycle.NEEDS_ADMIN_REVIEW,
                    Instant.now(clock));
        } catch (java.sql.SQLException updateFailure) {
            failure.addSuppressed(updateFailure);
        }
    }
}
