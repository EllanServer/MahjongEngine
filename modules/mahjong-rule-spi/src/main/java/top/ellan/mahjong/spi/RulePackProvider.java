package top.ellan.mahjong.spi;

import java.util.List;

/**
 * Pure rule-pack boundary. Implementations may not access platform APIs, files, network, wall clocks,
 * or create threads. The core serializes calls per match and may call different matches concurrently.
 */
public interface RulePackProvider {
    RulePackDescriptor descriptor();

    RuleState createMatch(MatchSetup setup);

    RuleTransition transition(RuleState state, PlayerId actor, RuleAction action);

    List<LegalAction> legalActions(RuleState state, PlayerId actor);

    PublicRuleView publicView(RuleState state, long revision);

    PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision);

    /** Canonical SHA-256 of state. Providers should override this when hashing can avoid full encoding. */
    default String stateHash(RuleState state) {
        return snapshot(state, 0).sha256();
    }

    RuleStateSnapshot snapshot(RuleState state, long sequence);

    RuleState restore(RuleStateSnapshot snapshot);
}
