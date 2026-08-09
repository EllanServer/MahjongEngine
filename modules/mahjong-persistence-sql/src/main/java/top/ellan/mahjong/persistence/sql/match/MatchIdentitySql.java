package top.ellan.mahjong.persistence.sql.match;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/** Match identity row mapping shared by creation, lookup and recovery queries. */
final class MatchIdentitySql {
    private MatchIdentitySql() {}

    static Optional<MatchInstanceRecord> find(Connection connection, MatchId matchId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT * FROM match_instance WHERE match_id = ?")) {
            statement.setString(1, matchId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(read(result))
                        : Optional.empty();
            }
        }
    }

    static MatchInstanceRecord read(ResultSet result) throws SQLException {
        RulePackRef reference =
                new RulePackRef(
                        new RuleId(result.getString("rule_id")),
                        result.getString("rule_version"),
                        result.getString("rule_jar_sha256"),
                        result.getInt("state_schema_version"));
        MatchBinding binding =
                new MatchBinding(
                        MatchId.parse(result.getString("match_id")),
                        reference,
                        new ProfileId(result.getString("profile_id")),
                        result.getString("configuration_sha256"),
                        result.getTimestamp("created_at").toInstant());
        return new MatchInstanceRecord(
                binding,
                TableId.parse(result.getString("table_id")),
                TableLifecycle.valueOf(result.getString("status")),
                result.getTimestamp("updated_at").toInstant(),
                result.getLong("last_committed_sequence"));
    }

    static void insert(Connection connection, MatchInstanceRecord match)
            throws SQLException {
        String sql =
                "INSERT INTO match_instance (match_id, table_id, rule_id, rule_version, "
                        + "rule_jar_sha256, state_schema_version, profile_id, "
                        + "configuration_sha256, status, created_at, updated_at, "
                        + "last_committed_sequence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            MatchBinding binding = match.binding();
            statement.setString(1, binding.matchId().toString());
            statement.setString(2, match.tableId().toString());
            statement.setString(3, binding.rulePack().ruleId().value());
            statement.setString(4, binding.rulePack().version());
            statement.setString(5, binding.rulePack().jarSha256());
            statement.setInt(6, binding.rulePack().stateSchemaVersion());
            statement.setString(7, binding.profile().value());
            statement.setString(8, binding.configurationSha256());
            statement.setString(9, match.status().name());
            statement.setTimestamp(10, Timestamp.from(binding.createdAt()));
            statement.setTimestamp(11, Timestamp.from(match.updatedAt()));
            statement.setLong(12, match.lastCommittedSequence());
            statement.executeUpdate();
        }
    }

    static boolean sameIdentity(
            MatchInstanceRecord left,
            MatchInstanceRecord right) {
        return left.binding().equals(right.binding())
                && left.tableId().equals(right.tableId());
    }
}
