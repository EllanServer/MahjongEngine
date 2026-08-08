package top.ellan.mahjong.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Coalescible per-table persistence signal consumed by a TableActor. */
public record OutboxHealth(
        int unpersistedEvents,
        Duration oldestAge,
        long committedSequence,
        boolean paused,
        Optional<String> failure) {
    public OutboxHealth {
        if (unpersistedEvents < 0 || committedSequence < 0 || oldestAge.isNegative()) {
            throw new IllegalArgumentException("Invalid outbox counters");
        }
        Objects.requireNonNull(oldestAge, "oldestAge");
        failure = Objects.requireNonNull(failure, "failure");
    }
}
