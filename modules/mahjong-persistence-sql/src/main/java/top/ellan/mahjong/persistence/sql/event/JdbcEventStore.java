package top.ellan.mahjong.persistence.sql.event;

import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import top.ellan.mahjong.application.persistence.EventStorePort;
import top.ellan.mahjong.application.persistence.MatchEventRecord;
import top.ellan.mahjong.application.persistence.MatchWriteBatch;
import top.ellan.mahjong.application.persistence.PersistAck;
import top.ellan.mahjong.application.persistence.SnapshotWrite;
import top.ellan.mahjong.spi.RuleStateSnapshot;

/** Ordered, idempotent JDBC event store. All blocking calls run on the supplied bounded IO executor. */
public final class JdbcEventStore implements EventStorePort {
    private final SqlConnectionFactory connections;
    private final Executor ioExecutor;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public JdbcEventStore(SqlConnectionFactory connections, Executor ioExecutor) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    @Override
    public CompletionStage<PersistAck> appendBatch(MatchWriteBatch batch) {
        Objects.requireNonNull(batch, "batch");
        try {
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            PersistAck ack = appendNow(batch);
                            available.set(true);
                            return ack;
                        } catch (SQLException failure) {
                            available.set(false);
                            throw new CompletionException(failure);
                        }
                    },
                    ioExecutor);
        } catch (RejectedExecutionException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public boolean available() {
        return available.get();
    }

    /** Synchronous startup probe; invoke it only from bootstrap/IO code, never a region thread. */
    public boolean probe() {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement("SELECT 1");
                ResultSet result = statement.executeQuery()) {
            boolean healthy = result.next();
            available.set(healthy);
            return healthy;
        } catch (SQLException failure) {
            available.set(false);
            return false;
        }
    }

    private PersistAck appendNow(MatchWriteBatch batch) throws SQLException {
        try (Connection connection = connections.open()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long committed = lockCommittedSequence(connection, batch.matchId().toString());
                long batchFirst = batch.events().getFirst().sequence();
                long batchLast = batch.events().getLast().sequence();
                Map<Long, ExistingEvent> existing =
                        committed < batchFirst
                                ? Map.of()
                                : loadExisting(
                                        connection,
                                        batch.matchId().toString(),
                                        batchFirst,
                                        Math.min(committed, batchLast));
                long expectedNew = committed + 1;
                java.util.ArrayList<MatchEventRecord> newEvents = new java.util.ArrayList<>();
                for (MatchEventRecord event : batch.events()) {
                    if (event.sequence() <= committed) {
                        ExistingEvent stored = existing.get(event.sequence());
                        if (stored == null || !stored.same(event)) {
                            throw new PersistenceConflictException(
                                    "Idempotent event retry differs at sequence " + event.sequence());
                        }
                    } else {
                        if (event.sequence() != expectedNew++) {
                            throw new PersistenceConflictException(
                                    "Event sequence gap after " + committed);
                        }
                        newEvents.add(event);
                    }
                }
                insertEvents(connection, newEvents);
                long acknowledged = Math.max(committed, batchLast);
                if (batch.snapshot().isPresent()) {
                    persistSnapshot(connection, batch.snapshot().orElseThrow(), acknowledged);
                }
                updateCommittedSequence(
                        connection,
                        batch.matchId().toString(),
                        acknowledged,
                        batch.events().getLast().acceptedAt(),
                        batch.snapshot().flatMap(SnapshotWrite::lifecycleAfterCommit));
                connection.commit();
                return new PersistAck(acknowledged);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private static long lockCommittedSequence(Connection connection, String matchId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT last_committed_sequence FROM match_instance "
                                + "WHERE match_id = ? FOR UPDATE")) {
            statement.setString(1, matchId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceConflictException("Cannot append events to an unknown match");
                }
                return result.getLong(1);
            }
        }
    }

    private static Map<Long, ExistingEvent> loadExisting(
            Connection connection, String matchId, long first, long last) throws SQLException {
        String sql =
                "SELECT event_sequence, state_revision, actor_id, action_type, action_payload, "
                        + "event_type, event_payload, before_state_sha256, after_state_sha256 "
                        + "FROM match_event WHERE match_id = ? AND event_sequence BETWEEN ? AND ?";
        Map<Long, ExistingEvent> events = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, matchId);
            statement.setLong(2, first);
            statement.setLong(3, last);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.put(
                            result.getLong("event_sequence"),
                            new ExistingEvent(
                                    result.getLong("state_revision"),
                                    result.getString("actor_id"),
                                    result.getString("action_type"),
                                    result.getBytes("action_payload"),
                                    result.getString("event_type"),
                                    result.getBytes("event_payload"),
                                    result.getString("before_state_sha256"),
                                    result.getString("after_state_sha256")));
                }
            }
        }
        return events;
    }

    private static void insertEvents(Connection connection, java.util.List<MatchEventRecord> events)
            throws SQLException {
        if (events.isEmpty()) {
            return;
        }
        String sql =
                "INSERT INTO match_event (match_id, event_sequence, state_revision, accepted_at, "
                        + "actor_id, action_type, action_payload, event_type, event_payload, "
                        + "before_state_sha256, after_state_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (MatchEventRecord event : events) {
                statement.setString(1, event.matchId().toString());
                statement.setLong(2, event.sequence());
                statement.setLong(3, event.stateRevision());
                statement.setTimestamp(4, Timestamp.from(event.acceptedAt()));
                statement.setString(5, event.actor().toString());
                statement.setString(6, event.action().type());
                statement.setBytes(7, event.action().payload());
                statement.setString(8, event.event().type());
                statement.setBytes(9, event.event().canonicalPayload());
                statement.setString(10, event.beforeStateSha256());
                statement.setString(11, event.afterStateSha256());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void persistSnapshot(
            Connection connection, SnapshotWrite write, long acknowledged) throws SQLException {
        RuleStateSnapshot snapshot = write.snapshot();
        if (snapshot.sequence() > acknowledged) {
            throw new PersistenceConflictException("Snapshot is ahead of committed events");
        }
        String selectSql =
                "SELECT state_revision, state_schema_version, snapshot_payload, snapshot_sha256 "
                        + "FROM match_snapshot WHERE match_id = ? AND snapshot_sequence = ?";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, write.matchId().toString());
            select.setLong(2, snapshot.sequence());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    if (result.getLong("state_revision") != write.stateRevision()
                            || result.getInt("state_schema_version") != snapshot.schemaVersion()
                            || !Arrays.equals(
                                    result.getBytes("snapshot_payload"), snapshot.payload())
                            || !result.getString("snapshot_sha256").equals(snapshot.sha256())) {
                        throw new PersistenceConflictException(
                                "Idempotent snapshot retry differs at sequence "
                                        + snapshot.sequence());
                    }
                    return;
                }
            }
        }
        String insertSql =
                "INSERT INTO match_snapshot (match_id, snapshot_sequence, state_revision, "
                        + "state_schema_version, snapshot_payload, snapshot_sha256, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setString(1, write.matchId().toString());
            insert.setLong(2, snapshot.sequence());
            insert.setLong(3, write.stateRevision());
            insert.setInt(4, snapshot.schemaVersion());
            insert.setBytes(5, snapshot.payload());
            insert.setString(6, snapshot.sha256());
            insert.setTimestamp(7, Timestamp.from(write.createdAt()));
            insert.executeUpdate();
        }
    }

    private static void updateCommittedSequence(
            Connection connection,
            String matchId,
            long committedSequence,
            java.time.Instant updatedAt,
            java.util.Optional<top.ellan.mahjong.domain.TableLifecycle> lifecycle)
            throws SQLException {
        String sql = lifecycle.isPresent()
                ? "UPDATE match_instance SET last_committed_sequence = ?, updated_at = ?, status = ? "
                        + "WHERE match_id = ?"
                : "UPDATE match_instance SET last_committed_sequence = ?, updated_at = ? "
                        + "WHERE match_id = ?";
        try (PreparedStatement statement =
                connection.prepareStatement(sql)) {
            statement.setLong(1, committedSequence);
            statement.setTimestamp(2, Timestamp.from(updatedAt));
            int matchIdIndex;
            if (lifecycle.isPresent()) {
                statement.setString(3, lifecycle.orElseThrow().name());
                matchIdIndex = 4;
            } else {
                matchIdIndex = 3;
            }
            statement.setString(matchIdIndex, matchId);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("Match disappeared during event append");
            }
        }
    }

    private record ExistingEvent(
            long stateRevision,
            String actorId,
            String actionType,
            byte[] actionPayload,
            String eventType,
            byte[] eventPayload,
            String beforeHash,
            String afterHash) {
        private boolean same(MatchEventRecord event) {
            return stateRevision == event.stateRevision()
                    && actorId.equals(event.actor().toString())
                    && actionType.equals(event.action().type())
                    && Arrays.equals(actionPayload, event.action().payload())
                    && eventType.equals(event.event().type())
                    && Arrays.equals(eventPayload, event.event().canonicalPayload())
                    && beforeHash.equals(event.beforeStateSha256())
                    && afterHash.equals(event.afterStateSha256());
        }
    }
}
