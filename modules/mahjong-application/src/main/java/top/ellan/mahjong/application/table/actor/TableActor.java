package top.ellan.mahjong.application.table.actor;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.concurrent.RulePackCircuitOpenException;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.automation.TableAutomationEndpoint;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.security.ActionTokenIssuer;
import top.ellan.mahjong.application.table.MatchCompletionPort;
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
    private final TableActorAutomationController automation;
    private final TableAuthorityActionController authorityActions;
    private final TableExternalIngressController externalIngress;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final TableActorSnapshotPublisher snapshots = new TableActorSnapshotPublisher();
    private final CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();
    private boolean ruleInFlight;

    public TableActor(
            Executor dispatcher,
            FairRuleExecutor ruleExecutor,
            FairRuleExecutor automationExecutor,
            RulePackProvider provider,
            PersistenceOutbox outbox,
            TaskScheduler deadlineScheduler,
            SceneProjectionPort projector,
            TablePresentationCuePort cuePort,
            TableOpeningPresentationPort openingPort,
            boolean presentInitialOpening,
            MatchCompletionPort completionPort,
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
                inbox, closed::get, snapshots::result, this::scheduleDrain);
        externalIngress = new TableExternalIngressController(
                inbox, closed::get, snapshots::result, this::scheduleDrain);
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
                Objects.requireNonNull(completionPort, "completionPort"),
                tokenIssuer,
                clock,
                aggregate,
                initialRuleState,
                lastEventSequence,
                scheduledActions);
        automation = new TableActorAutomationController(
                aggregate.participants(), deadlineScheduler, inbox, this::scheduleDrain);
        ruleTasks = new TableRuleTaskLauncher(
                Objects.requireNonNull(ruleExecutor, "ruleExecutor"),
                Objects.requireNonNull(automationExecutor, "automationExecutor"),
                provider,
                aggregate.participants(),
                config,
                stateMachine.matchBinding().rulePack().ruleId());
        outbox.setListener(this::signalOutbox);
        snapshots.publish(stateMachine, inbox.actionCount(), ruleInFlight);
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
    public boolean isAutomated(PlayerId playerId) { return automation.isAutomated(playerId); }
    public TableActorSnapshot snapshot() {
        return snapshots.current();
    }

    public Optional<TableProjection> latestProjection() {
        return stateMachine.latestProjection();
    }

    private void scheduleDrain() {        if (!scheduled.compareAndSet(false, true)) {
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
                } else if (ingress instanceof HumanDecisionTimeoutTrigger humanTimeout) {
                    handleHumanDecisionTimeout(humanTimeout);
                } else {
                    handleScheduledAction((ScheduledActionTrigger) ingress);
                }
            }
        } finally {
            scheduled.set(false);
            snapshots.publish(stateMachine, inbox.actionCount(), ruleInFlight);
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
            envelope.response().complete(stateMachine.result(admission.rejectionCode(), admission.reasonCode()));
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
            envelope.response().complete(stateMachine.result(admission.rejectionCode(), admission.reasonCode()));
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

    private void handleHumanDecisionTimeout(HumanDecisionTimeoutTrigger trigger) {
        if (automation.acceptTimeout(trigger, stateMachine, closed.get(), ruleInFlight)) {
            submitFrameComputation();
        }
    }

    private void handleAutomationControl(AutomationControlEnvelope envelope) {
        if (automation.control(envelope, stateMachine, closed.get(), ruleInFlight)) {
            submitFrameComputation();
        }
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
            if (failure instanceof RulePackCircuitOpenException) {
                stateMachine.block("rule-pack-circuit-open");
            } else {
                stateMachine.recordFailure("rule-pool-saturated");
            }
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
        automation.beforeTransition(actor, action, envelope.isPresent(), scheduledTrigger);
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
            automation.transitionRejected();
            boolean circuitOpen = failure instanceof RulePackCircuitOpenException;
            String reason = circuitOpen ? "rule-pack-circuit-open" : "rule-pool-saturated";
            TableActionCode code = circuitOpen
                    ? TableActionCode.RULE_PACK_FAILURE
                    : TableActionCode.RULE_POOL_SATURATED;
            if (circuitOpen) {
                stateMachine.block(reason);
            }
            if (envelope.isPresent()) {
                envelope.orElseThrow().response().complete(stateMachine.result(
                        code, reason));
            } else if (authorityEnvelope.isPresent()) {
                authorityEnvelope.orElseThrow().response().complete(stateMachine.result(
                        code, reason));
            } else {
                stateMachine.block(circuitOpen ? reason : "scheduled-rule-pool-saturated");
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
            automation.completionDiscarded();
            TableActionResult closedResult =
                    stateMachine.result(TableActionCode.TABLE_CLOSED, "closed");
            completion.envelope().ifPresent(value -> value.response().complete(closedResult));
            completion.authorityEnvelope().ifPresent(value -> value.response().complete(closedResult));
            return;
        }
        stateMachine.handleRuleCompletion(completion);
        if (automation.afterCompletion(completion, stateMachine)) {
            submitFrameComputation();
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
        automation.clear();
        TableQueuedResponseDrainer.complete(
                inbox, TableActionCode.TABLE_CLOSED, "closed", stateMachine::result);
    }

    private void failQueuedActions(TableActionCode code, String reason) {
        TableQueuedResponseDrainer.complete(inbox, code, reason, snapshots::result);
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
