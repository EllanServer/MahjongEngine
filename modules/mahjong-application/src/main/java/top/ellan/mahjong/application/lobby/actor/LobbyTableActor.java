package top.ellan.mahjong.application.lobby.actor;

import java.time.Duration;
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
import top.ellan.mahjong.application.security.ActionTokenIssuer;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionEndpoint;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.table.TableActorSnapshot;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;
import top.ellan.mahjong.application.lobby.command.LobbyReducer;
import top.ellan.mahjong.application.lobby.command.LobbyReduction;
import top.ellan.mahjong.application.lobby.port.LobbyStartPort;
import top.ellan.mahjong.application.lobby.port.LobbyStateObserver;
import top.ellan.mahjong.application.lobby.projection.LobbyProjectionFactory;
import top.ellan.mahjong.application.lobby.projection.LobbyProjectionFrame;
import top.ellan.mahjong.domain.lobby.LobbyPhase;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Pre-match single-writer actor. It owns no rule implementation and hands the table to a rule
 * actor only after all four seats are ready.
 */
public final class LobbyTableActor implements TableActionEndpoint {
    private static final OutboxHealth NO_OUTBOX =
            new OutboxHealth(0, Duration.ZERO, 0, false, Optional.empty());

    private final Executor dispatcher;
    private final SceneProjectionPort projector;
    private final LobbyProjectionFactory projectionFactory;
    private final LobbyReducer reducer;
    private final LobbyStateObserver observer;
    private final LobbyStartPort startPort;
    private final TableActorConfig config;
    private final ArrayBlockingQueue<Envelope> mailbox;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<TableLobby> publishedState;
    private final AtomicReference<TableActorSnapshot> publishedSnapshot = new AtomicReference<>();
    private final AtomicReference<TableProjection> publishedProjection = new AtomicReference<>();
    private final CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();
    private final Map<UUID, LobbyCommand> actionCatalog = new HashMap<>();
    private TableLobby state;
    private String failureCode = "";

