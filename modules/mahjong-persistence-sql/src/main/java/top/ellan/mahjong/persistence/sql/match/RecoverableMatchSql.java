package top.ellan.mahjong.persistence.sql.match;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.SeatId;

/** Idempotent SQL for the initial snapshot, participants and physical table anchor. */
final class RecoverableMatchSql {
    private RecoverableMatchSql() {}

    static void insertOrVerifyAnchor(Connection connection, TableAnchor anchor)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
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
        try (PreparedStatement insert =
                connection.prepareStatement(
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

    static void insertOrVerifyInitialSnapshot(
            Connection connection,
            MatchInstanceRecord match,
            RuleStateSnapshot snapshot)
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
                            || !Arrays.equals(
                                    result.getBytes("snapshot_payload"), snapshot.payload())
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

    static void insertOrVerifyParticipants(
            Connection connection,
            MatchId matchId,
            List<TableParticipant> participants)
            throws SQLException {
        List<TableParticipant> existing = loadParticipants(connection, matchId);
        List<TableParticipant> expected =
                participants.stream()
                        .sorted(Comparator.comparing(TableParticipant::playerId))
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
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO match_participant "
                                + "(match_id, player_id, participant_role, seat_id) "
                                + "VALUES (?, ?, ?, ?)")) {
            for (TableParticipant participant : expected) {
                insert.setString(1, matchId.toString());
                insert.setString(2, participant.playerId().toString());
                insert.setString(3, participant.role().name());
                if (participant.seat().isPresent()) {
                    insert.setString(
                            4,
                            Integer.toString(
                                    participant.seat().orElseThrow().value()));
                } else {
                    insert.setNull(4, java.sql.Types.VARCHAR);
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static List<TableParticipant> loadParticipants(
            Connection connection,
            MatchId matchId)
            throws SQLException {
        List<TableParticipant> existing = new ArrayList<>();
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT player_id, participant_role, seat_id FROM match_participant "
                                + "WHERE match_id = ? ORDER BY player_id")) {
            select.setString(1, matchId.toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ParticipantRole role =
                            ParticipantRole.valueOf(rows.getString("participant_role"));
                    String seat = rows.getString("seat_id");
                    existing.add(
                            new TableParticipant(
                                    PlayerId.parse(rows.getString("player_id")),
                                    role,
                                    seat == null
                                            ? Optional.empty()
                                            : Optional.of(
                                                    new SeatId(Integer.parseInt(seat)))));
                }
            }
        }
        return existing;
    }
}
