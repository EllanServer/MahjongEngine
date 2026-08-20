package top.ellan.mahjong.application.feedback;

import java.util.Objects;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Transient notice that one seat is about to be played automatically.
 *
 * <p>1.5.0 counted the remaining seconds down on the action bar so nobody was ever auto-played
 * without warning. The core only states the fact; how it is shown is a platform concern.
 */
public record HumanDecisionWarning(PlayerId actor, boolean discardTurn, int remainingSeconds) {
    public HumanDecisionWarning {
        Objects.requireNonNull(actor, "actor");
        if (remainingSeconds < 1) {
            throw new IllegalArgumentException("a warning must precede its deadline");
        }
    }
}
