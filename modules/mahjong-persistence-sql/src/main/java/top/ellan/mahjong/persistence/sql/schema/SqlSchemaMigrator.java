package top.ellan.mahjong.persistence.sql.schema;

import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.connection.SqlDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates the platform-neutral event, snapshot, result and rank projection tables. */
public final class SqlSchemaMigrator {
    public static final int SCHEMA_VERSION = 4;

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
                // One catalog scan for every table this migration touches; the previous code
                // re-listed the whole catalog for each column and index probe.
                Map<String, String> tableNames = resolveTableNames(connection);
                upgradeResultProjection(connection, tableNames);
                backfillRankSummary(connection, tableNames);
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

    /**
     * Adds the rank-ladder stage columns. A tier is stored twice: by name for readability and by
     * ordinal so the leaderboard can be ordered by an index rather than by alphabetised tier names,
     * which would rank Adept above Celestial.
     *
     * <p>Every column carries a default matching {@code RankProfile.initial()}, so an existing
     * database upgrades without a backfill pass: rows simply start at Novice 1.
     */
    private static void upgradeRankLadder(Connection connection, Map<String, String> tables)
            throws SQLException {
        ensureColumn(
                connection, tables, "player_rank_summary", "tier", "VARCHAR(16) NOT NULL DEFAULT 'NOVICE'");
        ensureColumn(
                connection, tables, "player_rank_summary", "tier_ordinal", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, tables, "player_rank_summary", "tier_level", "INT NOT NULL DEFAULT 1");
        ensureColumn(
                connection, tables, "player_rank_summary", "stage_points", "INT NOT NULL DEFAULT 0");
        for (String place : List.of("first_places", "second_places", "third_places", "fourth_places")) {
            ensureColumn(connection, tables, "player_rank_summary", place, "BIGINT NOT NULL DEFAULT 0");
        }
        ensureIndex(
                connection,
                tables,
                "player_rank_summary",
                "idx_rank_summary_ladder",
                // Must list every ORDER BY key in order. Omitting total_score made the index diverge
                // from the leaderboard ordering at its fourth key, so no planner could ever use it.
                //
                // Declared ascending on purpose. The leaderboard orders every key descending, which a
                // plain ascending index serves by scanning backwards on every supported engine. Asking
                // for a descending index would instead depend on real descending-index support, and
                // MySQL before 8.0 and MariaDB parse the keyword but ignore it.
                "rule_id, rank_system, tier_ordinal, tier_level, stage_points, "
                        + "total_score, player_id");
    }

    private static void upgradeResultProjection(
            Connection connection, Map<String, String> tables) throws SQLException {
        ensureColumn(
                connection,
                tables,
                "player_result",
                "ranking_points_milli",
                "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(
                connection,
                tables,
                "rank_ledger",
                "ranking_points_milli",
                "BIGINT NOT NULL DEFAULT 0");
        upgradeRankLadder(connection, tables);
        ensureIndex(
                connection,
                tables,
                "player_result",
                "idx_player_result_player",
                "player_id, match_id");
        ensureIndex(
                connection,
                tables,
                "rank_ledger",
                "idx_rank_ledger_system_player",
                "rank_system, player_id, match_id");
        // History filters by player and orders by recency; the composite primary keys cannot serve
        // either access path because player_id is not their leading column.
        ensureIndex(
                connection, tables, "match_participant", "idx_match_participant_player", "player_id");
        ensureIndex(
                connection, tables, "match_instance", "idx_match_instance_updated", "updated_at");
        // Recovery and rule-pack reference scans filter on status, optionally scoped by rule.
        ensureIndex(connection, tables, "match_instance", "idx_match_instance_status", "status");
        ensureIndex(
                connection, tables, "match_instance", "idx_match_instance_rule", "rule_id, status");
        ensureIndex(
                connection,
                tables,
                "match_instance",
                "idx_match_instance_table_status",
                "table_id, status");
        // MySQL creates the foreign-key index implicitly, H2 does not; created_at has no index at
        // all even though the rank-system lookup orders by it.
        ensureIndex(connection, tables, "rank_ledger", "idx_rank_ledger_match", "match_id");
        ensureIndex(connection, tables, "rank_ledger", "idx_rank_ledger_created", "created_at");
        ensureIndex(
                connection,
                tables,
                "player_rank_summary",
                "idx_rank_summary_board",
                "rule_id, rank_system, ranking_points_milli DESC, total_score DESC, player_id");
    }

    /**
     * Fills the leaderboard projection once when upgrading a database that already has ledger rows.
     * Later matches maintain it incrementally inside the terminal-result transaction.
     */
    private static void backfillRankSummary(Connection connection, Map<String, String> tables)
            throws SQLException {
        String summary = requireTable(tables, "player_rank_summary");
        try (PreparedStatement probe =
                        connection.prepareStatement("SELECT 1 FROM " + summary);
                ResultSet rows = probe.executeQuery()) {
            if (rows.next()) {
                return;
            }
        }
        String sql = "INSERT INTO " + summary + " (rule_id, rank_system, player_id, "
                + "ranking_points_milli, total_score, match_count, updated_at) "
                + "SELECT m.rule_id, l.rank_system, l.player_id, "
                + "SUM(l.ranking_points_milli), SUM(r.score), COUNT(*), MAX(l.created_at) "
                + "FROM rank_ledger l JOIN match_instance m ON m.match_id = l.match_id "
                + "JOIN player_result r ON r.match_id = l.match_id AND r.player_id = l.player_id "
                + "GROUP BY m.rule_id, l.rank_system, l.player_id";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void ensureColumn(
            Connection connection,
            Map<String, String> tables,
            String table,
            String column,
            String definition)
            throws SQLException {
        String actualTable = requireTable(tables, table);
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(
                connection.getCatalog(), null, actualTable, null)) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE " + actualTable + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static void ensureIndex(
            Connection connection,
            Map<String, String> tables,
            String table,
            String index,
            String columns)
            throws SQLException {
        String actualTable = requireTable(tables, table);
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(
                connection.getCatalog(), null, actualTable, false, false)) {
            while (indexes.next()) {
                if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE INDEX " + index + " ON " + actualTable + " (" + columns + ")");
        }
    }

