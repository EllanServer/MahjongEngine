package top.ellan.mahjong.persistence.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.ParticipantRole;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.spi.ProfileId;
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
                Optional<MatchInstanceRecord> existing = find(connection, match.binding().matchId());
                if (existing.isPresent()) {
                    if (!sameIdentity(existing.orElseThrow(), match)) {
                        throw new PersistenceConflictException(
                                "Match id already belongs to different provenance");
                    }
                } else {
                    insert(connection, match);
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
                Optional<MatchInstanceRecord> existing = find(connection, match.binding().matchId());
                if (existing.isEmpty()) {
                    insert(connection, match);
                } else if (!sameIdentity(existing.orElseThrow(), match)) {
                    throw new PersistenceConflictException(
                            "Match id already belongs to different provenance");
                }
                insertOrVerifyParticipants(connection, match.binding().matchId(), participants);
                insertOrVerifyInitialSnapshot(connection, match, initialSnapshot);
                if (anchor.isPresent()) {
                    insertOrVerifyAnchor(connection, anchor.orElseThrow());
                }
                if (consumeLobby
                        && !JdbcTableLobbyRepository.deleteWithin(connection, match.tableId())) {
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

    private static void insertOrVerifyAnchor(Connection connection, TableAnchor anchor)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT world_id, x, y, z, yaw, pitch FROM table_anchor WHERE table_id = ?")) {
            select.setString(1, anchor.tableId().toString());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    TableAnchor existing =
                            new TableAnchor(
                                    anchor.tableId(),
                                    result.getString("world_id"),
                                    result.getDouble("x"),
                                    result.getDouble("y"),
                                    result.getDouble("z"),
                                    result.getFloat("yaw"),
                                    result.getFloat("pitch"));
                    if (!existing.equals(anchor)) {
                        throw new PersistenceConflictException(
                                "Table id already belongs to a different anchor");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO table_anchor (table_id, world_id, x, y, z, yaw, pitch) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, anchor.tableId().toString());
            insert.setString(2, anchor.worldId());
            insert.setDouble(3, anchor.x());
            insert.setDouble(4, anchor.y());
            insert.setDouble(5, anchor.z());
            insert.setFloat(6, anchor.yaw());
            insert.setFloat(7, anchor.pitch());
            insert.executeUpdate();
        }
    }

    public Optional<MatchInstanceRecord> find(MatchId matchId) throws SQLException {
        try (Connection connection = connections.open()) {
            return find(connection, matchId);
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
                result.add(readMatch(rows));
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

    private static Optional<MatchInstanceRecord> find(Connection connection, MatchId matchId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT * FROM match_instance WHERE match_id = ?")) {
            statement.setString(1, matchId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readMatch(result));
            }
        }
    }

    private static MatchInstanceRecord readMatch(ResultSet result) throws SQLException {
        RulePackRef reference =
                new RulePackRef(
                        new RuleId(result.getString("rule_id")),
                        result.getString("rule_version"),
                        result.getString("rule_jar_sha256"),
                        result.getInt("state_schema_version"));
        MatchBinding binding =
                new MatchBinding(
                        MatchId.parse(result.getString("match_id")),
                        reference,
                        new ProfileId(result.getString("profile_id")),
                        result.getString("configuration_sha256"),
                        result.getTimestamp("created_at").toInstant());
        return new MatchInstanceRecord(
                binding,
                TableId.parse(result.getString("table_id")),
                TableLifecycle.valueOf(result.getString("status")),
                result.getTimestamp("updated_at").toInstant(),
                result.getLong("last_committed_sequence"));
    }

    private static void insert(Connection connection, MatchInstanceRecord match)
            throws SQLException {
        String sql =
                "INSERT INTO match_instance (match_id, table_id, rule_id, rule_version, "
                        + "rule_jar_sha256, state_schema_version, profile_id, "
                        + "configuration_sha256, status, created_at, updated_at, "
                        + "last_committed_sequence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            MatchBinding binding = match.binding();
            statement.setString(1, binding.matchId().toString());
            statement.setString(2, match.tableId().toString());
            statement.setString(3, binding.rulePack().ruleId().value());
            statement.setString(4, binding.rulePack().version());
            statement.setString(5, binding.rulePack().jarSha256());
            statement.setInt(6, binding.rulePack().stateSchemaVersion());
            statement.setString(7, binding.profile().value());
            statement.setString(8, binding.configurationSha256());
            statement.setString(9, match.status().name());
            statement.setTimestamp(10, Timestamp.from(binding.createdAt()));
            statement.setTimestamp(11, Timestamp.from(match.updatedAt()));
            statement.setLong(12, match.lastCommittedSequence());
            statement.executeUpdate();
        }
    }

    private static boolean sameIdentity(
            MatchInstanceRecord left, MatchInstanceRecord right) {
        return left.binding().equals(right.binding()) && left.tableId().equals(right.tableId());
    }

    private static void insertOrVerifyInitialSnapshot(
            Connection connection, MatchInstanceRecord match, RuleStateSnapshot snapshot)
            throws SQLException {
        String selectSql =
                "SELECT state_revision, state_schema_version, snapshot_payload, snapshot_sha256 "
                        + "FROM match_snapshot WHERE match_id = ? AND snapshot_sequence = 0";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, match.binding().matchId().toString());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    if (result.getLong("state_revision") != 0L
                            || result.getInt("state_schema_version") != snapshot.schemaVersion()
                            || !Arrays.equals(result.getBytes("snapshot_payload"), snapshot.payload())
                            || !result.getString("snapshot_sha256").equals(snapshot.sha256())) {
                        throw new PersistenceConflictException(
                                "Initial snapshot differs from the existing match");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO match_snapshot (match_id, snapshot_sequence, "
                                + "state_revision, state_schema_version, snapshot_payload, "
                                + "snapshot_sha256, created_at) VALUES (?, 0, 0, ?, ?, ?, ?)")) {
            insert.setString(1, match.binding().matchId().toString());
            insert.setInt(2, snapshot.schemaVersion());
            insert.setBytes(3, snapshot.payload());
            insert.setString(4, snapshot.sha256());
            insert.setTimestamp(5, Timestamp.from(match.binding().createdAt()));
            insert.executeUpdate();
        }
    }

    private static void insertOrVerifyParticipants(
            Connection connection, MatchId matchId, List<TableParticipant> participants)
            throws SQLException {
        List<TableParticipant> existing = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT player_id, participant_role, seat_id FROM match_participant "
                        + "WHERE match_id = ? ORDER BY player_id")) {
            select.setString(1, matchId.toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ParticipantRole role = ParticipantRole.valueOf(rows.getString("participant_role"));
                    String seat = rows.getString("seat_id");
                    existing.add(new TableParticipant(
                            PlayerId.parse(rows.getString("player_id")),
                            role,
                            seat == null
                                    ? Optional.empty()
                                    : Optional.of(new SeatId(Integer.parseInt(seat)))));
                }
            }
        }
        List<TableParticipant> expected = participants.stream()
                .sorted(java.util.Comparator.comparing(TableParticipant::playerId))
                .toList();
        if (!existing.isEmpty()) {
            if (!existing.equals(expected)) {
                throw new PersistenceConflictException(
                        "Match participants differ from existing recovery metadata");
            }
            return;
        }
        if (expected.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO match_participant (match_id, player_id, participant_role, seat_id) "
                        + "VALUES (?, ?, ?, ?)")) {
            for (TableParticipant participant : expected) {
                insert.setString(1, matchId.toString());
                insert.setString(2, participant.playerId().toString());
                insert.setString(3, participant.role().name());
                if (participant.seat().isPresent()) {
                    insert.setString(
                            4, Integer.toString(participant.seat().orElseThrow().value()));
                } else {
                    insert.setNull(4, java.sql.Types.VARCHAR);
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
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
