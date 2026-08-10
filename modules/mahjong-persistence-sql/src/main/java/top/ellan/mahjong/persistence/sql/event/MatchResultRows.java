package top.ellan.mahjong.persistence.sql.event;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.ellan.mahjong.application.persistence.SnapshotWrite;
import top.ellan.mahjong.persistence.sql.common.PersistenceConflictException;
import top.ellan.mahjong.spi.RuleMatchResult;
import top.ellan.mahjong.spi.RulePlayerResult;

/**
 * Terminal-result writer that loads every existing projection row in three set-based queries and
 * writes the missing ones as JDBC batches.
 *
 * <p>The previous implementation issued one seat check, one result select, one result insert, one
 * ledger select and one ledger insert per player, so a four-player match cost more than a dozen
 * single-row round trips inside the commit transaction. Idempotent retries keep the same semantics:
 * an already stored row must match byte for byte, and a partially stored result is completed rather
 * than duplicated.</p>
 */
final class MatchResultRows {
    private final Map<String, String> seats;
    private final Map<String, StoredPlayerResult> playerResults;
    private final Map<String, StoredRankLedger> rankLedgers;

    private MatchResultRows(
            Map<String, String> seats,
            Map<String, StoredPlayerResult> playerResults,
            Map<String, StoredRankLedger> rankLedgers) {
        this.seats = seats;
        this.playerResults = playerResults;
        this.rankLedgers = rankLedgers;
    }

    static MatchResultRows load(Connection connection, String matchId, RuleMatchResult result)
            throws SQLException {
        return new MatchResultRows(
                loadSeats(connection, matchId),
                loadPlayerResults(connection, matchId),
                loadRankLedgers(connection, matchId, result.rankSystem()));
    }

    void verifySeats(RuleMatchResult result) throws SQLException {
        for (RulePlayerResult player : result.players()) {
            String seat = seats.get(player.playerId().toString());
            if (seat == null || !Integer.toString(player.seatId().value()).equals(seat)) {
                throw new PersistenceConflictException(
                        "Terminal result player is not bound to the reported seat");
            }
        }
    }

