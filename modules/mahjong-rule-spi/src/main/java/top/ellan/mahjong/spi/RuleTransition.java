package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;

/** Pure transition result. Rejections must return the unchanged state and no events. */
public record RuleTransition(
        RuleState nextState,
        TransitionDisposition disposition,
        List<RuleEvent> events,
        List<RulePresentationCue> presentationCues,
        String reasonCode) {
    private static final int MAX_PRESENTATION_CUES = 16;

    public RuleTransition {
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(disposition, "disposition");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        presentationCues = List.copyOf(
                Objects.requireNonNull(presentationCues, "presentationCues"));
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        if (presentationCues.size() > MAX_PRESENTATION_CUES) {
            throw new IllegalArgumentException("A transition emitted too many presentation cues");
        }
        if (disposition == TransitionDisposition.REJECTED
                && (!events.isEmpty() || !presentationCues.isEmpty())) {
            throw new IllegalArgumentException(
                    "Rejected transitions cannot emit events or presentation cues");
        }
    }

    /** Source-compatible constructor for providers that do not emit transient feedback. */
    public RuleTransition(
            RuleState nextState,
            TransitionDisposition disposition,
            List<RuleEvent> events,
            String reasonCode) {
        this(nextState, disposition, events, List.of(), reasonCode);
    }

    public boolean accepted() {
        return disposition != TransitionDisposition.REJECTED;
    }

    public static RuleTransition rejected(RuleState unchangedState, String reasonCode) {
        return new RuleTransition(
                unchangedState,
                TransitionDisposition.REJECTED,
                List.of(),
                List.of(),
                reasonCode);
    }
}