    public LobbyTableActor(
            Executor dispatcher,
            SceneProjectionPort projector,
            ActionTokenIssuer tokenIssuer,
            LobbyReducer reducer,
            LobbyStateObserver observer,
            LobbyStartPort startPort,
            TableActorConfig config,
            TableLobby initialState) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.projector = Objects.requireNonNull(projector, "projector");
        projectionFactory =
                new LobbyProjectionFactory(
                        Objects.requireNonNull(tokenIssuer, "tokenIssuer"));
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.startPort = Objects.requireNonNull(startPort, "startPort");
        this.config = Objects.requireNonNull(config, "config");
        state = Objects.requireNonNull(initialState, "initialState");
        if (state.phase() == LobbyPhase.CLOSED) {
            throw new IllegalArgumentException("cannot start a closed lobby actor");
        }
        publishedState = new AtomicReference<>(state);
        mailbox = new ArrayBlockingQueue<>(config.mailboxCapacity());
        publishSnapshot();
    }

    /** Publishes the first frame after directories and the interaction registry are ready. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("lobby actor already started");
        }
        if (closed.get()) {
            throw new IllegalStateException("lobby actor is closed");
        }
        publishFrame();
    }

    /** O(1), bounded direct command ingress for Paper commands and seat events. */
    public CompletionStage<TableActionResult> command(LobbyCommand command) {
        Objects.requireNonNull(command, "command");
        return offer(new CommandEnvelope(command, new CompletableFuture<>()));
    }

    @Override
    public CompletionStage<TableActionResult> submit(PlayerId actor, ActionToken token) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(token, "token");
        return offer(new TokenEnvelope(actor, token, new CompletableFuture<>()));
    }

    public TableLobby state() {
        return publishedState.get();
    }

    public Optional<TableProjection> latestProjection() {
        return Optional.ofNullable(publishedProjection.get());
    }

    /** Returns a STARTING lobby to WAITING after an asynchronous rule-pack start failure. */
    public void startFailed(String reasonCode) {
        if (closed.get()) {
            return;
        }
        if (!mailbox.offer(
                new StartFailedEnvelope(
                        reasonCode == null ? "start-failed" : reasonCode,
                        new CompletableFuture<>()))) {
            failureCode = "start-failure-mailbox-full";
            closeRequested.set(true);
        }
        scheduleDrain();
    }

    private CompletionStage<TableActionResult> offer(Envelope envelope) {
        if (closed.get()) {
            envelope.response()
                    .complete(result(TableActionCode.TABLE_CLOSED, "closed"));
            return envelope.response();
        }
        if (!started.get()) {
            envelope.response()
                    .complete(result(TableActionCode.TABLE_BLOCKED, "lobby-not-started"));
            return envelope.response();
        }
        if (!mailbox.offer(envelope)) {
            envelope.response()
                    .complete(result(TableActionCode.MAILBOX_FULL, "mailbox-full"));
            return envelope.response();
        }
        scheduleDrain();
        return envelope.response();
    }

    private void scheduleDrain() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatcher.execute(this::drain);
        } catch (RejectedExecutionException failure) {
            scheduled.set(false);
            failureCode = "actor-dispatch-rejected";
            failQueued(TableActionCode.TABLE_BLOCKED, failureCode);
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
                Envelope envelope = mailbox.poll();
                if (envelope == null) {
                    break;
                }
                if (envelope instanceof StartFailedEnvelope failed) {
                    handleStartFailed(failed);
                } else if (envelope instanceof TokenEnvelope token) {
                    handleToken(token);
                } else {
                    handleCommand((CommandEnvelope) envelope);
                }
            }
        } finally {
            scheduled.set(false);
            publishSnapshot();
            if (closeRequested.get() || !mailbox.isEmpty()) {
                scheduleDrain();
            }
        }
    }

    private void handleToken(TokenEnvelope envelope) {
        if (!envelope.actor().equals(envelope.token().actor())) {
            envelope.response().complete(result(TableActionCode.WRONG_ACTOR, "token-owner-mismatch"));
            return;
        }
        if (envelope.token().revision() != state.revision()) {
            envelope.response().complete(result(TableActionCode.STALE_TOKEN, "stale-revision"));
            return;
        }
        LobbyCommand command = actionCatalog.get(envelope.token().value());
        if (command == null || !command.actor().equals(envelope.actor())) {
            envelope.response().complete(result(TableActionCode.STALE_TOKEN, "unknown-token"));
            return;
        }
        apply(command, envelope.response());
    }

    private void handleCommand(CommandEnvelope envelope) {
        apply(envelope.command(), envelope.response());
    }

    private void apply(
            LobbyCommand command, CompletableFuture<TableActionResult> response) {
        if (closed.get()) {
            response.complete(result(TableActionCode.TABLE_CLOSED, "closed"));
            return;
        }
        LobbyReduction reduction = reducer.apply(state, command);
        if (!reduction.accepted()) {
            response.complete(
                    result(TableActionCode.REJECTED_BY_RULES, reduction.reasonCode()));
            return;
        }
        if (reduction.changed()) {
            state = reduction.state();
            publishedState.set(state);
            publishFrame();
            notifyObserver();
        }
        response.complete(result(TableActionCode.ACCEPTED_MEMORY, reduction.reasonCode()));
        if (reduction.startRequested()) {
            try {
                startPort.requestStart(state, this);
            } catch (RuntimeException failure) {
                startFailed("start-port-" + failure.getClass().getSimpleName());
            }
        }
    }

    private void handleStartFailed(StartFailedEnvelope failed) {
        if (state.phase() != LobbyPhase.STARTING) {
            failed.response().complete(result(TableActionCode.REJECTED_BY_RULES, "not-starting"));
            return;
        }
        failureCode = failed.reasonCode();
        state = state.withState(state.seats(), state.spectators(), LobbyPhase.WAITING);
        publishedState.set(state);
        publishFrame();
        notifyObserver();
        failed.response().complete(result(TableActionCode.ACCEPTED_MEMORY, failed.reasonCode()));
    }

    private void publishFrame() {
        LobbyProjectionFrame frame = projectionFactory.create(state);
        actionCatalog.clear();
        actionCatalog.putAll(frame.actionCatalog());
        TableProjection projection = frame.projection();
        publishedProjection.set(projection);
        try {
            projector.publish(projection);
        } catch (RuntimeException failure) {
            failureCode = "projection-" + failure.getClass().getSimpleName();
        }
    }

    private void notifyObserver() {
        try {
            observer.changed(state);
        } catch (RuntimeException failure) {
            failureCode = "observer-" + failure.getClass().getSimpleName();
        }
    }

    private void handleClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        actionCatalog.clear();
        failQueued(TableActionCode.TABLE_CLOSED, "closed");
        shutdownComplete.complete(null);
    }

    private void failQueued(TableActionCode code, String reason) {
        Envelope queued;
        while ((queued = mailbox.poll()) != null) {
            queued.response().complete(result(code, reason));
        }
    }

    private TableActionResult result(TableActionCode code, String reason) {
        return new TableActionResult(code, state.revision(), reason == null ? "" : reason);
    }

    private void publishSnapshot() {
        publishedSnapshot.set(
                new TableActorSnapshot(
                        state.tableId(),
                        state.revision(),
                        state.phase() == LobbyPhase.STARTING
                                ? TableLifecycle.STARTING
                                : TableLifecycle.LOBBY,
                        mailbox.size(),
                        false,
                        NO_OUTBOX,
                        failureCode));
    }

    @Override
    public TableActorSnapshot snapshot() {
        return publishedSnapshot.get();
    }

    @Override
    public void close() {
        closeRequested.set(true);
        scheduleDrain();
    }

    @Override
    public CompletionStage<Void> closeAndDrain() {
        close();
        return shutdownComplete;
    }

    private sealed interface Envelope
            permits CommandEnvelope, TokenEnvelope, StartFailedEnvelope {
        CompletableFuture<TableActionResult> response();
    }

    private record CommandEnvelope(
            LobbyCommand command, CompletableFuture<TableActionResult> response)
            implements Envelope {
        private CommandEnvelope {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(response, "response");
        }
    }

    private record TokenEnvelope(
            PlayerId actor,
            ActionToken token,
            CompletableFuture<TableActionResult> response)
            implements Envelope {
        private TokenEnvelope {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(response, "response");
        }
    }

    private record StartFailedEnvelope(
            String reasonCode, CompletableFuture<TableActionResult> response)
            implements Envelope {
        private StartFailedEnvelope {
            reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(response, "response");
        }
    }
}
