package top.ellan.mahjong.persistence.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/** Creates the platform-neutral event, snapshot, result and rank projection tables. */
public final class SqlSchemaMigrator {
    public static final int SCHEMA_VERSION = 1;

    private final SqlConnectionFactory connections;

    public SqlSchemaMigrator(SqlConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public void migrate() throws SQLException {
        try (Connection connection = connections.open()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                SqlDialect dialect =
                        SqlDialect.fromProductName(
                                connection.getMetaData().getDatabaseProductName());
                try (Statement statement = connection.createStatement()) {
                    for (String ddl : statements(dialect)) {
                        statement.execute(ddl);
                    }
                }
                recordSchemaVersion(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private static void recordSchemaVersion(Connection connection) throws SQLException {
        Integer existing = null;
        try (PreparedStatement select =
                        connection.prepareStatement(
                                "SELECT schema_version FROM mahjong_schema_version WHERE component = ?")) {
            select.setString(1, "event-store");
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    existing = result.getInt(1);
                }
            }
        }
        if (existing != null) {
            if (existing != SCHEMA_VERSION) {
                throw new SQLException(
                        "Database schema is incompatible with MahjongPaper 2.0: " + existing);
            }
            return;
        }
        try (PreparedStatement write = connection.prepareStatement(
                "INSERT INTO mahjong_schema_version (schema_version, component) VALUES (?, ?)")) {
            write.setInt(1, SCHEMA_VERSION);
            write.setString(2, "event-store");
            write.executeUpdate();
        }
    }

    private static List<String> statements(SqlDialect dialect) {
        String binary = dialect.binaryType();
        return List.of(
                "CREATE TABLE IF NOT EXISTS mahjong_schema_version ("
                        + "component VARCHAR(64) PRIMARY KEY, schema_version INT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS match_instance ("
                        + "match_id VARCHAR(36) PRIMARY KEY, table_id VARCHAR(36) NOT NULL, "
                        + "rule_id VARCHAR(32) NOT NULL, rule_version VARCHAR(64) NOT NULL, "
                        + "rule_jar_sha256 CHAR(64) NOT NULL, state_schema_version INT NOT NULL, "
                        + "profile_id VARCHAR(64) NOT NULL, "
                        + "configuration_sha256 CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, "
                        + "created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, "
                        + "last_committed_sequence BIGINT NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS match_event ("
                        + "match_id VARCHAR(36) NOT NULL, event_sequence BIGINT NOT NULL, "
                        + "state_revision BIGINT NOT NULL, "
                        + "accepted_at TIMESTAMP(6) NOT NULL, actor_id VARCHAR(36) NOT NULL, "
                        + "action_type VARCHAR(64) NOT NULL, action_payload "
                        + binary
                        + " NOT NULL, event_type VARCHAR(64) NOT NULL, event_payload "
                        + binary
                        + " NOT NULL, before_state_sha256 CHAR(64) NOT NULL, "
                        + "after_state_sha256 CHAR(64) NOT NULL, "
                        + "PRIMARY KEY (match_id, event_sequence), "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))",
                "CREATE TABLE IF NOT EXISTS match_participant ("
                        + "match_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL, "
                        + "participant_role VARCHAR(16) NOT NULL, seat_id VARCHAR(32), "
                        + "PRIMARY KEY (match_id, player_id), "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))",
                "CREATE TABLE IF NOT EXISTS table_anchor ("
                        + "table_id VARCHAR(36) PRIMARY KEY, world_id VARCHAR(128) NOT NULL, "
                        + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                        + "yaw REAL NOT NULL, pitch REAL NOT NULL)",
                "CREATE TABLE IF NOT EXISTS match_snapshot ("
                        + "match_id VARCHAR(36) NOT NULL, snapshot_sequence BIGINT NOT NULL, "
                        + "state_revision BIGINT NOT NULL, "
                        + "state_schema_version INT NOT NULL, snapshot_payload "
                        + binary
                        + " NOT NULL, snapshot_sha256 CHAR(64) NOT NULL, "
                        + "created_at TIMESTAMP(6) NOT NULL, "
                        + "PRIMARY KEY (match_id, snapshot_sequence), "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))",
                "CREATE TABLE IF NOT EXISTS round_result ("
                        + "match_id VARCHAR(36) NOT NULL, round_index INT NOT NULL, "
                        + "result_type VARCHAR(64) NOT NULL, result_payload "
                        + binary
                        + " NOT NULL, created_at TIMESTAMP(6) NOT NULL, "
                        + "PRIMARY KEY (match_id, round_index), "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))",
                "CREATE TABLE IF NOT EXISTS player_result ("
                        + "match_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL, "
                        + "seat_index INT NOT NULL, placement INT NOT NULL, score BIGINT NOT NULL, "
                        + "result_payload "
                        + binary
                        + " NOT NULL, PRIMARY KEY (match_id, player_id), "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))",
                "CREATE TABLE IF NOT EXISTS rank_ledger ("
                        + "ledger_id VARCHAR(36) PRIMARY KEY, match_id VARCHAR(36) NOT NULL, "
                        + "player_id VARCHAR(36) NOT NULL, rank_system VARCHAR(64) NOT NULL, "
                        + "delta_payload "
                        + binary
                        + " NOT NULL, created_at TIMESTAMP(6) NOT NULL, "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))");
    }
}
