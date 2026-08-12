package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure transition result. Rejections must return the unchanged state and no events.
 *
 * @param nextState immutable rule state after applying the action
 * @param disposition whether the action was accepted, rejected, or completed the match
 * @param events canonical events emitted by the accepted transition
 * @param presentationCues transient client presentation cues emitted by the transition
 * @param reasonCode stable machine-readable transition reason
 */
public record RuleTransition(
        RuleState nextState,
        TransitionDisposition disposition,
        List<RuleEvent> events,
        List<RulePresentationCue> presentationCues,
        String reasonCode) {
    private static final int MAX_EVENTS = 64;
    private static final int MAX_PRESENTATION_CUES = 16;
    private static final int MAX_EVENT_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final Pattern REASON = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    public RuleTransition {
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(presentationCues, "presentationCues");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        if (!REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("Invalid transition reason code");
        }
        if (presentationCues.size() > MAX_PRESENTATION_CUES) {
            throw new IllegalArgumentException("A transition emitted too many presentation cues");
        }
        if (events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("A transition emitted too many events");
        }
        long payloadBytes = events.stream().mapToLong(RuleEvent::canonicalPayloadSize).sum();
        if (payloadBytes > MAX_EVENT_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Transition event payloads exceed 8 MiB");
        }
        events = List.copyOf(events);
        presentationCues = List.copyOf(presentationCues);
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
