package top.ellan.mahjong.application.table.actor;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
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
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.TableAggregate;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.TransitionDisposition;

/**
 * Per-table single-writer actor. Event threads only perform a bounded offer; rule work, persistence,
 * and scene work are continuations and never block ingress.
 */
public final class TableActor implements TableActionEndpoint {
    private final Executor dispatcher;
    private final FairRuleExecutor ruleExecutor;
    private final PersistenceOutbox outbox;
    private final TableActorConfig config;
    private final RuleComputationEngine computationEngine;
    private final ProjectionAuthorizationService authorizationService;
    private final AcceptedTransitionWriter transitionWriter;
    private final TableProjectionPublisher projectionPublisher;
    private final ArrayBlockingQueue<ActionEnvelope> mailbox;
    private final AtomicReference<RuleCompletion> ruleCompletion = new AtomicReference<>();
    private final AtomicReference<OutboxHealth> outboxSignal = new AtomicReference<>();
    private final AtomicBoolean duplicateRuleCompletion = new AtomicBoolean();
    private final AtomicBoolean initializeRequested = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<TableActorSnapshot> publishedSnapshot = new AtomicReference<>();
    private final CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();
    private final Map<UUID, AuthorizedAction> actionCatalog = new HashMap<>();
    private TableAggregate aggregate;
    private RuleState ruleState;
    private OutboxHealth outboxHealth;
    private long lastEventSequence;
    private long acceptedActions;
    private boolean ruleInFlight;
    private String failureCode = "";

