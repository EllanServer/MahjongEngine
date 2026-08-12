package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Optional;

/**
 * Pure rule-pack boundary. Implementations may not access platform APIs, files, network, wall clocks,
 * or create threads. The core serializes calls per match and may call different matches concurrently.
 */
public interface RulePackProvider {
    /**
     * Returns immutable metadata used to validate and activate this provider.
     *
     * @return rule-pack descriptor
     */
    RulePackDescriptor descriptor();

    /**
     * Creates the deterministic initial state for a match.
     *
     * @param setup validated match setup supplied by the core
     * @return immutable initial rule state
     */
    RuleState createMatch(MatchSetup setup);

    /**
     * Applies one opaque action to immutable rule state.
     *
     * @param state current immutable rule state
     * @param actor player submitting the action
     * @param action opaque provider-defined action
     * @return pure transition result
     */
    RuleTransition transition(RuleState state, PlayerId actor, RuleAction action);

    /**
     * Computes the legal actions currently available to one player.
     *
     * @param state current immutable rule state
     * @param actor player for whom actions are requested
     * @return immutable legal-action candidates
     */
    List<LegalAction> legalActions(RuleState state, PlayerId actor);

    /**
     * Returns at most one deterministic action to run if this exact state revision remains current.
     *
     * <p>The returned actor must be seated and {@link #transition} must accept the returned action
     * when invoked with the same state. Player-visible actions remain in {@link #legalActions}; this
     * hook is also allowed to expose trusted actor-owned commands that must never be client tokens.</p>
     *
     * @param state current immutable rule state
     * @return deterministic scheduled action, when the state requests one
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
     *
     * @param state current immutable rule state
     * @param candidates seat-ordered legal actions for automated players
     * @return selected deterministic action, or empty when automation should wait
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
     *
     * @param state immutable rule state to inspect
     * @return terminal result for a completed match, otherwise empty
     */
    default Optional<RuleMatchResult> matchResult(RuleState state) {
        return Optional.empty();
    }

    /**
     * Projects information safe to publish to every table viewer.
     *
     * @param state current immutable rule state
     * @param revision revision assigned to that state by the core
     * @return bounded public rule view
     */
    PublicRuleView publicView(RuleState state, long revision);

    /**
     * Projects information authorized for exactly one player.
     *
     * @param state current immutable rule state
     * @param viewer player authorized to receive the view
     * @param revision revision assigned to that state by the core
     * @return bounded private rule view
     */
    PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision);

    /**
     * Computes the canonical SHA-256 of state.
     *
     * <p>Providers should override this when hashing can avoid full encoding.</p>
     *
     * @param state immutable rule state to hash
     * @return lowercase canonical SHA-256
     */
    default String stateHash(RuleState state) {
        return snapshot(state, 0).sha256();
    }

    /**
     * Encodes immutable rule state for persistence and replay.
     *
     * @param state immutable rule state to encode
     * @param sequence monotonic snapshot sequence assigned by the core
     * @return bounded versioned state snapshot
     */
    RuleStateSnapshot snapshot(RuleState state, long sequence);

    /**
     * Restores immutable rule state from a provider-owned snapshot.
     *
     * @param snapshot validated snapshot to decode
     * @return restored immutable rule state
     */
    RuleState restore(RuleStateSnapshot snapshot);
}
