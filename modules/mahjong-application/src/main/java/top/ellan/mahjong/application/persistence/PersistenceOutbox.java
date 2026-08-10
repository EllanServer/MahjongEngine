package top.ellan.mahjong.application.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.domain.match.MatchId;

/**
 * Per-match memory-first outbox. It batches in order, never blocks the actor, and reports
 * backpressure independently for each table.
 */
public final class PersistenceOutbox implements AutoCloseable {
    public static final int DEFAULT_BATCH_SIZE = 16;
    public static final int DEFAULT_PAUSE_THRESHOLD = 32;
    public static final Duration DEFAULT_FLUSH_DELAY = Duration.ofMillis(50);
    public static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(2);

    private final MatchId matchId;
    private final EventStorePort store;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final int batchSize;
    private final int pauseThreshold;
    private final int hardCapacity;
    private final Duration flushDelay;
    private final Duration maxAge;
    private final ArrayDeque<MatchEventRecord> pending = new ArrayDeque<>();
    private final NavigableMap<Long, SnapshotWrite> snapshots = new TreeMap<>();
    private Consumer<OutboxHealth> listener = ignored -> {};
    private long committedSequence;
    private long lastEnqueuedSequence;
    private boolean writeInFlight;
    private boolean flushScheduled;
    private boolean lastPaused;
    private boolean closed;
    private String lastFailure;
    private Cancellable flushTask;
    private CompletableFuture<Void> drained = CompletableFuture.completedFuture(null);

    public PersistenceOutbox(
            MatchId matchId,
            long committedSequence,
            EventStorePort store,
            TaskScheduler scheduler,
            Clock clock) {
        this(
                matchId,
                committedSequence,
                store,
                scheduler,
                clock,
                DEFAULT_BATCH_SIZE,
                DEFAULT_PAUSE_THRESHOLD,
                256,
                DEFAULT_FLUSH_DELAY,
                DEFAULT_MAX_AGE);
    }

