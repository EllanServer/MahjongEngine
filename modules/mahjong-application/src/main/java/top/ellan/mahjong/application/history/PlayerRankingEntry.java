package top.ellan.mahjong.application.history;

import java.util.Objects;
import top.ellan.mahjong.domain.match.RankProfile;
import top.ellan.mahjong.spi.PlayerId;

/** Aggregated rank-ledger row with a deterministic global position and ladder standing. */
public record PlayerRankingEntry(
        long position,
        PlayerId playerId,
        long rankingPointsMilli,
        long totalScore,
        long matchCount,
        RankProfile profile) {
    public PlayerRankingEntry {
        if (position < 1 || matchCount < 1) {
            throw new IllegalArgumentException("Ranking position and match count must be positive");
        }
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(profile, "profile");
    }
}
