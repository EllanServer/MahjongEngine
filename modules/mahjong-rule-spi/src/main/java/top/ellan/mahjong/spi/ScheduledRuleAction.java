package top.ellan.mahjong.spi;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic one-shot action requested by a rule pack for its current immutable state.
 *
 * <p>The actor and action are persisted exactly like a player action, so crash replay does not
 * depend on wall-clock timing. The core binds the timer to a state revision and discards stale
 * callbacks after any successful transition.</p>
 */
public record ScheduledRuleAction(
        PlayerId actor, RuleAction action, Duration delay, String reasonCode) {
    private static final Pattern REASON = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Duration MAXIMUM_DELAY = Duration.ofHours(1);

    public ScheduledRuleAction {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(delay, "delay");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        if (delay.isNegative() || delay.compareTo(MAXIMUM_DELAY) > 0) {
            throw new IllegalArgumentException("Scheduled action delay is outside 0..1 hour");
        }
        if (!REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("Invalid scheduled action reason code");
        }
    }
}
