package top.ellan.mahjong.persistence.sql;

import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;

/** One accepted action reconstructed from one or more canonical event rows. */
public record RecoveredAction(
        long stateRevision,
        long firstEventSequence,
        long lastEventSequence,
        PlayerId actor,
        RuleAction action,
        List<RuleEvent> expectedEvents,
        String beforeStateSha256,
        String afterStateSha256) {
    public RecoveredAction {
        if (stateRevision < 1 || firstEventSequence < 1 || lastEventSequence < firstEventSequence) {
            throw new IllegalArgumentException("Invalid recovered action sequence");
        }
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        expectedEvents = List.copyOf(Objects.requireNonNull(expectedEvents, "expectedEvents"));
        if (expectedEvents.isEmpty()) {
            throw new IllegalArgumentException("Recovered action requires events");
        }
        Objects.requireNonNull(beforeStateSha256, "beforeStateSha256");
        Objects.requireNonNull(afterStateSha256, "afterStateSha256");
    }
}
