package top.ellan.mahjong.persistence.sql.match;

import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.lobby.LobbySqlTransactions;
import top.ellan.mahjong.persistence.sql.recovery.MatchRecoveryData;
import top.ellan.mahjong.persistence.sql.recovery.RecoveredAction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.ParticipantRole;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.SeatId;

/** Match metadata and recovery queries. Mutating calls belong on an IO executor. */
public final class JdbcMatchRepository {
    private final SqlConnectionFactory connections;

    public JdbcMatchRepository(SqlConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public void createMatch(MatchInstanceRecord match) throws SQLException {
        Objects.requireNonNull(match, "match");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                Optional<MatchInstanceRecord> existing =
                        MatchIdentitySql.find(connection, match.binding().matchId());
                if (existing.isPresent()) {
                    if (!MatchIdentitySql.sameIdentity(existing.orElseThrow(), match)) {
                        throw new PersistenceConflictException(
                                "Match id already belongs to different provenance");
                    }
                } else {
                    MatchIdentitySql.insert(connection, match);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public void createRecoverableMatch(
            MatchInstanceRecord match, RuleStateSnapshot initialSnapshot) throws SQLException {
        createRecoverableMatch(match, List.of(), initialSnapshot);
    }

    public void createRecoverableMatch(
            MatchInstanceRecord match,
            List<TableParticipant> participants,
            RuleStateSnapshot initialSnapshot) throws SQLException {
        createRecoverableMatch(match, participants, initialSnapshot, Optional.empty(), false);
    }

    /** Creates provenance, participants, the initial snapshot and anchor in one transaction. */
    public void createRecoverableMatch(
            MatchInstanceRecord match,
            List<TableParticipant> participants,
            RuleStateSnapshot initialSnapshot,
            TableAnchor anchor) throws SQLException {
        createRecoverableMatch(
                match,
                participants,
                initialSnapshot,
                Optional.of(Objects.requireNonNull(anchor, "anchor")),
                false);
    }

    /** Atomically replaces a durable lobby with the pinned initial match boundary. */
    public void createRecoverableMatchFromLobby(
            MatchInstanceRecord match,
            List<TableParticipant> participants,
            RuleStateSnapshot initialSnapshot,
            TableAnchor anchor) throws SQLException {
        createRecoverableMatch(
                match,
                participants,
                initialSnapshot,
                Optional.of(Objects.requireNonNull(anchor, "anchor")),
                true);
    }

    private void createRecoverableMatch(
            MatchInstanceRecord match,
            List<TableParticipant> participants,
            RuleStateSnapshot initialSnapshot,
            Optional<TableAnchor> anchor,
            boolean consumeLobby) throws SQLException {
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        Objects.requireNonNull(anchor, "anchor");
        if (anchor.isPresent() && !anchor.orElseThrow().tableId().equals(match.tableId())) {
            throw new IllegalArgumentException("Match and anchor table ids differ");
        }
        if (initialSnapshot.sequence() != 0
                || initialSnapshot.schemaVersion()
                        != match.binding().rulePack().stateSchemaVersion()) {
            throw new IllegalArgumentException(
                    "Initial snapshot must use sequence zero and the pinned schema");
        }
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                Optional<MatchInstanceRecord> existing =
                        MatchIdentitySql.find(connection, match.binding().matchId());
                if (existing.isEmpty()) {
                    MatchIdentitySql.insert(connection, match);
                } else if (!MatchIdentitySql.sameIdentity(existing.orElseThrow(), match)) {
                    throw new PersistenceConflictException(
                            "Match id already belongs to different provenance");
                }
                RecoverableMatchSql.insertOrVerifyParticipants(
                        connection,
                        match.binding().matchId(),
                        participants);
                RecoverableMatchSql.insertOrVerifyInitialSnapshot(
                        connection,
                        match,
                        initialSnapshot);
                if (anchor.isPresent()) {
                    RecoverableMatchSql.insertOrVerifyAnchor(
                            connection,
                            anchor.orElseThrow());
                }
                if (consumeLobby
                    && !LobbySqlTransactions.delete(connection, match.tableId())) {
                    throw new PersistenceConflictException(
                            "lobby disappeared before match activation");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public Optional<MatchInstanceRecord> find(MatchId matchId) throws SQLException {
        try (Connection connection = connections.open()) {
            return MatchIdentitySql.find(connection, matchId);
        }
    }

    public void updateStatus(MatchId matchId, TableLifecycle status, Instant updatedAt)
            throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE match_instance SET status = ?, updated_at = ? WHERE match_id = ?")) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, Timestamp.from(updatedAt));
            statement.setString(3, matchId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Match does not exist: " + matchId);
            }
        }
    }

    public Optional<RuleStateSnapshot> latestSnapshot(MatchId matchId, long committedSequence)
            throws SQLException {
        return latestStoredSnapshot(matchId, committedSequence).map(StoredSnapshot::snapshot);
    }

    private Optional<StoredSnapshot> latestStoredSnapshot(
            MatchId matchId, long committedSequence) throws SQLException {
        String sql =
                "SELECT snapshot_sequence, state_revision, state_schema_version, "
                        + "snapshot_payload, snapshot_sha256 "
                        + "FROM match_snapshot WHERE match_id = ? AND snapshot_sequence <= ? "
                        + "ORDER BY snapshot_sequence DESC";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, matchId.toString());
            statement.setLong(2, committedSequence);
            statement.setMaxRows(1);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                RuleStateSnapshot snapshot = new RuleStateSnapshot(
                        result.getInt("state_schema_version"),
                        result.getLong("snapshot_sequence"),
                        result.getBytes("snapshot_payload"),
                        result.getString("snapshot_sha256"));
                return Optional.of(new StoredSnapshot(
                        snapshot, result.getLong("state_revision")));
            }
        }
    }

