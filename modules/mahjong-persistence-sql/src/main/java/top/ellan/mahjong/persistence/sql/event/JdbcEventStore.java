package top.ellan.mahjong.persistence.sql.event;
import top.ellan.mahjong.domain.table.TableLifecycle;

import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import top.ellan.mahjong.spi.RuleMatchResult;
import top.ellan.mahjong.spi.RulePlayerResult;

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
                    SnapshotWrite snapshot = batch.snapshot().orElseThrow();
                    persistSnapshot(connection, snapshot, acknowledged);
                    if (snapshot.matchResult().isPresent()) {
                        persistMatchResult(
                                connection,
                                snapshot,
                                snapshot.matchResult().orElseThrow());
                    }
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

    private static void persistMatchResult(
            Connection connection, SnapshotWrite snapshot, RuleMatchResult result)
            throws SQLException {
        for (RulePlayerResult player : result.players()) {
            verifyParticipant(connection, snapshot.matchId().toString(), player);
            persistPlayerResult(connection, snapshot.matchId().toString(), player);
            persistRankLedger(
                    connection,
                    snapshot,
                    result.rankSystem(),
                    player);
        }
    }

    private static void verifyParticipant(
            Connection connection, String matchId, RulePlayerResult player) throws SQLException {
        String sql = "SELECT seat_id FROM match_participant WHERE match_id = ? AND player_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, matchId);
            statement.setString(2, player.playerId().toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()
                        || !Integer.toString(player.seatId().value())
                                .equals(row.getString("seat_id"))) {
                    throw new PersistenceConflictException(
                            "Terminal result player is not bound to the reported seat");
                }
            }
        }
    }

    private static void persistPlayerResult(
            Connection connection, String matchId, RulePlayerResult player) throws SQLException {
        String selectSql = "SELECT seat_index, placement, score, ranking_points_milli, "
                + "result_payload FROM player_result WHERE match_id = ? AND player_id = ?";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, matchId);
            select.setString(2, player.playerId().toString());
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    if (row.getInt("seat_index") != player.seatId().value()
                            || row.getInt("placement") != player.placement()
                            || row.getLong("score") != player.score()
                            || row.getLong("ranking_points_milli")
                                    != player.rankingPointsMilli()
                            || !Arrays.equals(
                                    row.getBytes("result_payload"),
                                    player.canonicalPayload())) {
                        throw new PersistenceConflictException(
                                "Idempotent player result retry differs");
                    }
                    return;
                }
            }
        }
        String insertSql = "INSERT INTO player_result (match_id, player_id, seat_index, "
                + "placement, score, ranking_points_milli, result_payload) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setString(1, matchId);
            insert.setString(2, player.playerId().toString());
            insert.setInt(3, player.seatId().value());
            insert.setInt(4, player.placement());
            insert.setLong(5, player.score());
            insert.setLong(6, player.rankingPointsMilli());
            insert.setBytes(7, player.canonicalPayload());
            insert.executeUpdate();
        }
    }

    private static void persistRankLedger(
            Connection connection,
            SnapshotWrite snapshot,
            String rankSystem,
            RulePlayerResult player) throws SQLException {
        String ledgerId = UUID.nameUUIDFromBytes(("mahjong-rank-ledger|"
                        + snapshot.matchId()
                        + "|"
                        + rankSystem
                        + "|"
                        + player.playerId())
                .getBytes(StandardCharsets.UTF_8))
                .toString();
        String selectSql = "SELECT match_id, player_id, rank_system, ranking_points_milli, "
                + "delta_payload FROM rank_ledger WHERE ledger_id = ?";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, ledgerId);
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    if (!snapshot.matchId().toString().equals(row.getString("match_id"))
                            || !player.playerId().toString().equals(row.getString("player_id"))
                            || !rankSystem.equals(row.getString("rank_system"))
                            || row.getLong("ranking_points_milli")
                                    != player.rankingPointsMilli()
                            || !Arrays.equals(
                                    row.getBytes("delta_payload"),
                                    player.canonicalPayload())) {
                        throw new PersistenceConflictException(
                                "Idempotent rank ledger retry differs");
                    }
                    return;
                }
            }
        }
        String insertSql = "INSERT INTO rank_ledger (ledger_id, match_id, player_id, "
                + "rank_system, ranking_points_milli, delta_payload, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setString(1, ledgerId);
            insert.setString(2, snapshot.matchId().toString());
            insert.setString(3, player.playerId().toString());
            insert.setString(4, rankSystem);
            insert.setLong(5, player.rankingPointsMilli());
            insert.setBytes(6, player.canonicalPayload());
            insert.setTimestamp(7, Timestamp.from(snapshot.createdAt()));
            insert.executeUpdate();
        }
    }

    private static void updateCommittedSequence(
            Connection connection,
            String matchId,
            long committedSequence,
            java.time.Instant updatedAt,
            java.util.Optional<top.ellan.mahjong.domain.table.TableLifecycle> lifecycle)
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