    void insertPlayerResults(Connection connection, String matchId, RuleMatchResult result)
            throws SQLException {
        List<RulePlayerResult> missing = new ArrayList<>(result.players().size());
        for (RulePlayerResult player : result.players()) {
            StoredPlayerResult stored = playerResults.get(player.playerId().toString());
            if (stored == null) {
                missing.add(player);
            } else if (!stored.same(player)) {
                throw new PersistenceConflictException("Idempotent player result retry differs");
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO player_result (match_id, player_id, seat_index, "
                + "placement, score, ranking_points_milli, result_payload) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (RulePlayerResult player : missing) {
                insert.setString(1, matchId);
                insert.setString(2, player.playerId().toString());
                insert.setInt(3, player.seatId().value());
                insert.setInt(4, player.placement());
                insert.setLong(5, player.score());
                insert.setLong(6, player.rankingPointsMilli());
                insert.setBytes(7, player.canonicalPayload());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    void insertRankLedgers(
            Connection connection, SnapshotWrite snapshot, RuleMatchResult result)
            throws SQLException {
        String matchId = snapshot.matchId().toString();
        String rankSystem = result.rankSystem();
        List<RulePlayerResult> missing = new ArrayList<>(result.players().size());
        for (RulePlayerResult player : result.players()) {
            StoredRankLedger stored = rankLedgers.get(ledgerId(matchId, rankSystem, player));
            if (stored == null) {
                missing.add(player);
            } else if (!stored.same(matchId, rankSystem, player)) {
                throw new PersistenceConflictException("Idempotent rank ledger retry differs");
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO rank_ledger (ledger_id, match_id, player_id, "
                + "rank_system, ranking_points_milli, delta_payload, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (RulePlayerResult player : missing) {
                insert.setString(1, ledgerId(matchId, rankSystem, player));
                insert.setString(2, matchId);
                insert.setString(3, player.playerId().toString());
                insert.setString(4, rankSystem);
                insert.setLong(5, player.rankingPointsMilli());
                insert.setBytes(6, player.canonicalPayload());
                insert.setTimestamp(7, Timestamp.from(snapshot.createdAt()));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Accumulates the leaderboard projection in the same transaction as the ledger rows. Players
     * whose ledger row already existed are skipped so a retry never double counts.
     */
    void upsertRankSummary(Connection connection, SnapshotWrite snapshot, RuleMatchResult result)
            throws SQLException {
        String matchId = snapshot.matchId().toString();
        String rankSystem = result.rankSystem();
        String ruleId = loadRuleId(connection, matchId);
        for (RulePlayerResult player : result.players()) {
            if (rankLedgers.containsKey(ledgerId(matchId, rankSystem, player))) {
                continue;
            }
            String updateSql = "UPDATE player_rank_summary SET "
                    + "ranking_points_milli = ranking_points_milli + ?, "
                    + "total_score = total_score + ?, match_count = match_count + 1, "
                    + "updated_at = ? WHERE rule_id = ? AND rank_system = ? AND player_id = ?";
            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                update.setLong(1, player.rankingPointsMilli());
                update.setLong(2, player.score());
                update.setTimestamp(3, Timestamp.from(snapshot.createdAt()));
                update.setString(4, ruleId);
                update.setString(5, rankSystem);
                update.setString(6, player.playerId().toString());
                if (update.executeUpdate() > 0) {
                    continue;
                }
            }
            String insertSql = "INSERT INTO player_rank_summary (rule_id, rank_system, "
                    + "player_id, ranking_points_milli, total_score, match_count, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, 1, ?)";
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setString(1, ruleId);
                insert.setString(2, rankSystem);
                insert.setString(3, player.playerId().toString());
                insert.setLong(4, player.rankingPointsMilli());
                insert.setLong(5, player.score());
                insert.setTimestamp(6, Timestamp.from(snapshot.createdAt()));
                insert.executeUpdate();
            }
        }
    }

    private static String loadRuleId(Connection connection, String matchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rule_id FROM match_instance WHERE match_id = ?")) {
            statement.setString(1, matchId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new PersistenceConflictException(
                            "Terminal result references an unknown match");
                }
                return row.getString("rule_id");
            }
        }
    }

    private static Map<String, String> loadSeats(Connection connection, String matchId)
            throws SQLException {
        Map<String, String> seats = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id, seat_id FROM match_participant WHERE match_id = ?")) {
            statement.setString(1, matchId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    seats.put(rows.getString("player_id"), rows.getString("seat_id"));
                }
            }
        }
        return seats;
    }

    private static Map<String, StoredPlayerResult> loadPlayerResults(
            Connection connection, String matchId) throws SQLException {
        Map<String, StoredPlayerResult> results = new HashMap<>();
        String sql = "SELECT player_id, seat_index, placement, score, ranking_points_milli, "
                + "result_payload FROM player_result WHERE match_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, matchId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.put(
                            rows.getString("player_id"),
                            new StoredPlayerResult(
                                    rows.getInt("seat_index"),
                                    rows.getInt("placement"),
                                    rows.getLong("score"),
                                    rows.getLong("ranking_points_milli"),
                                    rows.getBytes("result_payload")));
                }
            }
        }
        return results;
    }

    private static Map<String, StoredRankLedger> loadRankLedgers(
            Connection connection, String matchId, String rankSystem) throws SQLException {
        Map<String, StoredRankLedger> ledgers = new HashMap<>();
        String sql = "SELECT ledger_id, match_id, player_id, rank_system, "
                + "ranking_points_milli, delta_payload FROM rank_ledger "
                + "WHERE match_id = ? AND rank_system = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, matchId);
            statement.setString(2, rankSystem);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ledgers.put(
                            rows.getString("ledger_id"),
                            new StoredRankLedger(
                                    rows.getString("match_id"),
                                    rows.getString("player_id"),
                                    rows.getString("rank_system"),
                                    rows.getLong("ranking_points_milli"),
                                    rows.getBytes("delta_payload")));
                }
            }
        }
        return ledgers;
    }

    private static String ledgerId(String matchId, String rankSystem, RulePlayerResult player) {
        return UUID.nameUUIDFromBytes(
                        ("mahjong-rank-ledger|"
                                        + matchId
                                        + "|"
                                        + rankSystem
                                        + "|"
                                        + player.playerId())
                                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private record StoredPlayerResult(
            int seatIndex, int placement, long score, long rankingPointsMilli, byte[] payload) {
        private boolean same(RulePlayerResult player) {
            return seatIndex == player.seatId().value()
                    && placement == player.placement()
                    && score == player.score()
                    && rankingPointsMilli == player.rankingPointsMilli()
                    && Arrays.equals(payload, player.canonicalPayload());
        }
    }

    private record StoredRankLedger(
            String matchId,
            String playerId,
            String rankSystem,
            long rankingPointsMilli,
            byte[] payload) {
        private boolean same(String expectedMatchId, String expectedSystem, RulePlayerResult player) {
            return matchId.equals(expectedMatchId)
                    && playerId.equals(player.playerId().toString())
                    && rankSystem.equals(expectedSystem)
                    && rankingPointsMilli == player.rankingPointsMilli()
                    && Arrays.equals(payload, player.canonicalPayload());
        }
    }
}