    /** Lists the catalog once and keys every table by its lowercase name. */
    private static Map<String, String> resolveTableNames(Connection connection)
            throws SQLException {
        Map<String, String> resolved = new HashMap<>();
        collectTables(connection.getMetaData(), connection.getCatalog(), resolved);
        if (connection.getCatalog() != null) {
            collectTables(connection.getMetaData(), null, resolved);
        }
        return resolved;
    }

    private static void collectTables(
            DatabaseMetaData metadata, String catalog, Map<String, String> target)
            throws SQLException {
        try (ResultSet tables = metadata.getTables(catalog, null, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String candidate = tables.getString("TABLE_NAME");
                target.putIfAbsent(candidate.toLowerCase(java.util.Locale.ROOT), candidate);
            }
        }
    }

    private static String requireTable(Map<String, String> tables, String expected)
            throws SQLException {
        String resolved = tables.get(expected.toLowerCase(java.util.Locale.ROOT));
        if (resolved == null) {
            throw new SQLException("Missing table during schema migration: " + expected);
        }
        return resolved;
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
            if (existing < 1 || existing > SCHEMA_VERSION) {
                throw new SQLException(
                        "Database schema is incompatible with MahjongPaper 2.0: " + existing);
            }
            if (existing < SCHEMA_VERSION) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE mahjong_schema_version SET schema_version = ? WHERE component = ?")) {
                    update.setInt(1, SCHEMA_VERSION);
                    update.setString(2, "event-store");
                    update.executeUpdate();
                }
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
                "CREATE TABLE IF NOT EXISTS table_lobby ("
                        + "table_id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, "
                        + "rule_id VARCHAR(32) NOT NULL, profile_id VARCHAR(64) NOT NULL, "
                        + "configuration_payload "
                        + binary
                        + " NOT NULL, seat_count INT NOT NULL, revision BIGINT NOT NULL, "
                        + "phase VARCHAR(16) NOT NULL, created_at TIMESTAMP(6) NOT NULL, "
                        + "updated_at TIMESTAMP(6) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS table_lobby_seat ("
                        + "table_id VARCHAR(36) NOT NULL, seat_index INT NOT NULL, "
                        + "player_id VARCHAR(36), ready BOOLEAN NOT NULL, "
                        + "PRIMARY KEY (table_id, seat_index), "
                        + "FOREIGN KEY (table_id) REFERENCES table_lobby(table_id))",
                "CREATE TABLE IF NOT EXISTS table_lobby_spectator ("
                        + "table_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL, "
                        + "PRIMARY KEY (table_id, player_id), "
                        + "FOREIGN KEY (table_id) REFERENCES table_lobby(table_id))",
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
                        + "ranking_points_milli BIGINT NOT NULL DEFAULT 0, "
                        + "result_payload "
                        + binary
                        + " NOT NULL, PRIMARY KEY (match_id, player_id), "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))",
                "CREATE TABLE IF NOT EXISTS rank_ledger ("
                        + "ledger_id VARCHAR(36) PRIMARY KEY, match_id VARCHAR(36) NOT NULL, "
                        + "player_id VARCHAR(36) NOT NULL, rank_system VARCHAR(64) NOT NULL, "
                        + "ranking_points_milli BIGINT NOT NULL DEFAULT 0, "
                        + "delta_payload "
                        + binary
                        + " NOT NULL, created_at TIMESTAMP(6) NOT NULL, "
                        + "FOREIGN KEY (match_id) REFERENCES match_instance(match_id))",
                // Incrementally maintained leaderboard projection. Ranking used to aggregate the
                // whole ledger on every request, which no index could accelerate.
                "CREATE TABLE IF NOT EXISTS player_rank_summary ("
                        + "rule_id VARCHAR(32) NOT NULL, rank_system VARCHAR(64) NOT NULL, "
                        + "player_id VARCHAR(36) NOT NULL, "
                        + "ranking_points_milli BIGINT NOT NULL DEFAULT 0, "
                        + "total_score BIGINT NOT NULL DEFAULT 0, "
                        + "match_count BIGINT NOT NULL DEFAULT 0, "
                        + "tier VARCHAR(16) NOT NULL DEFAULT 'NOVICE', "
                        + "tier_ordinal INT NOT NULL DEFAULT 0, "
                        + "tier_level INT NOT NULL DEFAULT 1, "
                        + "stage_points INT NOT NULL DEFAULT 0, "
                        + "first_places BIGINT NOT NULL DEFAULT 0, "
                        + "second_places BIGINT NOT NULL DEFAULT 0, "
                        + "third_places BIGINT NOT NULL DEFAULT 0, "
                        + "fourth_places BIGINT NOT NULL DEFAULT 0, "
                        + "updated_at TIMESTAMP(6) NOT NULL, "
                        + "PRIMARY KEY (rule_id, rank_system, player_id))");
    }
}
