package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;

/** Pure transition result. Rejections must return the unchanged state and no events. */
public record RuleTransition(
        RuleState nextState,
        TransitionDisposition disposition,
        List<RuleEvent> events,
        String reasonCode) {
    public RuleTransition {
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(disposition, "disposition");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        if (disposition == TransitionDisposition.REJECTED && !events.isEmpty()) {
            throw new IllegalArgumentException("Rejected transitions cannot emit events");
        }
    }

    public boolean accepted() {
        return disposition != TransitionDisposition.REJECTED;
    }

    public static RuleTransition rejected(RuleState unchangedState, String reasonCode) {
        return new RuleTransition(
                unchangedState, TransitionDisposition.REJECTED, List.of(), reasonCode);
    }
}