    public TableActor(
            Executor dispatcher,
            FairRuleExecutor ruleExecutor,
            RulePackProvider provider,
            PersistenceOutbox outbox,
            SceneProjectionPort projector,
            ActionTokenIssuer tokenIssuer,
            Clock clock,
            TableActorConfig config,
            TableAggregate aggregate,
            RuleState initialRuleState,
            long lastEventSequence) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.ruleExecutor = Objects.requireNonNull(ruleExecutor, "ruleExecutor");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        computationEngine =
                new RuleComputationEngine(provider, aggregate.participants(), config);
        authorizationService = new ProjectionAuthorizationService(tokenIssuer);
        transitionWriter = new AcceptedTransitionWriter(clock);
        projectionPublisher = new TableProjectionPublisher(projector);
        ruleState = Objects.requireNonNull(initialRuleState, "initialRuleState");
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("lastEventSequence must be non-negative");
        }
        this.lastEventSequence = lastEventSequence;
        mailbox = new ArrayBlockingQueue<>(config.mailboxCapacity());
        outboxHealth = outbox.health();
        outbox.setListener(this::signalOutbox);
        publishSnapshot();
    }

    /** Starts initial view/action generation on the fair rule pool. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("actor is closed");
        }
        initializeRequested.set(true);
        scheduleDrain();
    }

    /** O(1), non-blocking ingress method safe for Paper/CraftEngine event threads. */
    public CompletionStage<TableActionResult> submit(PlayerId actor, ActionToken token) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(token, "token");
        if (closed.get()) {
            return CompletableFuture.completedFuture(result(TableActionCode.TABLE_CLOSED, "closed"));
        }
        CompletableFuture<TableActionResult> response = new CompletableFuture<>();
        if (!mailbox.offer(new ActionEnvelope(actor, token, response))) {
            response.complete(result(TableActionCode.MAILBOX_FULL, "mailbox-full"));
            return response;
        }
        scheduleDrain();
        return response;
    }

    public TableActorSnapshot snapshot() {
        return publishedSnapshot.get();
    }

    public Optional<TableProjection> latestProjection() {
        return projectionPublisher.latest();
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
                if (closeRequested.getAndSet(false)) {
                    handleClose();
                    break;
                }
                RuleCompletion completion = ruleCompletion.getAndSet(null);
                if (completion != null) {
                    handleRuleCompletion(completion);
                    continue;
                }
                OutboxHealth health = outboxSignal.getAndSet(null);
                if (health != null) {
                    handleOutboxHealth(health);
                    continue;
                }
                if (initializeRequested.getAndSet(false)) {
                    if (!ruleInFlight) {
                        submitFrameComputation();
                    }
                    continue;
                }
                ActionEnvelope envelope = mailbox.poll();
                if (envelope == null) {
                    break;
                }
                handleAction(envelope);
            }
        } finally {
            scheduled.set(false);
            publishSnapshot();
            if (hasPendingWork()) {
                scheduleDrain();
            }
        }
    }

    private boolean hasPendingWork() {
        return closeRequested.get()
                || initializeRequested.get()
                || ruleCompletion.get() != null
                || outboxSignal.get() != null
                || !mailbox.isEmpty();
    }

    private void handleAction(ActionEnvelope envelope) {
        if (closed.get()) {
            envelope.response().complete(result(TableActionCode.TABLE_CLOSED, "closed"));
            return;
        }
        ActionAdmission admission =
                TableActionAdmission.evaluate(
                        aggregate,
                        ruleInFlight,
                        envelope.actor(),
                        envelope.token(),
                        actionCatalog,
                        failureCode);
        if (!admission.accepted()) {
            envelope.response()
                    .complete(result(admission.rejectionCode(), admission.reasonCode()));
            return;
        }
        submitTransition(envelope, admission.action());
    }

    private void submitFrameComputation() {
        long expectedRevision = aggregate.revision();
        RuleState capturedState = ruleState;
        ruleInFlight = true;
        try {
            ruleExecutor
                    .submit(
                            matchBinding().rulePack().ruleId(),
                            () -> computationEngine.frameOnly(capturedState, expectedRevision))
                    .whenComplete(
                            (computed, failure) ->
                                    signalRuleCompletion(
                                            new RuleCompletion(
                                                    expectedRevision,
                                                    Optional.empty(),
                                                    computed,
                                                    failure)));
        } catch (RejectedExecutionException failure) {
            ruleInFlight = false;
            failureCode = "rule-pool-saturated";
        }
    }

    private void submitTransition(ActionEnvelope envelope, RuleAction action) {
        long expectedRevision = aggregate.revision();
        long startingSequence = lastEventSequence;
        long nextAcceptedAction = acceptedActions + 1;
        RuleState capturedState = ruleState;
        ruleInFlight = true;
        try {
            ruleExecutor
                    .submit(
                            matchBinding().rulePack().ruleId(),
                            () ->
                                    computationEngine.transition(
                                            capturedState,
                                            envelope.actor(),
                                            action,
                                            expectedRevision,
                                            startingSequence,
                                            nextAcceptedAction))
                    .whenComplete(
                            (computed, failure) ->
                                    signalRuleCompletion(
                                            new RuleCompletion(
                                                    expectedRevision,
                                                    Optional.of(envelope),
                                                    computed,
                                                    failure)));
        } catch (RejectedExecutionException failure) {
            ruleInFlight = false;
            envelope.response().complete(
                    result(TableActionCode.RULE_POOL_SATURATED, "rule-pool-saturated"));
        }
    }

    private void signalRuleCompletion(RuleCompletion completion) {
        if (!ruleCompletion.compareAndSet(null, completion)) {
            duplicateRuleCompletion.set(true);
            closeRequested.set(true);
        }
        scheduleDrain();
    }

    private void signalOutbox(OutboxHealth health) {
        outboxSignal.set(health);
        scheduleDrain();
    }

    private void handleRuleCompletion(RuleCompletion completion) {
        ruleInFlight = false;
        if (closed.get()) {
            completion.envelope().ifPresent(
                    value ->
                            value.response()
                                    .complete(result(TableActionCode.TABLE_CLOSED, "closed")));
            return;
        }
        if (completion.expectedRevision() != aggregate.revision()) {
            completion.envelope().ifPresent(
                    value ->
                            value.response()
                                    .complete(result(TableActionCode.STALE_TOKEN, "revision-advanced")));
            return;
        }
        if (completion.failure() != null || completion.computed() == null) {
            failureCode =
                    completion.failure() == null
                            ? "null-rule-result"
                            : completion.failure().getClass().getSimpleName();
            aggregate = aggregate.withLifecycle(TableLifecycle.BLOCKED_RULE_PACK);
            actionCatalog.clear();
            completion.envelope().ifPresent(
                    value ->
                            value.response()
                                    .complete(
                                            result(
                                                    TableActionCode.RULE_PACK_FAILURE,
                                                    failureCode)));
            republishLifecycle();
            return;
        }
        RuleComputation computed = completion.computed();
        RuleTransition transition = computed.transition();
        if (transition == null) {
            installFrame(computed.frame());
            return;
        }
        ActionEnvelope envelope = completion.envelope().orElseThrow();
        if (!transition.accepted()) {
            installFrame(computed.frame());
            envelope.response().complete(
                    result(TableActionCode.REJECTED_BY_RULES, transition.reasonCode()));
            return;
        }

        RuleAction acceptedAction =
                actionCatalog.get(envelope.token().value()).legalAction().action();
        TransitionWrite write =
                transitionWriter.offer(
                        outbox,
                        matchBinding(),
                        aggregate.revision(),
                        lastEventSequence,
                        envelope.actor(),
                        acceptedAction,
                        computed);
        if (!write.accepted()) {
            if (write.status() == TransitionWriteStatus.FAILED) {
                failureCode = write.failureCode();
            }
            outboxHealth = write.health();
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
            envelope.response().complete(
                    result(TableActionCode.TABLE_PAUSED, write.failureCode()));
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
        installFrame(computed.frame());
        envelope.response().complete(
                result(TableActionCode.ACCEPTED_MEMORY, "accepted-memory-first"));
    }

    private MatchBinding matchBinding() {
        return aggregate.matchBinding().orElseThrow(
                () -> new IllegalStateException("Active rule-pack table has no match binding"));
    }

    private void installFrame(RuleFrame frame) {
        AuthorizedProjection authorized = authorizationService.authorize(aggregate, frame);
        actionCatalog.clear();
        actionCatalog.putAll(authorized.actionCatalog());
        recordProjectionFailure(projectionPublisher.install(authorized.projection()));
    }

    private void handleOutboxHealth(OutboxHealth health) {
        outboxHealth = health;
        if (aggregate.lifecycle() == TableLifecycle.ACTIVE && health.paused()) {
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
            republishLifecycle();
        } else if (aggregate.lifecycle() == TableLifecycle.PAUSED_PERSISTENCE && !health.paused()) {
            aggregate = aggregate.withLifecycle(TableLifecycle.ACTIVE);
            republishLifecycle();
        }
    }

    private void republishLifecycle() {
        recordProjectionFailure(
                projectionPublisher.republishLifecycle(aggregate.lifecycle()));
    }

    private void recordProjectionFailure(String projectionFailure) {
        if (!projectionFailure.isEmpty()) {
            failureCode = projectionFailure;
        }
    }

    private void handleClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (duplicateRuleCompletion.get()) {
            failureCode = "duplicate-rule-completion";
        }
        actionCatalog.clear();
        failQueuedActions(TableActionCode.TABLE_CLOSED, "closed");
        outbox.close();
        outbox.awaitDrained().whenComplete((ignored, failure) -> {
            if (failure == null) {
                shutdownComplete.complete(null);
            } else {
                shutdownComplete.completeExceptionally(failure);
            }
        });
    }

    private void failQueuedActions(TableActionCode code, String reason) {
        ActionEnvelope envelope;
        while ((envelope = mailbox.poll()) != null) {
            envelope.response().complete(result(code, reason));
        }
    }

    private TableActionResult result(TableActionCode code, String reason) {
        return new TableActionResult(code, aggregate.revision(), reason == null ? "" : reason);
    }

    private void publishSnapshot() {
        publishedSnapshot.set(
                new TableActorSnapshot(
                        aggregate.tableId(),
                        aggregate.revision(),
                        aggregate.lifecycle(),
                        mailbox.size(),
                        ruleInFlight,
                        outboxHealth,
                        failureCode));
    }

    @Override
    public void close() {
        closeRequested.set(true);
        scheduleDrain();
    }

    /** Requests actor shutdown and completes after this table's persistence outbox drains. */
    public CompletionStage<Void> closeAndDrain() {
        close();
        return shutdownComplete;
    }

    private record ActionEnvelope(
            PlayerId actor,
            ActionToken token,
            CompletableFuture<TableActionResult> response) {}

    private record RuleCompletion(
            long expectedRevision,
            Optional<ActionEnvelope> envelope,
            RuleComputation computed,
            Throwable failure) {}

}
