package top.ellan.mahjong.tck;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.MatchPlayer;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.SpiVersion;
import top.ellan.mahjong.spi.TransitionDisposition;

/** Reusable provider-level checks required before an official rule pack can be released. */
public final class RulePackTck {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private RulePackTck() {}

    public static RulePackTckReport verify(
            RulePackProvider provider, RulePackTckCase testCase) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(testCase, "testCase");
        RulePackDescriptor descriptor = require(provider.descriptor(), "null descriptor");
        check(
                SpiVersion.CURRENT.equals(descriptor.spiVersion()),
                "provider uses an unsupported SPI version");
        check(
                descriptor.profiles().stream()
                        .anyMatch(profile -> profile.id().equals(testCase.setup().profileId())),
                "fixture profile is absent from the descriptor");

        RuleState first = require(provider.createMatch(testCase.setup()), "null initial state");
        RuleState second = require(provider.createMatch(testCase.setup()), "null repeated state");
        String initialHash = checkedHash(provider.stateHash(first));
        check(initialHash.equals(checkedHash(provider.stateHash(second))),
                "same seed/configuration produced different states");

        int snapshots = 0;
        verifySnapshot(provider, first, 0, initialHash);
        snapshots++;
        RuleStateSnapshot laterSequence = verifySnapshot(provider, first, 17, initialHash);
        snapshots++;
        RuleStateSnapshot initialSnapshot = provider.snapshot(first, 0);
        check(Arrays.equals(initialSnapshot.payload(), laterSequence.payload()),
                "snapshot payload depends on the external event sequence");

        PublicRuleView publicView = require(provider.publicView(first, 0), "null public view");
        check(publicView.stateRevision() == 0, "public view has the wrong revision");
        check(publicView.equals(provider.publicView(second, 0)),
                "public view is not deterministic");

        int legalActionCount = 0;
        PlayerId selectedActor = null;
        RuleAction selectedAction = null;
        for (MatchPlayer player : testCase.setup().players()) {
            PlayerId playerId = player.playerId();
            PrivateRuleView privateView =
                    require(provider.privateView(first, playerId, 0), "null private view");
            check(privateView.viewer().equals(playerId),
                    "private view is authorized for a different player");
            check(privateView.stateRevision() == 0, "private view has the wrong revision");
            check(privateView.equals(provider.privateView(second, playerId, 0)),
                    "private view is not deterministic");
            List<LegalAction> firstActions =
                    List.copyOf(require(provider.legalActions(first, playerId), "null action list"));
            List<LegalAction> secondActions =
                    List.copyOf(require(provider.legalActions(second, playerId), "null action list"));
            check(firstActions.equals(secondActions), "legal actions are not deterministic");
            check(
                    firstActions.stream().map(LegalAction::key).distinct().count()
                            == firstActions.size(),
                    "legal action keys are not unique for a player");
            legalActionCount += firstActions.size();
            if (selectedAction == null && !firstActions.isEmpty()) {
                selectedActor = playerId;
                selectedAction = firstActions.getFirst().action();
            }
        }
        check(initialHash.equals(checkedHash(provider.stateHash(first))),
                "view or legal-action generation mutated the initial state");
        check(selectedAction != null, "fixture exposes no legal action");

        RuleTransition firstTransition = require(
                provider.transition(first, selectedActor, selectedAction), "null transition");
        RuleTransition repeatedTransition = require(
                provider.transition(second, selectedActor, selectedAction), "null repeated transition");
        check(firstTransition.accepted(), "a generated legal action was rejected");
        check(!firstTransition.events().isEmpty(), "accepted transition emitted no events");
        check(firstTransition.nextState() != first, "accepted transition reused the input state");
        check(firstTransition.disposition() == repeatedTransition.disposition()
                        && firstTransition.reasonCode().equals(repeatedTransition.reasonCode())
                        && eventsEqual(firstTransition.events(), repeatedTransition.events())
                        && checkedHash(provider.stateHash(firstTransition.nextState()))
                                .equals(checkedHash(provider.stateHash(
                                        repeatedTransition.nextState()))),
                "transition is not deterministic");
        check(initialHash.equals(checkedHash(provider.stateHash(first))),
                "transition mutated its input state");
        long resultingSequence = firstTransition.events().size();
        verifySnapshot(
                provider,
                firstTransition.nextState(),
                resultingSequence,
                checkedHash(provider.stateHash(firstTransition.nextState())));
        snapshots++;

        int rejected = 0;
        for (Map.Entry<PlayerId, RuleAction> entry : testCase.rejectedActions().entrySet()) {
            String before = checkedHash(provider.stateHash(first));
            RuleTransition transition = require(
                    provider.transition(first, entry.getKey(), entry.getValue()),
                    "null rejection transition");
            check(transition.disposition() == TransitionDisposition.REJECTED,
                    "guaranteed-illegal action was accepted");
            check(transition.nextState() == first,
                    "rejected action did not return the identical state instance");
            check(transition.events().isEmpty(), "rejected action emitted events");
            check(before.equals(checkedHash(provider.stateHash(first))),
                    "rejected action mutated its input state");
            rejected++;
        }
        return new RulePackTckReport(
                testCase.setup().players().size(), legalActionCount, rejected, snapshots);
    }

    private static RuleStateSnapshot verifySnapshot(
            RulePackProvider provider, RuleState state, long sequence, String expectedStateHash) {
        RuleStateSnapshot first = require(provider.snapshot(state, sequence), "null snapshot");
        RuleStateSnapshot second = require(provider.snapshot(state, sequence), "null repeated snapshot");
        check(first.equals(second), "snapshot encoding is not deterministic");
        check(first.sequence() == sequence, "snapshot has the wrong event sequence");
        check(first.schemaVersion() == provider.descriptor().stateSchemaVersion(),
                "snapshot has the wrong schema version");
        check(first.sha256().equals(sha256(first.payload())),
                "snapshot SHA-256 does not authenticate its payload");
        RuleState restored = require(provider.restore(first), "null restored state");
        check(expectedStateHash.equals(checkedHash(provider.stateHash(restored))),
                "snapshot restore changed canonical state");
        return first;
    }

    private static boolean eventsEqual(List<RuleEvent> first, List<RuleEvent> second) {
        return first.equals(second);
    }

    private static String checkedHash(String hash) {
        check(hash != null && SHA256.matcher(hash).matches(),
                "state hash is not lowercase SHA-256");
        return hash;
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256", impossible);
        }
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new RulePackContractViolation(message);
        }
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new RulePackContractViolation(message);
        }
    }
}
