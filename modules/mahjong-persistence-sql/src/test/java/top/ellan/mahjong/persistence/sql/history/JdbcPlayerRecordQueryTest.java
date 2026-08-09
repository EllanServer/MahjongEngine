package top.ellan.mahjong.persistence.sql.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.SeatId;

class JdbcPlayerRecordQueryTest {
    private static final PlayerId FIRST =
            PlayerId.parse("00000000-0000-0000-0000-000000000001");
    private static final PlayerId SECOND =
            PlayerId.parse("00000000-0000-0000-0000-000000000002");

    private SqlConnectionFactory connections;
    private JdbcMatchRepository matches;
    private JdbcPlayerRecordQuery query;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        connections = () -> DriverManager.getConnection(url, "sa", "");
        new SqlSchemaMigrator(connections).migrate();
        matches = new JdbcMatchRepository(connections);
        query = new JdbcPlayerRecordQuery(connections);
    }

    @Test
    void returnsBoundedHistoryAndOwnLeaderboardPosition() throws Exception {
        MatchInstanceRecord finished = match(Instant.parse("2026-08-08T00:00:00Z"));
        matches.createRecoverableMatch(finished, participants(), snapshot());
        insertResults(finished.binding().matchId());
        matches.updateStatus(
                finished.binding().matchId(),
                TableLifecycle.FINISHED,
                Instant.parse("2026-08-08T00:01:00Z"));

        MatchInstanceRecord active = match(Instant.parse("2026-08-08T00:02:00Z"));
        matches.createRecoverableMatch(active, participants(), snapshot());

        var history = query.history(FIRST, 0, 10);
        assertEquals(2, history.size());
        assertTrue(history.getFirst().outcome().isEmpty());
        assertEquals(1, history.getLast().outcome().orElseThrow().placement());

        var ranking = query.ranking(SECOND, new RuleId("riichi"), 1, 1);
        assertEquals("riichi.mahjong-soul.v1", ranking.rankSystem().orElseThrow());
        assertEquals(FIRST, ranking.entries().getFirst().playerId());
        assertTrue(ranking.hasNext());
        assertEquals(2, ranking.ownEntry().orElseThrow().position());
    }

    private void insertResults(MatchId matchId) throws Exception {
        try (var connection = connections.open()) {
            connection.setAutoCommit(false);
            try (var result = connection.prepareStatement(
                            "INSERT INTO player_result (match_id, player_id, seat_index, "
                                    + "placement, score, ranking_points_milli, result_payload) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)");
                    var ledger = connection.prepareStatement(
                            "INSERT INTO rank_ledger (ledger_id, match_id, player_id, "
                                    + "rank_system, ranking_points_milli, delta_payload, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insertResult(result, matchId, FIRST, 0, 1, 31_000, 4_000);
                insertResult(result, matchId, SECOND, 1, 2, 19_000, 3_000);
                result.executeBatch();
                insertLedger(ledger, matchId, FIRST, 4_000);
                insertLedger(ledger, matchId, SECOND, 3_000);
                ledger.executeBatch();
            }
            connection.commit();
        }
    }

    private static void insertResult(
            java.sql.PreparedStatement statement,
            MatchId matchId,
            PlayerId player,
            int seat,
            int placement,
            long score,
            long points)
            throws Exception {
        statement.setString(1, matchId.toString());
        statement.setString(2, player.toString());
        statement.setInt(3, seat);
        statement.setInt(4, placement);
        statement.setLong(5, score);
        statement.setLong(6, points);
        statement.setBytes(7, new byte[] {(byte) placement});
        statement.addBatch();
    }

    private static void insertLedger(
            java.sql.PreparedStatement statement,
            MatchId matchId,
            PlayerId player,
            long points)
            throws Exception {
        statement.setString(1, UUID.randomUUID().toString());
        statement.setString(2, matchId.toString());
        statement.setString(3, player.toString());
        statement.setString(4, "riichi.mahjong-soul.v1");
        statement.setLong(5, points);
        statement.setBytes(6, new byte[] {1});
        statement.setTimestamp(7, Timestamp.from(Instant.parse("2026-08-08T00:01:00Z")));
        statement.addBatch();
    }

    private static MatchInstanceRecord match(Instant createdAt) {
        MatchBinding binding = new MatchBinding(
                MatchId.random(),
                new RulePackRef(new RuleId("riichi"), "2.0.1", "a".repeat(64), 1),
                new ProfileId("mahjong-soul"),
                "b".repeat(64),
                createdAt);
        return new MatchInstanceRecord(
                binding, TableId.random(), TableLifecycle.ACTIVE, createdAt, 0);
    }

    private static List<TableParticipant> participants() {
        return List.of(
                new TableParticipant(
                        FIRST, ParticipantRole.PLAYER, Optional.of(new SeatId(0))),
                new TableParticipant(
                        SECOND, ParticipantRole.PLAYER, Optional.of(new SeatId(1))));
    }

    private static RuleStateSnapshot snapshot() {
        return new RuleStateSnapshot(
                1,
                0,
                ByteBuffer.allocate(4).putInt(0).array(),
                "0".repeat(64));
    }
}
