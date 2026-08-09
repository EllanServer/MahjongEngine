package top.ellan.mahjong.application.table.actor;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.security.ActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorSnapshot;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.ScheduledRuleAction;
import top.ellan.mahjong.spi.TransitionDisposition;

/** Single-writer match state, persistence commit and projection lifecycle. */
final class TableActorStateMachine {
    private final PersistenceOutbox outbox;
    private final ProjectionAuthorizationService authorization;
    private final AcceptedTransitionWriter transitionWriter;
    private final TableProjectionPublisher projections;
    private final TableScheduledActionController scheduledActions;
    private final TablePresentationCuePublisher presentationCues;
    private final TableOpeningPublisher openings;
    private final Map<UUID, AuthorizedAction> actionCatalog = new HashMap<>();
    private TableAggregate aggregate;
    private RuleState ruleState;
    private OutboxHealth outboxHealth;
    private long lastEventSequence;
    private long acceptedActions;
    private String failureCode = "";

    TableActorStateMachine(
            PersistenceOutbox outbox,
            SceneProjectionPort projector,
            TablePresentationCuePort cuePort,
            TableOpeningPresentationPort openingPort,
            boolean presentInitialOpening,
            ActionTokenIssuer tokenIssuer,
            Clock clock,
            TableAggregate aggregate,
            RuleState initialRuleState,
            long lastEventSequence,
            TableScheduledActionController scheduledActions) {
        this.outbox = outbox;
        authorization = new ProjectionAuthorizationService(tokenIssuer);
        transitionWriter = new AcceptedTransitionWriter(clock);
        projections = new TableProjectionPublisher(projector);
        this.aggregate = aggregate;
        ruleState = initialRuleState;
        this.lastEventSequence = lastEventSequence;
        this.scheduledActions = scheduledActions;
        presentationCues = new TablePresentationCuePublisher(
                cuePort,
                aggregate,
                aggregate.matchBinding().orElseThrow().rulePack().ruleId());
        openings = new TableOpeningPublisher(
                openingPort,
                aggregate,
                aggregate.matchBinding().orElseThrow().rulePack().ruleId(),
                presentInitialOpening);
        outboxHealth = outbox.health();
    }

    ActionAdmission admit(PlayerId actor, ActionToken token, boolean ruleInFlight) {
        return TableActionAdmission.evaluate(
                aggregate, ruleInFlight, actor, token, actionCatalog, failureCode);
    }

    void handleRuleCompletion(RuleTaskCompletion completion) {
        if (completion.expectedRevision() != aggregate.revision()) {
            completion.envelope().ifPresent(value -> value.response().complete(
                    result(TableActionCode.STALE_TOKEN, "revision-advanced")));
            return;
        }
        if (completion.failure() != null || completion.computed() == null) {
            String failure = completion.failure() == null
                    ? "null-rule-result"
                    : completion.failure().getClass().getSimpleName();
            block(failure);
            completion.envelope().ifPresent(value -> value.response().complete(
                    result(TableActionCode.RULE_PACK_FAILURE, failureCode)));
            return;
        }
        RuleComputation computed = completion.computed();
        RuleTransition transition = computed.transition();
        if (transition == null) {
            installFrame(computed.frame(), false);
            return;
        }
        if (!transition.accepted()) {
            rejectTransition(completion, computed, transition);
            return;
        }
        commitTransition(completion, computed, transition);
    }

    void handleOutboxHealth(OutboxHealth health, boolean ruleInFlight) {
        outboxHealth = health;
        if (aggregate.lifecycle() == TableLifecycle.ACTIVE && health.paused()) {
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
            scheduledActions.pause();
            republishLifecycle();
        } else if (aggregate.lifecycle() == TableLifecycle.PAUSED_PERSISTENCE && !health.paused()) {
            aggregate = aggregate.withLifecycle(TableLifecycle.ACTIVE);
            republishLifecycle();
            scheduledActions
                    .resume(aggregate.revision(), !ruleInFlight)
                    .ifPresent(this::block);
        }
    }

    void close(
            boolean duplicateRuleCompletion,
            TableActorInbox inbox,
            CompletableFuture<Void> shutdownComplete) {
        if (duplicateRuleCompletion) {
            failureCode = "duplicate-rule-completion";
        }
        actionCatalog.clear();
        scheduledActions.clear();
        TableActionEnvelope envelope;
        while ((envelope = inbox.pollActionForClose()) != null) {
            envelope.response().complete(result(TableActionCode.TABLE_CLOSED, "closed"));
        }
        outbox.close();
        outbox.awaitDrained().whenComplete((ignored, failure) -> {
            if (failure == null) {
                shutdownComplete.complete(null);
            } else {
                shutdownComplete.completeExceptionally(failure);
            }
        });
    }

