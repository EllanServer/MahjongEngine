package top.ellan.mahjong.persistence.sql.schema;

import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
