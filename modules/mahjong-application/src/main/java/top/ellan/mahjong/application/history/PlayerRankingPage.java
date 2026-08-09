package top.ellan.mahjong.application.history;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.spi.RuleId;

/** One bounded leaderboard page plus the requesting player's own row. */
public record PlayerRankingPage(
        RuleId ruleId,
        Optional<String> rankSystem,
        int page,
        int pageSize,
        List<PlayerRankingEntry> entries,
        Optional<PlayerRankingEntry> ownEntry,
        boolean hasNext) {
    public PlayerRankingPage {
        Objects.requireNonNull(ruleId, "ruleId");
        rankSystem = Objects.requireNonNull(rankSystem, "rankSystem");
        if (page < 1 || pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("Invalid ranking page bounds");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        ownEntry = Objects.requireNonNull(ownEntry, "ownEntry");
    }
}
