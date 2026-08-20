package top.ellan.mahjong.persistence.sql.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;

/**
 * Exercises the leaderboard migration and access plan on the real external engines we support.
 *
 * <p>H2 catches SQL and index-shape regressions locally, but cannot prove what another optimizer
 * chooses. GitHub supplies fresh MySQL and MariaDB service containers and enables these tests with
 * environment variables; an ordinary local {@code check} skips them when no service is configured.
 */
class ExternalRankingQueryPlanTest {
    private static final int TARGET_PLAYERS = 12_000;
    private static final int OTHER_PLAYERS = 8_000;
    private static final List<String> LADDER_COLUMNS = List.of(
            "rule_id",
            "rank_system",
            "tier_ordinal",
            "tier_level",
            "stage_points",
            "total_score",
            "player_id");
    // Mirrors JdbcPlayerRecordQuery.rankingRange: a covered inner page followed by at most 51
    // primary-key lookups. The outer order may sort those fifty rows; the inner partition must not.
    private static final String PAGE_QUERY =
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
                    + "p.total_score DESC, p.player_id DESC";

    @Test
    @EnabledIfEnvironmentVariable(named = "MAHJONG_MYSQL_TEST_URL", matches = "jdbc:mysql:.+")
    void mysqlUsesTheLadderIndexWithoutFilesort() throws Exception {
        verifyExternalEngine(System.getenv("MAHJONG_MYSQL_TEST_URL"), "mysql");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MAHJONG_MARIADB_TEST_URL", matches = "jdbc:mariadb:.+")
    void mariaDbUsesTheLadderIndexWithoutFilesort() throws Exception {
        verifyExternalEngine(System.getenv("MAHJONG_MARIADB_TEST_URL"), "mariadb");
    }

    private static void verifyExternalEngine(String url, String expectedProduct) throws Exception {
        String user = environmentOrDefault("MAHJONG_SQL_TEST_USER", "mahjong");
        String password = environmentOrDefault("MAHJONG_SQL_TEST_PASSWORD", "mahjong");
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, user, password);

        String productDescription;
        try (Connection connection = connections.open()) {
            String product = connection.getMetaData().getDatabaseProductName();
            productDescription = product + " " + connection.getMetaData().getDatabaseProductVersion();
            assertTrue(
                    product.toLowerCase(Locale.ROOT).contains(expectedProduct),
                    "test URL reached the wrong database product: " + productDescription);
        }

        new SqlSchemaMigrator(connections).migrate();
        populateLeaderboard(connections);
        assertIndexShape(connections);

        List<QueryPlan> plans = explain(connections, PAGE_QUERY);
        QueryPlan ladder = plans.stream()
                .filter(plan -> plan.key().equalsIgnoreCase("idx_rank_summary_ladder"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "leaderboard inner page did not choose the ladder index: " + plans));
        assertNotEquals(
                "all",
                ladder.accessType().toLowerCase(Locale.ROOT),
                "leaderboard inner page degraded into a full table scan: " + plans);
        assertFalse(
                ladder.extra().toLowerCase(Locale.ROOT).contains("filesort"),
                "ladder index did not serve the inner ORDER BY: " + plans);
        System.out.println(
                "EXTERNAL_SQL_PLAN product=" + productDescription + " plans=" + plans);
    }

    /**
     * A planner on an empty or tiny table may legitimately choose a scan. The mixed population makes
     * the rule/rank-system prefix selective while leaving enough target rows for ORDER BY to matter.
     */
    private static void populateLeaderboard(SqlConnectionFactory connections) throws Exception {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            String insert = "INSERT INTO player_rank_summary (rule_id, rank_system, player_id, "
                    + "ranking_points_milli, total_score, match_count, tier, tier_ordinal, "
                    + "tier_level, stage_points, first_places, second_places, third_places, "
                    + "fourth_places, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                    + "0, 0, 0, 0, CURRENT_TIMESTAMP)";
            try (var statement = connection.prepareStatement(insert)) {
                addPlayers(statement, "riichi", "riichi.mahjong-soul.v1", "target", TARGET_PLAYERS);
                addPlayers(statement, "mcr", "mcr.standard.v1", "other", OTHER_PLAYERS);
                statement.executeBatch();
            }
            connection.commit();
            try (Statement analyze = connection.createStatement()) {
                analyze.execute("ANALYZE TABLE player_rank_summary");
            }
        }
    }

    private static void addPlayers(
            java.sql.PreparedStatement insert,
            String ruleId,
            String rankSystem,
            String prefix,
            int players)
            throws Exception {
        String[] tiers = {"NOVICE", "ADEPT", "EXPERT", "MASTER", "SAINT", "CELESTIAL"};
        for (int index = 0; index < players; index++) {
            int tier = index % tiers.length;
            insert.setString(1, ruleId);
            insert.setString(2, rankSystem);
            insert.setString(3, String.format(Locale.ROOT, "%s-%05d", prefix, index));
            insert.setLong(4, index * 1_000L);
            insert.setLong(5, index * 37L);
            insert.setLong(6, 1 + index % 50);
            insert.setString(7, tiers[tier]);
            insert.setInt(8, tier);
            insert.setInt(9, 1 + index % 3);
            insert.setInt(10, index % 900);
            insert.addBatch();
        }
    }

    /** MySQL and MariaDB expose the same index metadata contract here. */
    private static void assertIndexShape(SqlConnectionFactory connections) throws Exception {
        List<String> columns = new ArrayList<>();
        List<String> directions = new ArrayList<>();
        String query = "SELECT column_name, collation FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'player_rank_summary' "
                + "AND index_name = 'idx_rank_summary_ladder' ORDER BY seq_in_index";
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                columns.add(rows.getString("column_name").toLowerCase(Locale.ROOT));
                directions.add(rows.getString("collation"));
            }
        }
        assertEquals(LADDER_COLUMNS, columns, "external engine built the wrong ladder index");
        assertTrue(
                directions.stream().allMatch("A"::equalsIgnoreCase),
                "ladder index must be uniformly ascending for portable backward scans: "
                        + directions);
    }

    private static List<QueryPlan> explain(SqlConnectionFactory connections, String query)
            throws Exception {
        List<QueryPlan> plans = new ArrayList<>();
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("EXPLAIN " + query)) {
            while (rows.next()) {
                plans.add(new QueryPlan(
                        valueOrEmpty(rows.getString("table")),
                        valueOrEmpty(rows.getString("type")),
                        valueOrEmpty(rows.getString("key")),
                        valueOrEmpty(rows.getString("Extra")),
                        valueOrEmpty(rows.getString("possible_keys"))));
            }
        }
        assertFalse(plans.isEmpty(), "EXPLAIN returned no rows");
        return List.copyOf(plans);
    }

    private static String environmentOrDefault(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record QueryPlan(
            String table, String accessType, String key, String extra, String possibleKeys) {}
}
