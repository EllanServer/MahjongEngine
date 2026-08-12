package top.ellan.mahjong.tck;

import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.spi.MatchSetup;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/**
 * Deterministic fixture plus actions that the provider guarantees are illegal initially.
 *
 * @param setup deterministic match setup supplied to the provider
 * @param rejectedActions player actions guaranteed to be illegal in the initial state
 */
public record RulePackTckCase(
        MatchSetup setup, Map<PlayerId, RuleAction> rejectedActions) {
    /**
     * Creates a validated provider verification fixture.
     *
     * @param setup deterministic match setup supplied to the provider
     * @param rejectedActions player actions guaranteed to be illegal in the initial state
     */
    public RulePackTckCase {
        Objects.requireNonNull(setup, "setup");
        rejectedActions = Map.copyOf(Objects.requireNonNull(rejectedActions, "rejectedActions"));
        if (rejectedActions.isEmpty()) {
            throw new IllegalArgumentException("At least one rejected action is required");
        }
        if (!setup.players().stream()
                .map(top.ellan.mahjong.spi.MatchPlayer::playerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                .containsAll(rejectedActions.keySet())) {
            throw new IllegalArgumentException("Rejected-action actors must belong to the match");
        }
    }
}
