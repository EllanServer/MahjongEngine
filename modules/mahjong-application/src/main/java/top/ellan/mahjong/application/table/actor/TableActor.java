package top.ellan.mahjong.application.table.actor;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.automation.TableAutomationEndpoint;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.security.ActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionEndpoint;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.table.TableActorSnapshot;
import top.ellan.mahjong.application.table.TableAuthorityActionEndpoint;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.ScheduledRuleAction;

/** Bounded per-table actor shell; state and pure-rule work remain delegated. */
public final class TableActor
        implements TableActionEndpoint, TableAutomationEndpoint, TableAuthorityActionEndpoint {
    private final Executor dispatcher;
    private final TableActorConfig config;
    private final TableActorInbox inbox;
    private final TableRuleTaskLauncher ruleTasks;
    private final TableActorStateMachine stateMachine;
    private final TableAutomationRoster automation;
    private final TableAuthorityActionController authorityActions;
    private final TableExternalIngressController externalIngress;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<TableActorSnapshot> publishedSnapshot = new AtomicReference<>();
    private final CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();
    private boolean ruleInFlight;
    private boolean automationRefreshPending;

    public TableActor(
            Executor dispatcher,
            FairRuleExecutor ruleExecutor,
            RulePackProvider provider,
            PersistenceOutbox outbox,
            TaskScheduler deadlineScheduler,
            SceneProjectionPort projector,
            TablePresentationCuePort cuePort,
            TableOpeningPresentationPort openingPort,
            boolean presentInitialOpening,
            ActionTokenIssuer tokenIssuer,
            Clock clock,
            TableActorConfig config,
            TableAggregate aggregate,
            RuleState initialRuleState,
            long lastEventSequence) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(initialRuleState, "initialRuleState");
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("lastEventSequence must be non-negative");
        }
        inbox = new TableActorInbox(config.mailboxCapacity());
        authorityActions = new TableAuthorityActionController(
                inbox, closed::get, this::publishedResult, this::scheduleDrain);
        externalIngress = new TableExternalIngressController(
                inbox, closed::get, this::publishedResult, this::scheduleDrain);
        TableScheduledActionController scheduledActions = new TableScheduledActionController(
                Objects.requireNonNull(deadlineScheduler, "deadlineScheduler"),
                inbox,
                this::scheduleDrain);
        stateMachine = new TableActorStateMachine(
                outbox,
                projector,
                Objects.requireNonNull(cuePort, "cuePort"),
                Objects.requireNonNull(openingPort, "openingPort"),
                presentInitialOpening,
                tokenIssuer,
                clock,
                aggregate,
                initialRuleState,
                lastEventSequence,
                scheduledActions);
        automation = new TableAutomationRoster(aggregate.participants());
        ruleTasks = new TableRuleTaskLauncher(
                Objects.requireNonNull(ruleExecutor, "ruleExecutor"),
                provider,
                aggregate.participants(),
                config,
                stateMachine.matchBinding().rulePack().ruleId());
        outbox.setListener(this::signalOutbox);
        publishSnapshot();
    }

    /** Starts initial view and action generation on the fair rule pool. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("actor is closed");
        }
        inbox.requestInitialize();
        scheduleDrain();
    }

    /** O(1), non-blocking ingress method safe for Paper and CraftEngine event threads. */
    public CompletionStage<TableActionResult> submit(PlayerId actor, ActionToken token) {
        return externalIngress.submit(actor, token);
    }

    @Override
    public CompletionStage<TableActionResult> submitAuthority(
            PlayerId authority, long expectedRevision, RuleAction action) {
        return authorityActions.submit(authority, expectedRevision, action);
    }

    @Override
    public CompletionStage<TableActionResult> setAutomated(PlayerId playerId, boolean enabled) {
        return externalIngress.setAutomated(playerId, enabled);
    }

    public TableActorSnapshot snapshot() {
        return publishedSnapshot.get();
    }

    public Optional<TableProjection> latestProjection() {
        return stateMachine.latestProjection();
    }

    private void scheduleDrain() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatcher.execute(this::drain);
        } catch (RejectedExecutionException failure) {
            scheduled.set(false);
            failQueuedActions(TableActionCode.TABLE_BLOCKED, "actor-dispatch-rejected");
        }
    }

    private void drain() {
        int processed = 0;
        try {
            while (processed++ < config.maxMessagesPerRun()) {
                if (inbox.takeCloseRequest()) {
                    handleClose();
                    break;
                }
                RuleTaskCompletion completion = inbox.takeRuleCompletion();
                if (completion != null) {
                    handleRuleCompletion(completion);
                    continue;
                }
                OutboxHealth health = inbox.takeOutboxHealth();
                if (health != null) {
                    stateMachine.handleOutboxHealth(health, ruleInFlight);
                    continue;
                }
                if (inbox.takeInitializeRequest()) {
                    if (!ruleInFlight) {
                        submitFrameComputation();
                    }
                    continue;
                }
                TableIngress ingress = inbox.pollIngress();
                if (ingress == null) {
                    break;
                }
                if (ingress instanceof TableActionEnvelope envelope) {
                    handleAction(envelope);
                } else if (ingress instanceof AutomationControlEnvelope automationControl) {
                    handleAutomationControl(automationControl);
                } else if (ingress instanceof AuthorityActionEnvelope authorityAction) {
                    handleAuthorityAction(authorityAction);
                } else {
                    handleScheduledAction((ScheduledActionTrigger) ingress);
                }
            }
        } finally {
            scheduled.set(false);
            publishSnapshot();
            if (inbox.hasPendingWork()) {
                scheduleDrain();
            }
        }
    }

    private void handleAction(TableActionEnvelope envelope) {
        if (closed.get()) {
            envelope.response().complete(stateMachine.result(TableActionCode.TABLE_CLOSED, "closed"));
            return;
        }
        ActionAdmission admission =
                stateMachine.admit(envelope.actor(), envelope.token(), ruleInFlight);
        if (!admission.accepted()) {
            envelope.response().complete(
                    stateMachine.result(admission.rejectionCode(), admission.reasonCode()));
            return;
        }
        submitTransition(
                envelope.actor(),
                admission.action(),
                Optional.of(envelope),
                Optional.empty(),
                Optional.empty());
    }

    private void handleAuthorityAction(AuthorityActionEnvelope envelope) {
        ActionAdmission admission = authorityActions.admit(envelope, stateMachine, ruleInFlight);
        if (!admission.accepted()) {
            envelope.response().complete(
                    stateMachine.result(admission.rejectionCode(), admission.reasonCode()));
            return;
        }
        submitTransition(
                envelope.authority(),
                envelope.action(),
                Optional.empty(),
                Optional.of(envelope),
                Optional.empty());
    }

    private void handleScheduledAction(ScheduledActionTrigger trigger) {
        if (closed.get()
                || trigger.expectedRevision() != stateMachine.revision()
                || !stateMachine.lifecycle().acceptsRuleActions()
                || ruleInFlight) {
            return;
        }
        ScheduledRuleAction scheduledAction = trigger.scheduledAction();
        submitTransition(
                scheduledAction.actor(),
                scheduledAction.action(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(trigger));
    }

    private void handleAutomationControl(AutomationControlEnvelope envelope) {
        if (closed.get()) {
            envelope.response().complete(stateMachine.result(TableActionCode.TABLE_CLOSED, "closed"));
            return;
        }
        if (!stateMachine.lifecycle().acceptsRuleActions()) {
            envelope.response().complete(
                    stateMachine.result(TableActionCode.TABLE_BLOCKED, "table-not-active"));
            return;
        }
        TableAutomationRoster.Update update =
                automation.update(envelope.playerId(), envelope.enabled());
        if (!update.accepted()) {
            envelope.response().complete(stateMachine.result(
                    TableActionCode.REJECTED_BY_RULES, update.reasonCode()));
            return;
        }
        if (update.changed()) {
            stateMachine.pauseScheduledAction();
            if (ruleInFlight) {
                automationRefreshPending = true;
            } else {
                submitFrameComputation();
            }
        }
        envelope.response().complete(
                stateMachine.result(TableActionCode.ACCEPTED_MEMORY, update.reasonCode()));
    }

    private void submitFrameComputation() {
        ruleInFlight = true;
        try {
            ruleTasks.frame(
                    stateMachine.ruleState(),
                    stateMachine.revision(),
                    automation.automatedPlayers(),
                    this::signalRuleCompletion);
        } catch (RejectedExecutionException failure) {
            ruleInFlight = false;
            stateMachine.recordFailure("rule-pool-saturated");
        }
    }

    private void submitTransition(
            PlayerId actor,
            RuleAction action,
            Optional<TableActionEnvelope> envelope,
            Optional<AuthorityActionEnvelope> authorityEnvelope,
            Optional<ScheduledActionTrigger> scheduledTrigger) {
        stateMachine.pauseScheduledAction();
        ruleInFlight = true;
        try {
            ruleTasks.transition(
                    stateMachine.ruleState(),
                    actor,
                    action,
                    stateMachine.revision(),
                    stateMachine.lastEventSequence(),
                    stateMachine.nextAcceptedAction(),
                    automation.automatedPlayers(),
                    envelope,
                    authorityEnvelope,
                    scheduledTrigger,
                    this::signalRuleCompletion);
        } catch (RejectedExecutionException failure) {
            ruleInFlight = false;
            if (envelope.isPresent()) {
                envelope.orElseThrow().response().complete(stateMachine.result(
                        TableActionCode.RULE_POOL_SATURATED, "rule-pool-saturated"));
            } else if (authorityEnvelope.isPresent()) {
                authorityEnvelope.orElseThrow().response().complete(stateMachine.result(
                        TableActionCode.RULE_POOL_SATURATED, "rule-pool-saturated"));
            } else {
                stateMachine.block("scheduled-rule-pool-saturated");
            }
        }
    }

    private void signalRuleCompletion(RuleTaskCompletion completion) {
        inbox.completeRule(completion);
        scheduleDrain();
    }

    private void signalOutbox(OutboxHealth health) {
        inbox.updateOutbox(health);
        scheduleDrain();
    }

    private void handleRuleCompletion(RuleTaskCompletion completion) {
        ruleInFlight = false;
        if (closed.get()) {
            completion.envelope().ifPresent(value -> value.response().complete(
                    stateMachine.result(TableActionCode.TABLE_CLOSED, "closed")));
            completion.authorityEnvelope().ifPresent(value -> value.response().complete(
                    stateMachine.result(TableActionCode.TABLE_CLOSED, "closed")));
            return;
        }
        stateMachine.handleRuleCompletion(completion);
        if (automationRefreshPending) {
            automationRefreshPending = false;
            if (stateMachine.lifecycle().acceptsRuleActions()) {
                stateMachine.pauseScheduledAction();
                submitFrameComputation();
            }
        }
    }

    private void handleClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (inbox.scheduledTriggerOverflow()) {
            stateMachine.recordFailure("scheduled-trigger-overflow");
        }
        stateMachine.close(inbox.duplicateRuleCompletion(), shutdownComplete);
        TableQueuedResponseDrainer.complete(
                inbox, TableActionCode.TABLE_CLOSED, "closed", stateMachine::result);
    }

    private void failQueuedActions(TableActionCode code, String reason) {
        TableQueuedResponseDrainer.complete(inbox, code, reason, this::publishedResult);
    }

    private TableActionResult publishedResult(TableActionCode code, String reason) {
        TableActorSnapshot snapshot = publishedSnapshot.get();
        return new TableActionResult(
                code,
                snapshot == null ? 0 : snapshot.revision(),
                reason == null ? "" : reason);
    }

    private void publishSnapshot() {
        publishedSnapshot.set(stateMachine.snapshot(inbox.actionCount(), ruleInFlight));
    }

    @Override
    public void close() {
        inbox.requestClose();
        scheduleDrain();
    }

    /** Completes after this table's persistence outbox drains. */
    public CompletionStage<Void> closeAndDrain() {
        close();
        return shutdownComplete;
    }
}
