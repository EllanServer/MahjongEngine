package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic, public opening metadata for one hand. Rules own the dice and wall break; the
 * platform may animate the declared rolls without learning a rule implementation.
 *
 * @param handSequence zero-based sequence of the hand within the match
 * @param rolls immutable physical dice rolls declared by the rule pack
 * @param openDoorSeat seat at whose wall the opening begins
 * @param breakStackOffset one-based stack offset at which the wall is opened
 */
public record RuleOpeningPresentation(
        long handSequence,
        List<RuleDiceRoll> rolls,
        SeatId openDoorSeat,
        int breakStackOffset) {
    /**
     * Creates validated public opening metadata for one hand.
     *
     * @param handSequence zero-based sequence of the hand within the match
     * @param rolls immutable physical dice rolls declared by the rule pack
     * @param openDoorSeat seat at whose wall the opening begins
     * @param breakStackOffset one-based stack offset at which the wall is opened
     */
    public RuleOpeningPresentation {
        if (handSequence < 0) {
            throw new IllegalArgumentException("handSequence must be non-negative");
        }
        rolls = List.copyOf(Objects.requireNonNull(rolls, "rolls"));
        if (rolls.isEmpty() || rolls.size() > 2) {
            throw new IllegalArgumentException("An opening requires one or two dice rolls");
        }
        rolls.forEach(roll -> Objects.requireNonNull(roll, "roll"));
        Objects.requireNonNull(openDoorSeat, "openDoorSeat");
        if (breakStackOffset < 1) {
            throw new IllegalArgumentException("breakStackOffset must be positive");
        }
    }
}
