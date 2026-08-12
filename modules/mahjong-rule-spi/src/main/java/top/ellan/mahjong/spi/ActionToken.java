package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.UUID;

/**
 * Unforgeable, actor- and revision-bound authority to submit one legal action.
 *
 * @param value unique opaque token value
 * @param actor player authorized to submit the action
 * @param revision rule-state revision against which the token was issued
 */
public record ActionToken(UUID value, PlayerId actor, long revision) {
    /**
     * Creates a validated action authorization token.
     *
     * @param value unique opaque token value
     * @param actor player authorized to submit the action
     * @param revision rule-state revision against which the token was issued
     */
    public ActionToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(actor, "actor");
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
    }
}
