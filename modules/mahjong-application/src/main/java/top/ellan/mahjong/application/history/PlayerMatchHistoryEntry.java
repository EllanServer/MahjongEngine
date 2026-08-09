package top.ellan.mahjong.application.history;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** One bounded history projection row; active matches intentionally have no outcome yet. */
public record PlayerMatchHistoryEntry(
        MatchId matchId,
        RuleId ruleId,
        ProfileId profileId,
        String ruleVersion,
        TableLifecycle lifecycle,
        Instant updatedAt,
        SeatId seatId,
        Optional<PlayerMatchOutcome> outcome) {
    public PlayerMatchHistoryEntry {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(profileId, "profileId");
        ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(seatId, "seatId");
        outcome = Objects.requireNonNull(outcome, "outcome");
    }
}
