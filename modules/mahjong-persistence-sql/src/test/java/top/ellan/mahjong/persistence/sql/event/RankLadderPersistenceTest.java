package top.ellan.mahjong.persistence.sql.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.history.RankProgression;
import top.ellan.mahjong.application.history.RankProgressionPort;
import top.ellan.mahjong.application.persistence.MatchEventRecord;
import top.ellan.mahjong.application.persistence.MatchWriteBatch;
import top.ellan.mahjong.application.persistence.SnapshotWrite;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.match.RankMatchLength;
import top.ellan.mahjong.domain.match.RankRoom;
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
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleMatchResult;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RulePlayerResult;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.SeatId;

/** Proves the rank ladder is persisted by a finished match and never applied twice by a retry. */
class RankLadderPersistenceTest {
    private static final PlayerId WINNER = PlayerId.parse("00000000-0000-0000-0000-000000000001");
    private static final PlayerId LOSER = PlayerId.parse("00000000-0000-0000-0000-000000000004");
    private static final PlayerId MIDDLE_A = PlayerId.parse("00000000-0000-0000-0000-000000000002");
    private static final PlayerId MIDDLE_B = PlayerId.parse("00000000-0000-0000-0000-000000000003");

    /** The production policy, pinned to a south-game gold room so the numbers are predictable. */
    private static final RankProgressionPort GOLD_SOUTH = request ->
            RankProgression.apply(
                            request.standings(),
                            request.current(),
                            RankRoom.GOLD,
                            RankMatchLength.SOUTH)
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().updated()));

    private SqlConnectionFactory connections;
    private JdbcMatchRepository matches;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        connections = () -> DriverManager.getConnection(url, "sa", "");
        new SqlSchemaMigrator(connections).migrate();
        matches = new JdbcMatchRepository(connections);
    }

    @Test
    void aFinishedMatchMovesBothSeatsAlongTheLadder() throws Exception {
        MatchId matchId = finishOneMatch(GOLD_SOUTH, 1);

        // 40000 points, first place, gold south: (40000-25000)/1000 + 15 uma + 80 room = 110,
        // which cascades the whole novice band and lands inside Adept 1 (entry 300).
        Stage winner = readStage(WINNER);
        assertEquals("ADEPT", winner.tier());
        assertEquals(1, winner.level());
        assertEquals(390, winner.stagePoints());
        assertEquals(1, winner.matchCount());
        assertEquals(1, winner.firstPlaces());

        // The loser stays pinned at the novice floor: 1.5.0 never demotes a beginner.
        Stage loser = readStage(LOSER);
        assertEquals("NOVICE", loser.tier());
        assertEquals(1, loser.level());
        assertEquals(0, loser.stagePoints());
        assertEquals(1, loser.fourthPlaces());
        assertTrue(matchId != null);
    }

    @Test
    void replayingTheSameTerminalResultNeverPromotesTwice() throws Exception {
        MatchId matchId = finishOneMatch(GOLD_SOUTH, 1);
        Stage afterFirstWrite = readStage(WINNER);

        // Re-append the identical batch, exactly as a retry after a lost acknowledgement would.
        appendTerminal(matchId, GOLD_SOUTH, 1);

        Stage afterRetry = readStage(WINNER);
        assertEquals(afterFirstWrite.tier(), afterRetry.tier());
        assertEquals(afterFirstWrite.level(), afterRetry.level());
        assertEquals(afterFirstWrite.stagePoints(), afterRetry.stagePoints());
        assertEquals(1, afterRetry.matchCount());
        assertEquals(1, afterRetry.firstPlaces());
    }

    @Test
    void withoutARankingPolicyScoresAreStillRecordedAtTheStartingStage() throws Exception {
        finishOneMatch(RankProgressionPort.NONE, 1);

        Stage winner = readStage(WINNER);
        assertEquals("NOVICE", winner.tier());
        assertEquals(1, winner.level());
        assertEquals(0, winner.stagePoints());
        // The match itself is still counted, so history and totals stay correct.
        assertEquals(1, winner.matchCount());
        assertEquals(1, winner.firstPlaces());
    }

    @Test
    void tierOrdinalIsStoredSoTheLeaderboardCanOrderByIndex() throws Exception {
        finishOneMatch(GOLD_SOUTH, 1);

        // ADEPT is ordinal 1 and NOVICE ordinal 0; ordering by name would invert a real ladder.
        assertEquals(1, readStage(WINNER).tierOrdinal());
        assertEquals(0, readStage(LOSER).tierOrdinal());
    }

    @Test
    void theLeaderboardRanksByLadderStandingNotByAccumulatedScore() throws Exception {
        finishOneMatch(GOLD_SOUTH, 1);
        // Give the last-place seat a far larger score total while leaving it at Novice 1. Ordering by
        // accumulated points or score would float it to the top; the ladder must keep it below.
        try (Connection connection = connections.open();
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE player_rank_summary SET total_score = ?, "
                                + "ranking_points_milli = ? WHERE player_id = ?")) {
            update.setLong(1, 9_000_000L);
            update.setLong(2, 9_000_000_000L);
            update.setString(3, LOSER.toString());
            assertEquals(1, update.executeUpdate());
        }

        var page = new top.ellan.mahjong.persistence.sql.history.JdbcPlayerRecordQuery(connections)
                .ranking(LOSER, new RuleId("riichi"), 1, 10);

        // First and second place both cleared the novice band into Adept 1; third and fourth were
        // held at the novice floor. A nine-million score cannot lift a novice past an adept, but it
        // does win the tiebreak against the other novice, which is the documented ordering.
        assertEquals(WINNER, page.entries().getFirst().playerId());
        assertEquals("ADEPT", page.entries().getFirst().profile().tier().name());
        assertEquals("ADEPT", page.entries().get(1).profile().tier().name());
        assertEquals(LOSER, page.entries().get(2).playerId());
        assertEquals("NOVICE", page.entries().get(2).profile().tier().name());
        assertEquals(MIDDLE_B, page.entries().getLast().playerId());
        assertEquals(3, page.ownEntry().orElseThrow().position());
    }

    private MatchId finishOneMatch(RankProgressionPort progression, long sequence) throws Exception {
        MatchInstanceRecord record = match(Instant.parse("2026-08-08T00:00:00Z"));
        matches.createRecoverableMatch(record, participants(), snapshot());
        MatchId matchId = record.binding().matchId();
        appendTerminal(matchId, progression, sequence);
        return matchId;
    }

    private void appendTerminal(MatchId matchId, RankProgressionPort progression, long sequence) {
        JdbcEventStore events = new JdbcEventStore(connections, Runnable::run, progression);
        RuleMatchResult result = new RuleMatchResult(
                "riichi.mahjong-soul.v1",
                List.of(
                        new RulePlayerResult(
                                WINNER, new SeatId(0), 1, 40_000, 40_000_000, new byte[] {1}),
                        new RulePlayerResult(
                                MIDDLE_A, new SeatId(1), 2, 28_000, 28_000_000, new byte[] {2}),
                        new RulePlayerResult(
                                MIDDLE_B, new SeatId(2), 3, 20_000, 20_000_000, new byte[] {3}),
                        new RulePlayerResult(
                                LOSER, new SeatId(3), 4, 12_000, 12_000_000, new byte[] {4})));
        SnapshotWrite terminal = new SnapshotWrite(
                matchId,
                sequence,
                Instant.parse("2026-08-08T00:01:00Z"),
                new RuleStateSnapshot(
                        1, 1, ByteBuffer.allocate(4).putInt(1).array(), "1".repeat(64)),
                Optional.of(TableLifecycle.FINISHED),
                Optional.of(result));
        MatchEventRecord event = new MatchEventRecord(
                matchId,
                sequence,
                1,
                Instant.parse("2026-08-08T00:01:00Z"),
                WINNER,
                new RuleAction("discard", new byte[] {7}),
                new RuleEvent("discarded", new byte[] {8}),
                "0".repeat(64),
                "1".repeat(64));
        events.appendBatch(new MatchWriteBatch(matchId, List.of(event), Optional.of(terminal)))
                .toCompletableFuture()
                .join();
    }

    private record Stage(
            String tier,
            int tierOrdinal,
            int level,
            int stagePoints,
            long matchCount,
            long firstPlaces,
            long fourthPlaces) {}

    private Stage readStage(PlayerId playerId) throws Exception {
        String sql = "SELECT tier, tier_ordinal, tier_level, stage_points, match_count, "
                + "first_places, fourth_places FROM player_rank_summary WHERE player_id = ?";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "no rank summary row for " + playerId);
                return new Stage(
                        rows.getString("tier"),
                        rows.getInt("tier_ordinal"),
                        rows.getInt("tier_level"),
                        rows.getInt("stage_points"),
                        rows.getLong("match_count"),
                        rows.getLong("first_places"),
                        rows.getLong("fourth_places"));
            }
        }
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
                new TableParticipant(WINNER, ParticipantRole.PLAYER, Optional.of(new SeatId(0))),
                new TableParticipant(MIDDLE_A, ParticipantRole.PLAYER, Optional.of(new SeatId(1))),
                new TableParticipant(MIDDLE_B, ParticipantRole.PLAYER, Optional.of(new SeatId(2))),
                new TableParticipant(LOSER, ParticipantRole.PLAYER, Optional.of(new SeatId(3))));
    }

    private static RuleStateSnapshot snapshot() {
        return new RuleStateSnapshot(
                1, 0, ByteBuffer.allocate(4).putInt(0).array(), "0".repeat(64));
    }
}
