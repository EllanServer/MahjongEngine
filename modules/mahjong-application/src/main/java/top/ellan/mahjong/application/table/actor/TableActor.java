package top.ellan.mahjong.application.table.actor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import top.ellan.mahjong.application.persistence.MatchEventRecord;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.persistence.SnapshotWrite;
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
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.TransitionDisposition;

/**
 * Per-table single-writer actor. Event threads only perform a bounded offer; rule work, persistence,
 * and scene work are continuations and never block ingress.
 */
public final class TableActor implements TableActionEndpoint {
    private final Executor dispatcher;
    private final FairRuleExecutor ruleExecutor;
    private final RulePackProvider provider;
    private final PersistenceOutbox outbox;
    private final SceneProjectionPort projector;
    private final ActionTokenIssuer tokenIssuer;
    private final Clock clock;
    private final TableActorConfig config;
    private final ArrayBlockingQueue<ActionEnvelope> mailbox;
    private final AtomicReference<RuleCompletion> ruleCompletion = new AtomicReference<>();
    private final AtomicReference<OutboxHealth> outboxSignal = new AtomicReference<>();
    private final AtomicBoolean duplicateRuleCompletion = new AtomicBoolean();
    private final AtomicBoolean initializeRequested = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<TableActorSnapshot> publishedSnapshot = new AtomicReference<>();
    private final AtomicReference<TableProjection> publishedProjection = new AtomicReference<>();
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
        this.provider = Objects.requireNonNull(provider, "provider");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
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
        return Optional.ofNullable(publishedProjection.get());
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
        if (aggregate.lifecycle() == TableLifecycle.PAUSED_PERSISTENCE) {
            envelope.response().complete(result(TableActionCode.TABLE_PAUSED, "persistence-backpressure"));
            return;
        }
        if (aggregate.lifecycle() == TableLifecycle.BLOCKED_RULE_PACK
                || aggregate.lifecycle() == TableLifecycle.NEEDS_ADMIN_REVIEW) {
            envelope.response().complete(result(TableActionCode.TABLE_BLOCKED, failureCode));
            return;
        }
        if (aggregate.lifecycle().terminal()) {
            envelope.response().complete(result(TableActionCode.TABLE_FINISHED, "match-ended"));
            return;
        }
        if (!aggregate.lifecycle().acceptsRuleActions()) {
            envelope.response().complete(result(TableActionCode.TABLE_BLOCKED, "not-active"));
            return;
        }
        if (ruleInFlight) {
            envelope.response().complete(result(TableActionCode.RULE_BUSY, "rule-calculation-in-flight"));
            return;
        }
        ActionToken token = envelope.token();
        if (!token.actor().equals(envelope.actor())) {
            envelope.response().complete(result(TableActionCode.WRONG_ACTOR, "token-owner-mismatch"));
            return;
        }
        if (token.revision() != aggregate.revision()) {
            envelope.response().complete(result(TableActionCode.STALE_TOKEN, "stale-revision"));
            return;
        }
        AuthorizedAction authorized = actionCatalog.get(token.value());
        if (authorized == null || !authorized.token().equals(token)) {
            envelope.response().complete(result(TableActionCode.STALE_TOKEN, "unknown-token"));
            return;
        }
        submitTransition(envelope, authorized.legalAction().action());
    }

    private void submitFrameComputation() {
        long expectedRevision = aggregate.revision();
        RuleState capturedState = ruleState;
        ruleInFlight = true;
        try {
            ruleExecutor
                    .submit(
                            matchBinding().rulePack().ruleId(),
                            () -> computeFrameOnly(capturedState, expectedRevision))
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
                                    computeTransition(
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

    private RuleComputation computeFrameOnly(RuleState capturedState, long revision) {
        String hash = provider.stateHash(capturedState);
        return new RuleComputation(
                capturedState,
                null,
                hash,
                hash,
                Optional.empty(),
                buildFrame(capturedState, revision));
    }

    private RuleComputation computeTransition(
            RuleState capturedState,
            PlayerId actor,
            RuleAction action,
            long revision,
            long startingSequence,
            long nextAcceptedAction) {
        String beforeHash = provider.stateHash(capturedState);
        RuleTransition transition = provider.transition(capturedState, actor, action);
        Objects.requireNonNull(transition, "provider returned null transition");
        if (!transition.accepted() && transition.nextState() != capturedState) {
            throw new IllegalStateException("Rejected transition did not return the same state instance");
        }
        if (transition.accepted() && transition.events().isEmpty()) {
            throw new IllegalStateException("Accepted transition emitted no events");
        }
        if (transition.events().size() > config.maxEventsPerAction()) {
            throw new IllegalStateException("Provider exceeded per-action event limit");
        }
        long targetRevision = transition.accepted() ? revision + 1 : revision;
        RuleState targetState = transition.nextState();
        String afterHash = provider.stateHash(targetState);
        long resultingSequence = startingSequence + transition.events().size();
        boolean snapshotDue =
                transition.accepted()
                        && (nextAcceptedAction % 32 == 0
                                || transition.disposition() == TransitionDisposition.ROUND_ENDED
                                || transition.disposition() == TransitionDisposition.MATCH_ENDED);
        Optional<RuleStateSnapshot> snapshot =
                snapshotDue
                        ? Optional.of(provider.snapshot(targetState, resultingSequence))
                        : Optional.empty();
        return new RuleComputation(
                targetState,
                transition,
                beforeHash,
                afterHash,
                snapshot,
                buildFrame(targetState, targetRevision));
    }

    private RuleFrame buildFrame(RuleState state, long revision) {
        PublicRuleView publicView = provider.publicView(state, revision);
        if (publicView.stateRevision() != revision) {
            throw new IllegalStateException("Provider returned a public view for the wrong revision");
        }
        Map<PlayerId, PrivateRuleView> privateViews = new LinkedHashMap<>();
        Map<PlayerId, List<LegalAction>> legalActions = new LinkedHashMap<>();
        for (TableParticipant participant : aggregate.participants()) {
            if (participant.seat().isEmpty()) {
                continue;
            }
            PlayerId player = participant.playerId();
            PrivateRuleView privateView = provider.privateView(state, player, revision);
            if (!privateView.viewer().equals(player) || privateView.stateRevision() != revision) {
                throw new IllegalStateException("Provider returned an unauthorized private view");
            }
            List<LegalAction> actions = List.copyOf(provider.legalActions(state, player));
            if (actions.size() > config.maxLegalActionsPerPlayer()) {
                throw new IllegalStateException("Provider exceeded legal-action limit");
            }
            long distinctKeys = actions.stream().map(LegalAction::key).distinct().count();
            if (distinctKeys != actions.size()) {
                throw new IllegalStateException("Provider emitted duplicate legal-action keys");
            }
            privateViews.put(player, privateView);
            legalActions.put(player, actions);
        }
        return new RuleFrame(publicView, privateViews, legalActions);
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

        if (!outbox.canAccept(transition.events().size())) {
            outboxHealth = outbox.health();
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
            envelope.response().complete(
                    result(TableActionCode.TABLE_PAUSED, "persistence-hard-capacity"));
            republishLifecycle();
            return;
        }
        RuleAction acceptedAction =
                actionCatalog.get(envelope.token().value()).legalAction().action();
        long resultingSequence = lastEventSequence + transition.events().size();
        List<MatchEventRecord> records =
                createEventRecords(
                        envelope.actor(),
                        acceptedAction,
                        transition.events(),
                        computed.beforeHash(),
                        computed.afterHash(),
                        aggregate.revision() + 1,
                        lastEventSequence);
        Optional<SnapshotWrite> snapshot =
                computed.snapshot().map(
                        value ->
                                new SnapshotWrite(
                                        matchBinding().matchId(),
                                        aggregate.revision() + 1,
                                        Instant.now(clock),
                                        value,
                                        Optional.of(
                                                transition.disposition()
                                                                == TransitionDisposition.MATCH_ENDED
                                                        ? TableLifecycle.FINISHED
                                                        : TableLifecycle.ACTIVE)));
        OutboxHealth health;
        try {
            health = outbox.offer(records, snapshot);
        } catch (RuntimeException failure) {
            failureCode = "outbox-" + failure.getClass().getSimpleName();
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
            outboxHealth = outbox.health();
            envelope.response().complete(
                    result(TableActionCode.TABLE_PAUSED, failureCode));
            republishLifecycle();
            return;
        }
        ruleState = computed.state();
        aggregate = aggregate.withRevision(aggregate.revision() + 1);
        acceptedActions++;
        lastEventSequence = resultingSequence;
        outboxHealth = health;
        if (transition.disposition() == TransitionDisposition.MATCH_ENDED) {
            aggregate = aggregate.withLifecycle(TableLifecycle.FINISHED);
        } else if (health.paused()) {
            aggregate = aggregate.withLifecycle(TableLifecycle.PAUSED_PERSISTENCE);
        }
        installFrame(computed.frame());
        envelope.response().complete(
                result(TableActionCode.ACCEPTED_MEMORY, "accepted-memory-first"));
    }

    private List<MatchEventRecord> createEventRecords(
            PlayerId actor,
            RuleAction action,
            List<RuleEvent> events,
            String beforeHash,
            String afterHash,
            long stateRevision,
            long startingSequence) {
        List<MatchEventRecord> records = new ArrayList<>(events.size());
        Instant acceptedAt = Instant.now(clock);
        long sequence = startingSequence;
        for (RuleEvent event : events) {
            records.add(
                    new MatchEventRecord(
                            matchBinding().matchId(),
                            ++sequence,
                            stateRevision,
                            acceptedAt,
                            actor,
                            action,
                            event,
                            beforeHash,
                            afterHash));
        }
        return List.copyOf(records);
    }

    private MatchBinding matchBinding() {
        return aggregate.matchBinding().orElseThrow(
                () -> new IllegalStateException("Active rule-pack table has no match binding"));
    }

    private void installFrame(RuleFrame frame) {
        actionCatalog.clear();
        Map<PlayerId, List<AuthorizedAction>> authorizedByPlayer = new LinkedHashMap<>();
        frame.legalActions()
                .forEach(
                        (player, actions) -> {
                            List<AuthorizedAction> authorized = new ArrayList<>(actions.size());
                            for (LegalAction action : actions) {
                                ActionToken token = tokenIssuer.issue(player, aggregate.revision());
                                AuthorizedAction item = new AuthorizedAction(token, action);
                                if (actionCatalog.put(token.value(), item) != null) {
                                    throw new IllegalStateException("Action token collision");
                                }
                                authorized.add(item);
                            }
                            authorizedByPlayer.put(player, List.copyOf(authorized));
                        });
        TableProjection projection =
                new TableProjection(
                        aggregate.tableId(),
                        aggregate.revision(),
                        aggregate.lifecycle(),
                        frame.publicView(),
                        frame.privateViews(),
                        authorizedByPlayer);
        publishedProjection.set(projection);
        publishProjection(projection);
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
        TableProjection current = publishedProjection.get();
        if (current == null) {
            return;
        }
        TableProjection updated =
                new TableProjection(
                        current.tableId(),
                        current.revision(),
                        aggregate.lifecycle(),
                        current.publicView(),
                        current.privateViews(),
                        current.authorizedActions());
        publishedProjection.set(updated);
        publishProjection(updated);
    }

    private void publishProjection(TableProjection projection) {
        try {
            projector.publish(projection);
        } catch (RuntimeException failure) {
            // Rendering is derived state; a backend fault never rolls back or blocks the table actor.
            failureCode = "projection-" + failure.getClass().getSimpleName();
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

    private record RuleComputation(
            RuleState state,
            RuleTransition transition,
            String beforeHash,
            String afterHash,
            Optional<RuleStateSnapshot> snapshot,
            RuleFrame frame) {}

    private record RuleFrame(
            PublicRuleView publicView,
            Map<PlayerId, PrivateRuleView> privateViews,
            Map<PlayerId, List<LegalAction>> legalActions) {
        private RuleFrame {
            Objects.requireNonNull(publicView, "publicView");
            privateViews = Map.copyOf(privateViews);
            Map<PlayerId, List<LegalAction>> copy = new LinkedHashMap<>();
            for (Map.Entry<PlayerId, List<LegalAction>> entry : legalActions.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            legalActions = Map.copyOf(copy);
        }
    }
}