    void block(String reason) {
        scheduledActions.pause();
        failureCode = reason;
        aggregate = aggregate.withLifecycle(TableLifecycle.BLOCKED_RULE_PACK);
        actionCatalog.clear();
        republishLifecycle();
    }

    void recordFailure(String failure) {
        failureCode = failure;
    }

    TableActionResult result(TableActionCode code, String reason) {
        return new TableActionResult(code, aggregate.revision(), reason == null ? "" : reason);
    }

    TableActorSnapshot snapshot(int mailboxSize, boolean ruleInFlight) {
        return new TableActorSnapshot(
                aggregate.tableId(),
                aggregate.revision(),
                aggregate.lifecycle(),
                mailboxSize,
                ruleInFlight,
                outboxHealth,
                failureCode);
    }

    Optional<TableProjection> latestProjection() {
        return projections.latest();
    }

    MatchBinding matchBinding() {
        return aggregate.matchBinding().orElseThrow(
                () -> new IllegalStateException("Active rule-pack table has no match binding"));
    }

    RuleState ruleState() {
        return ruleState;
    }

    long revision() {
        return aggregate.revision();
    }

    long lastEventSequence() {
        return lastEventSequence;
    }

    long nextAcceptedAction() {
        return acceptedActions + 1;
    }

    TableLifecycle lifecycle() {
        return aggregate.lifecycle();
    }

    void pauseScheduledAction() {
        scheduledActions.pause();
    }

    private void rejectTransition(
            RuleTaskCompletion completion,
            RuleComputation computed,
            RuleTransition transition) {
        if (completion.scheduledTrigger().isPresent()) {
            block("scheduled-action-rejected-" + transition.reasonCode());
            return;
        }
        TableActionEnvelope envelope = completion.envelope().orElseThrow();
        installFrame(computed.frame(), false);
        envelope.response().complete(
                result(TableActionCode.REJECTED_BY_RULES, transition.reasonCode()));
    }

    private void commitTransition(
            RuleTaskCompletion completion,
            RuleComputation computed,
            RuleTransition transition) {
        Optional<TableActionEnvelope> envelope = completion.envelope();
        PlayerId actor;
        RuleAction action;
        if (envelope.isPresent()) {
            TableActionEnvelope playerAction = envelope.orElseThrow();
            actor = playerAction.actor();
            action = actionCatalog
                    .get(playerAction.token().value())
                    .legalAction()
                    .action();
        } else {
            ScheduledRuleAction scheduled = completion.scheduledTrigger()
                    .orElseThrow()
                    .scheduledAction();
            actor = scheduled.actor();
            action = scheduled.action();
        }
        TransitionWrite write = transitionWriter.offer(
                outbox,
                matchBinding(),
                aggregate.revision(),
                lastEventSequence,
                actor,
                action,
                computed);
        if (!write.accepted()) {
            if (write.status() == TransitionWriteStatus.FAILED) {
                failureCode = write.failureCode();
            }
            outboxHealth = write.health();
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
            envelope.ifPresent(value -> value.response().complete(
                    result(TableActionCode.TABLE_PAUSED, write.failureCode())));
            republishLifecycle();
            return;
        }
        ruleState = computed.state();
        aggregate = aggregate.withRevision(aggregate.revision() + 1);
        acceptedActions++;
        lastEventSequence = write.resultingSequence();
        outboxHealth = write.health();
        if (transition.disposition() == TransitionDisposition.MATCH_ENDED) {
            aggregate = aggregate.withLifecycle(TableLifecycle.FINISHED);
        } else if (write.health().paused()) {
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
        }
        installFrame(computed.frame(), false);
        presentationCues.publish(aggregate.revision(), transition.presentationCues());
        envelope.ifPresent(value -> value.response().complete(
                result(TableActionCode.ACCEPTED_MEMORY, "accepted-memory-first")));
    }

    private void installFrame(RuleFrame frame, boolean ruleInFlight) {
        AuthorizedProjection authorized = authorization.authorize(aggregate, frame);
        actionCatalog.clear();
        actionCatalog.putAll(authorized.actionCatalog());
        recordProjectionFailure(projections.install(authorized.projection()));
        openings.publishIfChanged(
                aggregate.revision(), frame.publicView().tablePresentation().opening());
        scheduledActions
                .install(
                        frame.scheduledAction(),
                        aggregate.revision(),
                        aggregate.lifecycle().acceptsRuleActions() && !ruleInFlight)
                .ifPresent(this::block);
    }

    private void republishLifecycle() {
        recordProjectionFailure(projections.republishLifecycle(aggregate.lifecycle()));
    }

    private void recordProjectionFailure(String projectionFailure) {
        if (!projectionFailure.isEmpty()) {
            failureCode = projectionFailure;
        }
    }
}
