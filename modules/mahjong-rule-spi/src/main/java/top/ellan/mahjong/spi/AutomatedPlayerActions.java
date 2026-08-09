package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;

/**
 * Seat-ordered legal actions for one player currently controlled by a bot or trustee.
 *
 * <p>The core computes legal actions once per immutable rule state and passes the same values to
 * the rule pack. This keeps automation rule-aware without duplicating hot-path analysis or
 * teaching the core concrete action names.</p>
 */
public record AutomatedPlayerActions(PlayerId actor, List<LegalAction> legalActions) {
    public AutomatedPlayerActions {
        Objects.requireNonNull(actor, "actor");
        legalActions = List.copyOf(Objects.requireNonNull(legalActions, "legalActions"));
        if (legalActions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Automation legal actions cannot contain null");
        }
    }
}