    public PersistenceOutbox(
            MatchId matchId,
            long committedSequence,
            EventStorePort store,
            TaskScheduler scheduler,
            Clock clock,
            int batchSize,
            int pauseThreshold,
            int hardCapacity,
            Duration flushDelay,
            Duration maxAge) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        if (committedSequence < 0
                || batchSize < 1
                || pauseThreshold < batchSize
                || hardCapacity < pauseThreshold) {
            throw new IllegalArgumentException("Invalid outbox limits");
        }
        this.committedSequence = committedSequence;
        lastEnqueuedSequence = committedSequence;
        this.store = Objects.requireNonNull(store, "store");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = batchSize;
        this.pauseThreshold = pauseThreshold;
        this.hardCapacity = hardCapacity;
        this.flushDelay = Objects.requireNonNull(flushDelay, "flushDelay");
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
    }

    public synchronized void setListener(Consumer<OutboxHealth> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized OutboxHealth offer(
            List<MatchEventRecord> events, Optional<SnapshotWrite> snapshot) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(snapshot, "snapshot");
        if (closed) {
            throw new IllegalStateException("outbox is closed");
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("At least one event is required");
        }
        if (pending.size() + events.size() > hardCapacity) {
            throw new RejectedExecutionException("per-match outbox hard capacity exceeded");
        }
        long expected = lastEnqueuedSequence + 1;
        for (MatchEventRecord event : events) {
            if (!event.matchId().equals(matchId) || event.sequence() != expected++) {
                throw new IllegalArgumentException("Outbox events must be contiguous and match-scoped");
            }
        }
        long offeredLastSequence = events.getLast().sequence();
        if (snapshot.isPresent()) {
            SnapshotWrite value = snapshot.orElseThrow();
            if (!value.matchId().equals(matchId)
                    || value.snapshot().sequence() > offeredLastSequence) {
                throw new IllegalArgumentException("Snapshot is outside the outbox boundary");
            }
        }
        boolean wasEmpty = pending.isEmpty();
        pending.addAll(events);
        lastEnqueuedSequence = offeredLastSequence;
        if (snapshot.isPresent()) {
            SnapshotWrite value = snapshot.orElseThrow();
            snapshots.put(value.snapshot().sequence(), value);
        }
        if (wasEmpty) {
            drained = new CompletableFuture<>();
        }
        scheduleTick();
        OutboxHealth health = health();
        publishHealth(health);
        return health;
    }

    public synchronized OutboxHealth health() {
        Duration age = oldestAge();
        boolean paused =
                pending.size() >= pauseThreshold
                        || age.compareTo(maxAge) >= 0
                        || lastFailure != null
                        || !store.available();
        return new OutboxHealth(
                pending.size(),
                age,
                committedSequence,
                paused,
                Optional.ofNullable(lastFailure));
    }

    /**
     * Returns whether one actor transition can be retained without crossing this match's hard
     * memory limit. The actor is the sole producer, so a successful check remains valid until its
     * immediately following {@link #offer(List, Optional)} call.
     */
    public synchronized boolean canAccept(int eventCount) {
        return !closed && eventCount > 0 && pending.size() + eventCount <= hardCapacity;
    }

    public synchronized CompletionStage<Void> awaitDrained() {
        return drained;
    }

    public synchronized void flushNow() {
        scheduleTick(Duration.ZERO);
    }

    /**
     * Smart tick: full batches flush immediately; partial batches wait for the earlier of
     * {@code flushDelay} or the oldest event approaching {@code maxAge}, so one timer covers
     * both flushing and the age-based pause signal.
     */
    private synchronized void scheduleTick() {
        if (flushScheduled || writeInFlight || pending.isEmpty()) {
            return;
        }
        Duration delay;
        if (pending.size() >= batchSize) {
            delay = Duration.ZERO;
        } else {
            Duration remaining = maxAge.minus(oldestAge());
            delay = remaining.isNegative() || remaining.compareTo(flushDelay) >= 0
                    ? flushDelay
                    : remaining;
        }
        scheduleTick(delay);
    }

    private synchronized void scheduleTick(Duration delay) {
        if (flushScheduled || writeInFlight || pending.isEmpty()) {
            return;
        }
        flushScheduled = true;
        try {
            flushTask =
                    scheduler.schedule(
                            () -> {
                                synchronized (PersistenceOutbox.this) {
                                    flushScheduled = false;
                                }
                                startFlush();
                            },
                            delay);
        } catch (RejectedExecutionException failure) {
            flushScheduled = false;
            lastFailure = "scheduler-capacity";
            publishHealth();
        }
    }

    private void startFlush() {
        MatchWriteBatch batch;
        synchronized (this) {
            if (writeInFlight || pending.isEmpty()) {
                return;
            }
            List<MatchEventRecord> events = new ArrayList<>(Math.min(batchSize, pending.size()));
            int remaining = batchSize;
            for (MatchEventRecord event : pending) {
                if (remaining-- == 0) {
                    break;
                }
                events.add(event);
            }
            long lastSequence = events.getLast().sequence();
            var snapshotEntry = snapshots.floorEntry(lastSequence);
            Optional<SnapshotWrite> snapshot =
                    snapshotEntry == null || snapshotEntry.getKey() <= committedSequence
                            ? Optional.empty()
                            : Optional.of(snapshotEntry.getValue());
            batch = new MatchWriteBatch(matchId, events, snapshot);
            writeInFlight = true;
        }

        CompletionStage<PersistAck> write;
        try {
            write = store.appendBatch(batch);
        } catch (RuntimeException failure) {
            finishFlush(batch, null, failure);
            return;
        }
        write.whenComplete((ack, failure) -> finishFlush(batch, ack, failure));
    }

    private synchronized void finishFlush(
            MatchWriteBatch batch, PersistAck ack, Throwable failure) {
        writeInFlight = false;
        if (failure != null) {
            lastFailure = failure.getClass().getSimpleName();
            scheduleTick(Duration.ofMillis(250));
            publishHealth();
            return;
        }
        long batchLast = batch.events().getLast().sequence();
        if (ack == null
                || ack.committedSequence() < batchLast
                || ack.committedSequence() > lastEnqueuedSequence) {
            lastFailure = "invalid-persistence-ack";
            scheduleTick(Duration.ofMillis(250));
            publishHealth();
            return;
        }
        committedSequence = ack.committedSequence();
        while (!pending.isEmpty() && pending.getFirst().sequence() <= committedSequence) {
            pending.removeFirst();
        }
        snapshots.headMap(committedSequence, true).clear();
        lastFailure = null;
        if (pending.isEmpty()) {
            cancelScheduledTasks();
            drained.complete(null);
        } else {
            scheduleTick();
        }
        publishHealth();
    }

    private synchronized Duration oldestAge() {
        MatchEventRecord oldest = pending.peekFirst();
        if (oldest == null) {
            return Duration.ZERO;
        }
        Duration age = Duration.between(oldest.acceptedAt(), Instant.now(clock));
        return age.isNegative() ? Duration.ZERO : age;
    }

    /** Coalesces the per-offer self-wakeup: the actor only needs to react to pause transitions. */
    private synchronized void publishHealth(OutboxHealth health) {
        if (health.paused() != lastPaused) {
            lastPaused = health.paused();
            listener.accept(health);
        }
    }

    private void publishHealth() {
        synchronized (this) {
            publishHealth(health());
        }
    }

    private synchronized void cancelScheduledTasks() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushScheduled = false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (!pending.isEmpty()) {
            scheduleTick(Duration.ZERO);
        } else {
            cancelScheduledTasks();
        }
    }
}
