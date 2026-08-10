package top.ellan.mahjong.runtime.loading;

import java.net.MalformedURLException;
import java.nio.file.Path;

import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.MatchSetup;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;

/**
 * Builds real {@link LoadedRulePack} instances for lifecycle tests.
 *
 * <p>It lives in this package because the pack constructor is deliberately package-private: only the
 * loader may mint one in production. Each pack gets its own child-first classloader so unload and
 * reclaim behaviour is exercised for real, while the provider itself is inert.</p>
 */
public final class RulePackTestPacks {
    private RulePackTestPacks() {}

    public static LoadedRulePack create(RulePackRef reference, Path artifact)
            throws MalformedURLException {
        ChildFirstRuleClassLoader loader =
                new ChildFirstRuleClassLoader(
                        artifact.toUri().toURL(), RulePackProvider.class.getClassLoader());
        return new LoadedRulePack(reference, artifact, new InertProvider(), loader);
    }

    /** Every method fails loudly; lifecycle tests never execute rule logic. */
    private static final class InertProvider implements RulePackProvider {
        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("inert test provider");
        }

        @Override
        public RulePackDescriptor descriptor() {
            throw unused();
        }

        @Override
        public RuleState createMatch(MatchSetup setup) {
            throw unused();
        }

        @Override
        public RuleTransition transition(RuleState state, PlayerId actor, RuleAction action) {
            throw unused();
        }

        @Override
        public java.util.List<LegalAction> legalActions(RuleState state, PlayerId actor) {
            throw unused();
        }

        @Override
        public PublicRuleView publicView(RuleState state, long revision) {
            throw unused();
        }

        @Override
        public PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision) {
            throw unused();
        }

        @Override
        public RuleStateSnapshot snapshot(RuleState state, long sequence) {
            throw unused();
        }

        @Override
        public RuleState restore(RuleStateSnapshot snapshot) {
            throw unused();
        }
    }
}
