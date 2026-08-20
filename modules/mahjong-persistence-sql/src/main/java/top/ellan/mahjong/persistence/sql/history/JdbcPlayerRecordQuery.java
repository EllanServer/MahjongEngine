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
import top.ellan.mahjong.domain.match.RankProfile;
import top.ellan.mahjong.domain.match.RankTier;
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
    private static final String RANK_COLUMNS =
            "SELECT player_id, ranking_points_milli, total_score, match_count, "
                    + "tier, tier_level, stage_points, "
                    + "first_places, second_places, third_places, fourth_places "
                    + "FROM player_rank_summary";
    private static final String RANK_PAGE_COLUMNS =
            "SELECT s.player_id, s.ranking_points_milli, s.total_score, s.match_count, "
                    + "s.tier, s.tier_level, s.stage_points, "
                    + "s.first_places, s.second_places, s.third_places, s.fourth_places ";
    // Ordered by ladder standing, matching idx_rank_summary_ladder. Ordering by tier name would put
    // Adept above Celestial, so the stored ordinal is what ranks the tiers.
    //
    // Every key descends, including the player_id tiebreak. A mixed-direction ORDER BY can only be
    // served by an index whose directions correspond exactly, which needs real descending-index
    // support; MySQL before 8.0 and MariaDB parse DESC in an index definition and ignore it. A
    // uniformly descending order can instead be served by scanning a plain ascending index backwards,
    // which every supported engine does.
    private static final String RANK_ORDER =
            " ORDER BY tier_ordinal DESC, tier_level DESC, stage_points DESC, "
                    + "total_score DESC, player_id DESC";
    private static final String RANK_PAGE_ORDER =
            " ORDER BY p.tier_ordinal DESC, p.tier_level DESC, p.stage_points DESC, "
                    + "p.total_score DESC, p.player_id DESC";

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
        // One extra row proves whether a next page exists without a second count query.
        List<PlayerRankingEntry> fetched = rankingRange(
                ruleId, rankSystem.orElseThrow(), offset + 1L, pageSize + 1L);
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
        String sql = "SELECT rank_system FROM player_rank_summary WHERE rule_id = ? "
                + "ORDER BY updated_at DESC, rank_system";
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

    /**
     * Reads one leaderboard window without making the ladder index duplicate every display column.
     *
     * <p>The inner query is covered by the narrow ordered index and selects at most fifty-one ids
     * (one extra detects the next page). The outer primary-key lookup fetches their wide display rows
     * and sorts only that bounded page. A
     * direct wide SELECT made H2 choose the primary key and sort the entire rank-system partition;
     * making the ladder index cover every statistic would instead add hundreds of bytes per player.
     */
    private List<PlayerRankingEntry> rankingRange(
            RuleId ruleId, String rankSystem, long first, long count) throws SQLException {
        String sql = RANK_PAGE_COLUMNS
                + "FROM player_rank_summary s JOIN ("
                + "SELECT rule_id, rank_system, player_id, tier_ordinal, tier_level, "
                + "stage_points, total_score FROM player_rank_summary "
                + "WHERE rule_id = ? AND rank_system = ?"
                + RANK_ORDER
                + " LIMIT ? OFFSET ?) p "
                + "ON s.rule_id = p.rule_id AND s.rank_system = p.rank_system "
                + "AND s.player_id = p.player_id"
                + RANK_PAGE_ORDER;
        List<PlayerRankingEntry> entries = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRankingScope(statement, ruleId, rankSystem);
            statement.setLong(3, count);
            statement.setLong(4, first - 1);
            long position = first;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(readRanking(rows, position++));
                }
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Resolves one player's own row plus its rank. The rank is counted with an aggregate over the
     * same ordered index instead of materialising the full leaderboard.
     */
    private Optional<PlayerRankingEntry> rankingForPlayer(
            RuleId ruleId, String rankSystem, PlayerId playerId) throws SQLException {
        String sql = RANK_COLUMNS + " WHERE rule_id = ? AND rank_system = ? AND player_id = ?";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRankingScope(statement, ruleId, rankSystem);
            statement.setString(3, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                long points = rows.getLong("ranking_points_milli");
                long score = rows.getLong("total_score");
                long matches = rows.getLong("match_count");
                RankProfile profile = readProfile(rows);
                long position = rankPosition(
                        connection, ruleId, rankSystem, playerId, profile, score);
                return Optional.of(new PlayerRankingEntry(
                        position, playerId, points, score, matches, profile));
            }
        }
    }

    /** Counts the rows the ladder order puts ahead of this one, matching {@code RANK_ORDER}. */
    private static long rankPosition(
            Connection connection,
            RuleId ruleId,
            String rankSystem,
            PlayerId playerId,
            RankProfile profile,
            long score)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM player_rank_summary WHERE rule_id = ? "
                + "AND rank_system = ? AND (tier_ordinal > ? "
                + "OR (tier_ordinal = ? AND tier_level > ?) "
                + "OR (tier_ordinal = ? AND tier_level = ? AND stage_points > ?) "
                + "OR (tier_ordinal = ? AND tier_level = ? AND stage_points = ? "
                + "AND total_score > ?) "
                + "OR (tier_ordinal = ? AND tier_level = ? AND stage_points = ? "
                + "AND total_score = ? AND player_id > ?))";
        int tier = profile.tier().ordinal();
        int level = profile.level();
        int stagePoints = profile.points();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ruleId.value());
            statement.setString(2, rankSystem);
            statement.setInt(3, tier);
            statement.setInt(4, tier);
            statement.setInt(5, level);
            statement.setInt(6, tier);
            statement.setInt(7, level);
            statement.setInt(8, stagePoints);
            statement.setInt(9, tier);
            statement.setInt(10, level);
            statement.setInt(11, stagePoints);
            statement.setLong(12, score);
            statement.setInt(13, tier);
            statement.setInt(14, level);
            statement.setInt(15, stagePoints);
            statement.setLong(16, score);
            statement.setString(17, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) + 1L : 1L;
            }
        }
    }

    private static void bindRankingScope(
            PreparedStatement statement, RuleId ruleId, String rankSystem) throws SQLException {
        statement.setString(1, ruleId.value());
        statement.setString(2, rankSystem);
    }

    private static PlayerRankingEntry readRanking(ResultSet row, long position)
            throws SQLException {
        return new PlayerRankingEntry(
                position,
                PlayerId.parse(row.getString("player_id")),
                row.getLong("ranking_points_milli"),
                row.getLong("total_score"),
                row.getLong("match_count"),
                readProfile(row));
    }

    /** Rows written before the ladder existed carry column defaults, reading back as Novice 1. */
    private static RankProfile readProfile(ResultSet row) throws SQLException {
        return new RankProfile(
                RankTier.valueOf(row.getString("tier")),
                Math.max(1, row.getInt("tier_level")),
                Math.max(0, row.getInt("stage_points")),
                count(row.getLong("match_count")),
                count(row.getLong("first_places")),
                count(row.getLong("second_places")),
                count(row.getLong("third_places")),
                count(row.getLong("fourth_places")));
    }

    private static int count(long value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private static void requireBounds(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid history query bounds");
        }
    }
}
