package top.ellan.mahjong.persistence.sql.history;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;

/**
 * Guards the leaderboard access paths against silently degrading into table scans.
 *
 * <p>The ladder ordering is expressed as a lexicographic comparison over five keys, which a planner
 * can easily fail to match against the composite index. These tests read the actual H2 plan rather
 * than assuming, because the cost only becomes visible on a populated leaderboard.
 */
class RankingQueryPlanTest {
    private SqlConnectionFactory connections;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        connections = () -> DriverManager.getConnection(url, "sa", "");
        new SqlSchemaMigrator(connections).migrate();
        populateLeaderboard(2_000);
    }

    /** A planner on an empty table picks arbitrarily, so the plan is only meaningful with rows. */
    private void populateLeaderboard(int players) throws Exception {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO player_rank_summary (rule_id, rank_system, player_id, "
                            + "ranking_points_milli, total_score, match_count, tier, tier_ordinal, "
                            + "tier_level, stage_points, first_places, second_places, third_places, "
                            + "fourth_places, updated_at) "
                            + "VALUES ('riichi', 'riichi.mahjong-soul.v1', ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "0, 0, 0, 0, CURRENT_TIMESTAMP)")) {
                for (int index = 0; index < players; index++) {
                    int tierOrdinal = index % 6;
                    insert.setString(1, String.format("player-%05d", index));
                    insert.setLong(2, index * 1_000L);
                    insert.setLong(3, index * 37L);
                    insert.setLong(4, 1 + index % 50);
                    insert.setString(
                            5,
                            new String[] {
                                "NOVICE", "ADEPT", "EXPERT", "MASTER", "SAINT", "CELESTIAL"
                            }[tierOrdinal]);
                    insert.setInt(6, tierOrdinal);
                    insert.setInt(7, 1 + index % 3);
                    insert.setInt(8, index % 900);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            try (Statement analyze = connection.createStatement()) {
                analyze.execute("ANALYZE");
            }
        }
    }

    @Test
    void theLadderOrderedPageUsesTheLadderIndex() throws Exception {
        // Mirror JdbcPlayerRecordQuery.rankingRange. The inner page stays fully inside the narrow
        // ladder index; the outer lookup fetches the wide display row for at most 50 selected ids.
        String plan = explain(
                "SELECT s.player_id, s.ranking_points_milli, s.total_score, s.match_count, "
                        + "s.tier, s.tier_level, s.stage_points, s.first_places, s.second_places, "
                        + "s.third_places, s.fourth_places FROM player_rank_summary s JOIN ("
                        + "SELECT rule_id, rank_system, player_id, tier_ordinal, tier_level, "
                        + "stage_points, total_score FROM player_rank_summary "
                        + "WHERE rule_id = 'riichi' AND rank_system = 'riichi.mahjong-soul.v1' "
                        + "ORDER BY tier_ordinal DESC, tier_level DESC, stage_points DESC, "
                        + "total_score DESC, player_id DESC LIMIT 51 OFFSET 250) p "
                        + "ON s.rule_id = p.rule_id AND s.rank_system = p.rank_system "
                        + "AND s.player_id = p.player_id "
                        + "ORDER BY p.tier_ordinal DESC, p.tier_level DESC, p.stage_points DESC, "
                        + "p.total_score DESC, p.player_id DESC");

        assertTrue(
                plan.contains("idx_rank_summary_ladder"),
                "leaderboard page should read the ladder index, plan was: " + plan);
        assertFalse(plan.contains("tablescan"), "leaderboard page fell back to a scan: " + plan);
    }

    @Test
    void theOwnPositionCountStaysInsideOneLeaderboardPartition() throws Exception {
        // The five-way OR chain expresses "sorts ahead of me". Even when the planner cannot use the
        // ladder columns, it must still restrict the scan to this rule and rank system rather than
        // counting every row in the table.
        String plan = explain(
                "SELECT COUNT(*) FROM player_rank_summary WHERE rule_id = 'riichi' "
                        + "AND rank_system = 'riichi.mahjong-soul.v1' AND (tier_ordinal > 1 "
                        + "OR (tier_ordinal = 1 AND tier_level > 2) "
                        + "OR (tier_ordinal = 1 AND tier_level = 2 AND stage_points > 300) "
                        + "OR (tier_ordinal = 1 AND tier_level = 2 AND stage_points = 300 "
                        + "AND total_score > 100) "
                        + "OR (tier_ordinal = 1 AND tier_level = 2 AND stage_points = 300 "
                        + "AND total_score = 100 AND player_id > 'a'))");

        assertTrue(
                plan.contains("idx_rank_summary_ladder") || plan.contains("idx_rank_summary_board"),
                "own-position count should enter through a rule-scoped index, plan was: " + plan);
        assertFalse(
                plan.contains("player_rank_summary.tablescan"),
                "own-position count degraded into a full table scan: " + plan);
    }

    /**
     * The ladder index must list every {@code ORDER BY} key, in order. An index that omits a middle
     * key diverges from the requested ordering at that point, so no planner can use it — which is
     * exactly how this index was first written: {@code total_score} was missing, and the leaderboard
     * silently sorted its whole partition in memory on every request.
     */
    @Test
    void theLadderIndexCoversEveryOrderByKeyInOrder() throws Exception {
        java.util.List<String> columns = new java.util.ArrayList<>();
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT c.column_name FROM information_schema.index_columns c "
                                + "WHERE c.table_name = 'PLAYER_RANK_SUMMARY' "
                                + "AND c.index_name = 'IDX_RANK_SUMMARY_LADDER' "
                                + "ORDER BY c.ordinal_position")) {
            while (rows.next()) {
                columns.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(
                        "rule_id",
                        "rank_system",
                        "tier_ordinal",
                        "tier_level",
                        "stage_points",
                        "total_score",
                        "player_id"),
                columns,
                "ladder index must mirror the leaderboard ORDER BY exactly");
    }

    private String explain(String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
