package top.ellan.mahjong.persistence.sql.history;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.application.history.PlayerMatchHistoryEntry;
import top.ellan.mahjong.application.history.PlayerMatchOutcome;
import top.ellan.mahjong.application.history.PlayerRankingEntry;
import top.ellan.mahjong.application.history.PlayerRankingPage;
import top.ellan.mahjong.application.history.PlayerRecordQueryPort;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Indexed, bounded SQL projections for player-facing history and leaderboards. */
public final class JdbcPlayerRecordQuery implements PlayerRecordQueryPort {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 100_000;

    private final SqlConnectionFactory connections;

    public JdbcPlayerRecordQuery(SqlConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public List<PlayerMatchHistoryEntry> history(PlayerId playerId, int offset, int limit)
            throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        requireBounds(offset, limit);
        String sql = "SELECT m.match_id, m.rule_id, m.profile_id, m.rule_version, "
                + "m.status, m.updated_at, p.seat_id, r.placement, r.score, "
                + "r.ranking_points_milli FROM match_participant p "
                + "JOIN match_instance m ON m.match_id = p.match_id "
                + "LEFT JOIN player_result r ON r.match_id = p.match_id "
                + "AND r.player_id = p.player_id WHERE p.player_id = ? "
                + "AND p.seat_id IS NOT NULL ORDER BY m.updated_at DESC, m.match_id DESC "
                + "LIMIT ? OFFSET ?";
        List<PlayerMatchHistoryEntry> entries = new ArrayList<>(limit);
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Integer placement = (Integer) rows.getObject("placement");
                    Optional<PlayerMatchOutcome> outcome = placement == null
                            ? Optional.empty()
                            : Optional.of(new PlayerMatchOutcome(
                                    placement,
                                    rows.getLong("score"),
                                    rows.getLong("ranking_points_milli")));
                    entries.add(new PlayerMatchHistoryEntry(
                            MatchId.parse(rows.getString("match_id")),
                            new RuleId(rows.getString("rule_id")),
                            new ProfileId(rows.getString("profile_id")),
                            rows.getString("rule_version"),
                            TableLifecycle.valueOf(rows.getString("status")),
                            rows.getTimestamp("updated_at").toInstant(),
                            new SeatId(Integer.parseInt(rows.getString("seat_id"))),
                            outcome));
                }
            }
        }
        return List.copyOf(entries);
    }

    @Override
    public PlayerRankingPage ranking(
            PlayerId requestingPlayer, RuleId ruleId, int page, int pageSize)
            throws SQLException {
        Objects.requireNonNull(requestingPlayer, "requestingPlayer");
        Objects.requireNonNull(ruleId, "ruleId");
        if (page < 1 || page > MAX_PAGE || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid ranking page bounds");
        }
        Optional<String> rankSystem = latestRankSystem(ruleId);
        if (rankSystem.isEmpty()) {
            return new PlayerRankingPage(
                    ruleId, Optional.empty(), page, pageSize, List.of(), Optional.empty(), false);
        }
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<PlayerRankingEntry> fetched = rankingRange(
                ruleId, rankSystem.orElseThrow(), offset + 1L, offset + pageSize + 1L);
        boolean hasNext = fetched.size() > pageSize;
        List<PlayerRankingEntry> entries = hasNext
                ? List.copyOf(fetched.subList(0, pageSize))
                : fetched;
        Optional<PlayerRankingEntry> own = rankingForPlayer(
                ruleId, rankSystem.orElseThrow(), requestingPlayer);
        return new PlayerRankingPage(
                ruleId, rankSystem, page, pageSize, entries, own, hasNext);
    }

    private Optional<String> latestRankSystem(RuleId ruleId) throws SQLException {
        String sql = "SELECT l.rank_system FROM rank_ledger l JOIN match_instance m "
                + "ON m.match_id = l.match_id WHERE m.rule_id = ? "
                + "ORDER BY l.created_at DESC, l.rank_system";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ruleId.value());
            statement.setMaxRows(1);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(rows.getString("rank_system"))
                        : Optional.empty();
            }
        }
    }

    private List<PlayerRankingEntry> rankingRange(
            RuleId ruleId, String rankSystem, long first, long last) throws SQLException {
        String sql = rankedQuery() + " WHERE rank_position BETWEEN ? AND ? ORDER BY rank_position";
        List<PlayerRankingEntry> entries = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRankingScope(statement, ruleId, rankSystem);
            statement.setLong(3, first);
            statement.setLong(4, last);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(readRanking(rows));
                }
            }
        }
        return List.copyOf(entries);
    }

    private Optional<PlayerRankingEntry> rankingForPlayer(
            RuleId ruleId, String rankSystem, PlayerId playerId) throws SQLException {
        String sql = rankedQuery() + " WHERE player_id = ?";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRankingScope(statement, ruleId, rankSystem);
            statement.setString(3, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readRanking(rows)) : Optional.empty();
            }
        }
    }

    private static String rankedQuery() {
        return "SELECT rank_position, player_id, ranking_points_milli, total_score, "
                + "match_count FROM (SELECT ROW_NUMBER() OVER (ORDER BY "
                + "SUM(l.ranking_points_milli) DESC, SUM(r.score) DESC, l.player_id) "
                + "AS rank_position, l.player_id, SUM(l.ranking_points_milli) "
                + "AS ranking_points_milli, SUM(r.score) AS total_score, COUNT(*) "
                + "AS match_count FROM rank_ledger l JOIN match_instance m "
                + "ON m.match_id = l.match_id JOIN player_result r ON r.match_id = l.match_id "
                + "AND r.player_id = l.player_id WHERE m.rule_id = ? AND l.rank_system = ? "
                + "GROUP BY l.player_id) ranked";
    }

    private static void bindRankingScope(
            PreparedStatement statement, RuleId ruleId, String rankSystem) throws SQLException {
        statement.setString(1, ruleId.value());
        statement.setString(2, rankSystem);
    }

    private static PlayerRankingEntry readRanking(ResultSet row) throws SQLException {
        return new PlayerRankingEntry(
                row.getLong("rank_position"),
                PlayerId.parse(row.getString("player_id")),
                row.getLong("ranking_points_milli"),
                row.getLong("total_score"),
                row.getLong("match_count"));
    }

    private static void requireBounds(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid history query bounds");
        }
    }
}
