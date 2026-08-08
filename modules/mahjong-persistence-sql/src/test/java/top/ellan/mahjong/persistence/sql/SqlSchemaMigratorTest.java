package top.ellan.mahjong.persistence.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlSchemaMigratorTest {
    @Test
    void upgradesVersionOneSnapshotTableAndAddsParticipantMetadata() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE match_snapshot ("
                            + "match_id VARCHAR(36) NOT NULL, snapshot_sequence BIGINT NOT NULL, "
                            + "state_schema_version INT NOT NULL, snapshot_payload BLOB NOT NULL, "
                            + "snapshot_sha256 CHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL, "
                            + "PRIMARY KEY (match_id, snapshot_sequence))");
        }

        new SqlSchemaMigrator(connections).migrate();

        try (Connection connection = connections.open()) {
            boolean revisionColumn = false;
            boolean participantTable = false;
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, "%", "%")) {
                while (columns.next()) {
                    revisionColumn |= "MATCH_SNAPSHOT".equalsIgnoreCase(
                                    columns.getString("TABLE_NAME"))
                            && "STATE_REVISION".equalsIgnoreCase(
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
}
