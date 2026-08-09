package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Optional;

/**
 * Pure rule-pack boundary. Implementations may not access platform APIs, files, network, wall clocks,
 * or create threads. The core serializes calls per match and may call different matches concurrently.
 */
public interface RulePackProvider {
    RulePackDescriptor descriptor();

    RuleState createMatch(MatchSetup setup);

    RuleTransition transition(RuleState state, PlayerId actor, RuleAction action);

    List<LegalAction> legalActions(RuleState state, PlayerId actor);

    /**
     * Returns at most one deterministic action to run if this exact state revision remains current.
     *
     * <p>The returned actor must be seated and {@link #transition} must accept the returned action
     * when invoked with the same state. Player-visible actions remain in {@link #legalActions}; this
     * hook is also allowed to expose trusted actor-owned commands that must never be client tokens.</p>
     */
    default Optional<ScheduledRuleAction> scheduledAction(RuleState state) {
        return Optional.empty();
    }

    /**
     * Selects at most one deterministic player action for rule-aware bot or trustee control.
     *
     * <p>Candidates are ordered by physical seat and contain the exact legal actions already
     * computed by the core. A returned actor must be present in {@code candidates}, and its action
     * must equal one of that actor's supplied legal actions. Implementations must return an empty
     * optional for an empty candidate list. As with every provider call, this method must be pure,
     * must not read a wall clock, and must not create work outside the calling thread.</p>
     */
    default Optional<ScheduledRuleAction> automatedAction(
            RuleState state, List<AutomatedPlayerActions> candidates) {
        return Optional.empty();
    }

    /**
     * Returns the complete terminal result after a transition reports {@code MATCH_ENDED}.
     *
     * <p>The result is persisted atomically with the terminal snapshot. Implementations must
     * return an empty optional for every non-terminal state and must derive the result only from
     * the supplied immutable state.</p>
     */
    default Optional<RuleMatchResult> matchResult(RuleState state) {
        return Optional.empty();
    }

    PublicRuleView publicView(RuleState state, long revision);

    PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision);

    /** Canonical SHA-256 of state. Providers should override this when hashing can avoid full encoding. */
    default String stateHash(RuleState state) {
        return snapshot(state, 0).sha256();
    }

    RuleStateSnapshot snapshot(RuleState state, long sequence);

    RuleState restore(RuleStateSnapshot snapshot);
}
