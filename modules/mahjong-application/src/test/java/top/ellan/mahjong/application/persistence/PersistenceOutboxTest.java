package top.ellan.mahjong.application.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;

class PersistenceOutboxTest {
    @Test
    void batchesSixteenEventsAndDrainsOnlyAfterDurableAck() {
        MatchId matchId = MatchId.random();
        ManualScheduler scheduler = new ManualScheduler();
        RecordingStore store = new RecordingStore();
        Clock clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);
        PersistenceOutbox outbox = new PersistenceOutbox(matchId, 0, store, scheduler, clock);

        outbox.offer(events(matchId, 1, 16, clock.instant()), Optional.empty());
        assertTrue(outbox.health().unpersistedEvents() >= 16);
        scheduler.runAll();
        assertEquals(1, store.batches.size());
        assertEquals(16, store.batches.getFirst().events().size());
        assertFalse(outbox.awaitDrained().toCompletableFuture().isDone());

        store.pending.getFirst().complete(new PersistAck(16));
        assertEquals(0, outbox.health().unpersistedEvents());
        assertTrue(outbox.awaitDrained().toCompletableFuture().isDone());
    }

    @Test
    void pausesAtThirtyTwoUnpersistedEventsWithoutAffectingAnotherOutbox() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);
        RecordingStore slowStore = new RecordingStore();
        MatchId slowMatch = MatchId.random();
        PersistenceOutbox slow =
                new PersistenceOutbox(slowMatch, 0, slowStore, new ManualScheduler(), clock);
        MatchId healthyMatch = MatchId.random();
        PersistenceOutbox healthy =
                new PersistenceOutbox(
                        healthyMatch, 0, new ImmediateStore(), new ManualScheduler(), clock);

        slow.offer(events(slowMatch, 1, 32, clock.instant()), Optional.empty());
        assertTrue(slow.health().paused());
        assertFalse(healthy.health().paused());
    }

    @Test
    void deadlineCapacityFailurePausesOnlyThatOutboxWithoutSplittingTheOffer() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);
        MatchId matchId = MatchId.random();
        TaskScheduler rejecting = (task, delay) -> {
            throw new RejectedExecutionException("full");
        };
        PersistenceOutbox outbox =
                new PersistenceOutbox(matchId, 0, new ImmediateStore(), rejecting, clock);

        OutboxHealth health =
                outbox.offer(events(matchId, 1, 1, clock.instant()), Optional.empty());

        assertEquals(1, health.unpersistedEvents());
        assertTrue(health.paused());
        assertEquals(Optional.of("scheduler-capacity"), health.failure());
    }

    private static List<MatchEventRecord> events(
            MatchId matchId, long start, int count, Instant acceptedAt) {
        List<MatchEventRecord> result = new ArrayList<>();
        PlayerId player = new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        String hash = "0".repeat(64);
        for (long sequence = start; sequence < start + count; sequence++) {
            result.add(
                    new MatchEventRecord(
                            matchId,
                            sequence,
                            sequence,
                            acceptedAt,
                            player,
                            new RuleAction("discard", new byte[] {(byte) sequence}),
                            new RuleEvent("discarded", new byte[] {(byte) sequence}),
                            hash,
                            hash));
        }
        return result;
    }

    private static final class ManualScheduler implements TaskScheduler {
        private final ArrayDeque<Scheduled> tasks = new ArrayDeque<>();

        @Override
        public Cancellable schedule(Runnable task, Duration delay) {
            Scheduled scheduled = new Scheduled(task);
            tasks.addLast(scheduled);
            return () -> {
                scheduled.cancelled = true;
                return tasks.remove(scheduled);
            };
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                Scheduled scheduled = tasks.removeFirst();
                if (!scheduled.cancelled) {
                    scheduled.task.run();
                }
            }
        }

        private static final class Scheduled {
            private final Runnable task;
            private boolean cancelled;

            private Scheduled(Runnable task) {
                this.task = task;
            }
        }
    }

    private static final class RecordingStore implements EventStorePort {
        private final List<MatchWriteBatch> batches = new ArrayList<>();
        private final ArrayDeque<CompletableFuture<PersistAck>> pending = new ArrayDeque<>();
        @Override
        public CompletionStage<PersistAck> appendBatch(MatchWriteBatch batch) {
            batches.add(batch);
            CompletableFuture<PersistAck> future = new CompletableFuture<>();
            pending.addLast(future);
            return future;
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final class ImmediateStore implements EventStorePort {
        @Override
        public CompletionStage<PersistAck> appendBatch(MatchWriteBatch batch) {
            return CompletableFuture.completedFuture(
                    new PersistAck(batch.events().getLast().sequence()));
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