    public Set<RulePackRef> referencedRulePacks() throws SQLException {
        String sql =
                "SELECT DISTINCT rule_id, rule_version, rule_jar_sha256, state_schema_version "
                        + "FROM match_instance WHERE status IN ('STARTING','ACTIVE',"
                        + "'PAUSED_PERSISTENCE','BLOCKED_RULE_PACK','NEEDS_ADMIN_REVIEW')";
        Set<RulePackRef> result = new LinkedHashSet<>();
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(
                        new RulePackRef(
                                new RuleId(rows.getString("rule_id")),
                                rows.getString("rule_version"),
                                rows.getString("rule_jar_sha256"),
                                rows.getInt("state_schema_version")));
            }
        }
        return Set.copyOf(result);
    }

    /** Matches that must be restored or explicitly blocked during startup. */
    public List<MatchInstanceRecord> recoverableMatches() throws SQLException {
        String sql = "SELECT * FROM match_instance WHERE status IN ('STARTING','ACTIVE',"
                + "'PAUSED_PERSISTENCE','BLOCKED_RULE_PACK','NEEDS_ADMIN_REVIEW') "
                + "ORDER BY created_at, match_id";
        List<MatchInstanceRecord> result = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                        result.add(MatchIdentitySql.read(rows));
            }
        }
        return List.copyOf(result);
    }

    public MatchRecoveryData recover(MatchId matchId) throws SQLException {
        MatchInstanceRecord match =
                find(matchId)
                        .orElseThrow(
                                () -> new PersistenceConflictException("Match does not exist"));
        StoredSnapshot storedSnapshot =
                latestStoredSnapshot(matchId, match.lastCommittedSequence())
                        .orElseThrow(
                                () ->
                                        new PersistenceConflictException(
                                                "Recoverable match has no durable snapshot"));
        return new MatchRecoveryData(
                match,
                loadParticipants(matchId),
                storedSnapshot.snapshot(),
                storedSnapshot.stateRevision(),
                actionsBetween(
                        matchId,
                        storedSnapshot.snapshot().sequence(),
                        match.lastCommittedSequence()));
    }

    private List<TableParticipant> loadParticipants(MatchId matchId) throws SQLException {
        String sql = "SELECT player_id, participant_role, seat_id FROM match_participant "
                + "WHERE match_id = ? ORDER BY player_id";
        List<TableParticipant> result = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, matchId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ParticipantRole role = ParticipantRole.valueOf(rows.getString("participant_role"));
                    String seat = rows.getString("seat_id");
                    result.add(new TableParticipant(
                            PlayerId.parse(rows.getString("player_id")),
                            role,
                            seat == null
                                    ? Optional.empty()
                                    : Optional.of(new SeatId(Integer.parseInt(seat)))));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<RecoveredAction> actionsBetween(MatchId matchId, long after, long through)
            throws SQLException {
        if (after >= through) {
            return List.of();
        }
        String sql =
                "SELECT event_sequence, state_revision, actor_id, action_type, action_payload, "
                        + "event_type, event_payload, before_state_sha256, after_state_sha256 "
                        + "FROM match_event WHERE match_id = ? AND event_sequence > ? "
                        + "AND event_sequence <= ? ORDER BY event_sequence";
        List<RecoveredAction> actions = new ArrayList<>();
        PendingRecoveredAction pending = null;
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, matchId.toString());
            statement.setLong(2, after);
            statement.setLong(3, through);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long revision = rows.getLong("state_revision");
                    if (pending == null || pending.stateRevision != revision) {
                        if (pending != null) {
                            actions.add(pending.finish());
                        }
                        pending = PendingRecoveredAction.start(rows);
                    } else {
                        pending.add(rows);
                    }
                }
            }
        }
        if (pending != null) {
            actions.add(pending.finish());
        }
        return List.copyOf(actions);
    }

    private record StoredSnapshot(RuleStateSnapshot snapshot, long stateRevision) {
        private StoredSnapshot {
            Objects.requireNonNull(snapshot, "snapshot");
            if (stateRevision < 0) {
                throw new IllegalArgumentException("Stored snapshot revision must be non-negative");
            }
        }
    }

    private static final class PendingRecoveredAction {
        private final long stateRevision;
        private final long firstSequence;
        private long lastSequence;
        private final PlayerId actor;
        private final RuleAction action;
        private final String beforeHash;
        private final String afterHash;
        private final List<RuleEvent> events = new ArrayList<>();

        private PendingRecoveredAction(
                long stateRevision,
                long firstSequence,
                PlayerId actor,
                RuleAction action,
                String beforeHash,
                String afterHash) {
            this.stateRevision = stateRevision;
            this.firstSequence = firstSequence;
            lastSequence = firstSequence;
            this.actor = actor;
            this.action = action;
            this.beforeHash = beforeHash;
            this.afterHash = afterHash;
        }

        static PendingRecoveredAction start(ResultSet row) throws SQLException {
            PendingRecoveredAction pending =
                    new PendingRecoveredAction(
                            row.getLong("state_revision"),
                            row.getLong("event_sequence"),
                            PlayerId.parse(row.getString("actor_id")),
                            new RuleAction(
                                    row.getString("action_type"),
                                    row.getBytes("action_payload")),
                            row.getString("before_state_sha256"),
                            row.getString("after_state_sha256"));
            pending.events.add(
                    new RuleEvent(
                            row.getString("event_type"), row.getBytes("event_payload")));
            return pending;
        }

        void add(ResultSet row) throws SQLException {
            RuleAction nextAction =
                    new RuleAction(
                            row.getString("action_type"), row.getBytes("action_payload"));
            if (!actor.equals(PlayerId.parse(row.getString("actor_id")))
                    || !action.type().equals(nextAction.type())
                    || !Arrays.equals(action.payload(), nextAction.payload())
                    || !beforeHash.equals(row.getString("before_state_sha256"))
                    || !afterHash.equals(row.getString("after_state_sha256"))) {
                throw new PersistenceConflictException(
                        "Rows for one state revision contain different actions or hashes");
            }
            long sequence = row.getLong("event_sequence");
            if (sequence != lastSequence + 1) {
                throw new PersistenceConflictException("Recovered event sequence has a gap");
            }
            lastSequence = sequence;
            events.add(
                    new RuleEvent(
                            row.getString("event_type"), row.getBytes("event_payload")));
        }

        RecoveredAction finish() {
            return new RecoveredAction(
                    stateRevision,
                    firstSequence,
                    lastSequence,
                    actor,
                    action,
                    events,
                    beforeHash,
                    afterHash);
        }
    }
}
