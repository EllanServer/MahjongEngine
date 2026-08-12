package top.ellan.mahjong.persistence.sql.schema;

import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlSchemaMigratorTest {
    @Test
    void createsTheCompleteCurrentSchema() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");

        new SqlSchemaMigrator(connections).migrate();

        try (Connection connection = connections.open()) {
            boolean revisionColumn = false;
            boolean rankingPointsColumn = false;
            boolean participantTable = false;
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, "%", "%")) {
                while (columns.next()) {
                    revisionColumn |= "MATCH_SNAPSHOT".equalsIgnoreCase(
                                    columns.getString("TABLE_NAME"))
                            && "STATE_REVISION".equalsIgnoreCase(
                                    columns.getString("COLUMN_NAME"));
                    rankingPointsColumn |= "PLAYER_RESULT".equalsIgnoreCase(
                                    columns.getString("TABLE_NAME"))
                            && "RANKING_POINTS_MILLI".equalsIgnoreCase(
                                    columns.getString("COLUMN_NAME"));
                }
            }
            try (ResultSet tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    participantTable |= "MATCH_PARTICIPANT".equalsIgnoreCase(
                            tables.getString("TABLE_NAME"));
                }
            }
            assertTrue(revisionColumn);
            assertTrue(rankingPointsColumn);
            assertTrue(participantTable);
            try (Statement statement = connection.createStatement();
                    ResultSet version = statement.executeQuery(
                            "SELECT schema_version FROM mahjong_schema_version "
                                    + "WHERE component = 'event-store'")) {
                assertTrue(version.next());
                assertEquals(SqlSchemaMigrator.SCHEMA_VERSION, version.getInt(1));
            }
        }
    }

    @Test
    void rejectsAnyDifferentSchemaVersion() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE mahjong_schema_version ("
                            + "component VARCHAR(64) PRIMARY KEY, schema_version INT NOT NULL)");
            statement.execute(
                    "INSERT INTO mahjong_schema_version (component, schema_version) "
                            + "VALUES ('event-store', 0)");
        }

        assertThrows(java.sql.SQLException.class, () -> new SqlSchemaMigrator(connections).migrate());
    }

    @Test
    void createsEveryIndexTheQueryPathsDependOn() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");

        new SqlSchemaMigrator(connections).migrate();
        // A second run must be a no-op rather than failing on existing indexes.
        new SqlSchemaMigrator(connections).migrate();

        java.util.Set<String> expected = java.util.Set.of(
                "IDX_PLAYER_RESULT_PLAYER",
                "IDX_RANK_LEDGER_SYSTEM_PLAYER",
                "IDX_MATCH_PARTICIPANT_PLAYER",
                "IDX_MATCH_INSTANCE_UPDATED",
                "IDX_MATCH_INSTANCE_STATUS",
                "IDX_MATCH_INSTANCE_TABLE_STATUS",
                "IDX_MATCH_INSTANCE_RULE",
                "IDX_RANK_LEDGER_MATCH",
                "IDX_RANK_LEDGER_CREATED",
                "IDX_RANK_SUMMARY_BOARD");
        java.util.Set<String> found = new java.util.HashSet<>();
        try (Connection connection = connections.open()) {
            for (String table : List.of(
                    "PLAYER_RESULT",
                    "RANK_LEDGER",
                    "MATCH_PARTICIPANT",
                    "MATCH_INSTANCE",
                    "PLAYER_RANK_SUMMARY")) {
                try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                        connection.getCatalog(), null, table, false, false)) {
                    while (indexes.next()) {
                        String name = indexes.getString("INDEX_NAME");
                        if (name != null) {
                            found.add(name.toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
        }
        assertTrue(found.containsAll(expected), "missing indexes: " + minus(expected, found));
    }

    @Test
    void backfillsTheLeaderboardProjectionFromExistingLedgerRows() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");
        new SqlSchemaMigrator(connections).migrate();
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM player_rank_summary");
            statement.execute(
                    "INSERT INTO match_instance (match_id, table_id, rule_id, rule_version, "
                            + "rule_jar_sha256, state_schema_version, profile_id, "
                            + "configuration_sha256, status, created_at, updated_at, "
                            + "last_committed_sequence) VALUES ('m1', 't1', 'riichi', '2.0.1', "
                            + "'" + "a".repeat(64) + "', 1, 'standard', '" + "b".repeat(64) + "', "
                            + "'FINISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
            statement.execute(
                    "INSERT INTO match_participant (match_id, player_id, participant_role, seat_id) "
                            + "VALUES ('m1', 'p1', 'PLAYER', '0')");
            statement.execute(
                    "INSERT INTO player_result (match_id, player_id, seat_index, placement, "
                            + "score, ranking_points_milli, result_payload) "
                            + "VALUES ('m1', 'p1', 0, 1, 31000, 4000, X'01')");
            statement.execute(
                    "INSERT INTO rank_ledger (ledger_id, match_id, player_id, rank_system, "
                            + "ranking_points_milli, delta_payload, created_at) "
                            + "VALUES ('l1', 'm1', 'p1', 'riichi.v1', 4000, X'01', CURRENT_TIMESTAMP)");
        }

        new SqlSchemaMigrator(connections).migrate();

        try (Connection connection = connections.open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT rule_id, rank_system, player_id, ranking_points_milli, "
                                + "total_score, match_count FROM player_rank_summary")) {
            assertTrue(rows.next());
            assertEquals("riichi", rows.getString(1));
            assertEquals("riichi.v1", rows.getString(2));
            assertEquals("p1", rows.getString(3));
            assertEquals(4_000L, rows.getLong(4));
            assertEquals(31_000L, rows.getLong(5));
            assertEquals(1L, rows.getLong(6));
        }
    }

    private static java.util.Set<String> minus(
            java.util.Set<String> expected, java.util.Set<String> found) {
        java.util.Set<String> remaining = new java.util.HashSet<>(expected);
        remaining.removeAll(found);
        return remaining;
    }

    @Test
    void upgradesVersionTwoResultProjectionWithoutDroppingRows() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE mahjong_schema_version ("
                            + "component VARCHAR(64) PRIMARY KEY, schema_version INT NOT NULL)");
            statement.execute(
                    "INSERT INTO mahjong_schema_version (component, schema_version) "
                            + "VALUES ('event-store', 2)");
            statement.execute(
                    "CREATE TABLE player_result (match_id VARCHAR(36) NOT NULL, "
                            + "player_id VARCHAR(36) NOT NULL, seat_index INT NOT NULL, "
                            + "placement INT NOT NULL, score BIGINT NOT NULL, "
                            + "result_payload BLOB NOT NULL, PRIMARY KEY (match_id, player_id))");
            statement.execute(
                    "CREATE TABLE rank_ledger (ledger_id VARCHAR(36) PRIMARY KEY, "
                            + "match_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL, "
                            + "rank_system VARCHAR(64) NOT NULL, delta_payload BLOB NOT NULL, "
                            + "created_at TIMESTAMP(6) NOT NULL)");
        }

        new SqlSchemaMigrator(connections).migrate();

        try (Connection connection = connections.open();
                ResultSet columns = connection.getMetaData().getColumns(
                        connection.getCatalog(), null, "%", "%")) {
            int found = 0;
            while (columns.next()) {
                if ("RANKING_POINTS_MILLI".equalsIgnoreCase(
                                columns.getString("COLUMN_NAME"))
                        && ("PLAYER_RESULT".equalsIgnoreCase(columns.getString("TABLE_NAME"))
                                || "RANK_LEDGER".equalsIgnoreCase(
                                        columns.getString("TABLE_NAME")))) {
                    found++;
                }
            }
            assertEquals(2, found);
        }
    }
}
