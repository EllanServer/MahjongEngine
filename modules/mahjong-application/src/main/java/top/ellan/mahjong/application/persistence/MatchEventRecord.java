package top.ellan.mahjong.application.persistence;

import java.time.Instant;
import java.util.Objects;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;

/** One idempotent event-log row. */
public record MatchEventRecord(
        MatchId matchId,
        long sequence,
        long stateRevision,
        Instant acceptedAt,
        PlayerId actor,
        RuleAction action,
        RuleEvent event,
        String beforeStateSha256,
        String afterStateSha256) {
    public MatchEventRecord {
        Objects.requireNonNull(matchId, "matchId");
        if (sequence < 1 || stateRevision < 1) {
            throw new IllegalArgumentException("Event sequence and state revision must be positive");
        }
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(event, "event");
        requireSha256(beforeStateSha256, "beforeStateSha256");
        requireSha256(afterStateSha256, "afterStateSha256");
    }

    private static void requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
    }
}
