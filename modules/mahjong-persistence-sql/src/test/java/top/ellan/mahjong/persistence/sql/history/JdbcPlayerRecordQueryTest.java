package top.ellan.mahjong.persistence.sql.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.persistence.MatchEventRecord;
import top.ellan.mahjong.application.persistence.MatchWriteBatch;
import top.ellan.mahjong.application.persistence.SnapshotWrite;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.event.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleMatchResult;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RulePlayerResult;
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
        assertEquals(1, ranking.entries().getFirst().position());
        assertTrue(ranking.hasNext());
        assertEquals(2, ranking.ownEntry().orElseThrow().position());

        var secondPage = query.ranking(SECOND, new RuleId("riichi"), 2, 1);
        assertEquals(SECOND, secondPage.entries().getFirst().playerId());
        assertEquals(2, secondPage.entries().getFirst().position());
        assertFalse(secondPage.hasNext());
    }

    /**
     * Writes the terminal result through the production event store so the leaderboard projection
     * is maintained exactly the way a finished match maintains it.
     */
    private void insertResults(MatchId matchId) throws Exception {
        JdbcEventStore events = new JdbcEventStore(connections, Runnable::run);
        RuleMatchResult result = new RuleMatchResult(
                "riichi.mahjong-soul.v1",
                List.of(
                        new RulePlayerResult(
                                FIRST, new SeatId(0), 1, 31_000, 4_000, new byte[] {1}),
                        new RulePlayerResult(
                                SECOND, new SeatId(1), 2, 19_000, 3_000, new byte[] {2})));
        SnapshotWrite terminal = new SnapshotWrite(
                matchId,
                1,
                Instant.parse("2026-08-08T00:01:00Z"),
                new RuleStateSnapshot(
                        1, 1, ByteBuffer.allocate(4).putInt(1).array(), "1".repeat(64)),
                Optional.of(TableLifecycle.FINISHED),
                Optional.of(result));
        MatchEventRecord event = new MatchEventRecord(
                matchId,
                1,
                1,
                Instant.parse("2026-08-08T00:01:00Z"),
                FIRST,
                new RuleAction("discard", new byte[] {7}),
                new RuleEvent("discarded", new byte[] {8}),
                "0".repeat(64),
                "1".repeat(64));
        events.appendBatch(new MatchWriteBatch(matchId, List.of(event), Optional.of(terminal)))
                .toCompletableFuture()
                .join();
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
