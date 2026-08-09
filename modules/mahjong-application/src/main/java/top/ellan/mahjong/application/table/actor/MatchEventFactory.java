package top.ellan.mahjong.application.table.actor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.application.persistence.MatchEventRecord;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;

/** Creates one normalized, contiguous event-log batch for an accepted action. */
final class MatchEventFactory {
    private final Clock clock;

    MatchEventFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    List<MatchEventRecord> create(
            MatchBinding binding,
            PlayerId actor,
            RuleAction action,
            List<RuleEvent> events,
            String beforeHash,
            String afterHash,
            long stateRevision,
            long startingSequence) {
        List<MatchEventRecord> records = new ArrayList<>(events.size());
        Instant acceptedAt = Instant.now(clock);
        long sequence = startingSequence;
        for (RuleEvent event : events) {
            records.add(
                    new MatchEventRecord(
                            binding.matchId(),
                            ++sequence,
                            stateRevision,
                            acceptedAt,
                            actor,
                            action,
                            event,
                            beforeHash,
                            afterHash));
        }
        return List.copyOf(records);
    }
}
