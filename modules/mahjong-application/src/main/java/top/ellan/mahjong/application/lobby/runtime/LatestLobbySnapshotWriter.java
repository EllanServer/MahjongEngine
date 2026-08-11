package top.ellan.mahjong.application.lobby.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.application.lobby.port.LobbyStateObserver;
import top.ellan.mahjong.domain.lobby.TableLobby;

/**
 * Per-table latest-only lobby writer. At most one blocking write is in flight and intermediate
 * readiness frames are deliberately coalesced.
 */
public final class LatestLobbySnapshotWriter implements LobbyStateObserver, AutoCloseable {
    private final Executor ioExecutor;
    private final LobbyRepositoryPort repository;
    private final Clock clock;
    private final Consumer<Throwable> failureSink;
    private final AtomicReference<TableLobby> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LatestLobbySnapshotWriter(
            Executor ioExecutor,
            LobbyRepositoryPort repository,
            Clock clock,
            Consumer<Throwable> failureSink) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    @Override
    public void changed(TableLobby state) {
        Objects.requireNonNull(state, "state");
        if (closed.get()) {
            return;
        }
        pending.accumulateAndGet(
                state,
                (current, next) ->
                        current == null || next.revision() >= current.revision()
                                ? next
                                : current);
        schedule();
    }

    private void schedule() {
        if (closed.get() || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            ioExecutor.execute(this::writeOne);
        } catch (RejectedExecutionException failure) {
            scheduled.set(false);
            failureSink.accept(failure);
        }
    }

    private void writeOne() {
        TableLobby state = pending.getAndSet(null);
        try {
            if (state != null && !closed.get()) {
                repository.save(state, Instant.now(clock));
            }
        } catch (Exception failure) {
            failureSink.accept(failure);
        } finally {
            scheduled.set(false);
            if (pending.get() != null) {
                schedule();
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        pending.set(null);
    }
}
