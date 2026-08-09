package top.ellan.mahjong.persistence.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleProfileDescriptor;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.SpiVersion;
import top.ellan.mahjong.spi.TransitionDisposition;

class RuleStateReplayVerifierTest {
    private static final PlayerId ACTOR = new PlayerId(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void replaysOnlyCommittedActionAndVerifiesEveryBoundary() {
        CounterProvider provider = new CounterProvider();
        MatchRecoveryData recovery = recovery(provider, false);

        VerifiedRecovery verified = RuleStateReplayVerifier.replay(provider, recovery);

        assertEquals(new CounterState(3), verified.state());
        assertEquals(1, verified.stateRevision());
        assertEquals(2, verified.eventSequence());
    }

    @Test
    void rejectsCanonicalEventDivergence() {
        CounterProvider provider = new CounterProvider();
        MatchRecoveryData recovery = recovery(provider, true);

        assertThrows(
                PersistenceConflictException.class,
                () -> RuleStateReplayVerifier.replay(provider, recovery));
    }

    private static MatchRecoveryData recovery(CounterProvider provider, boolean tamper) {
        CounterState initial = new CounterState(1);
        CounterState next = new CounterState(3);
        RuleStateSnapshot snapshot = provider.snapshot(initial, 0);
        RuleAction action = new RuleAction("add", new byte[] {2});
        List<RuleEvent> expected = List.of(
                new RuleEvent("counter-added", new byte[] {(byte) (tamper ? 9 : 2)}),
                new RuleEvent("counter-value", new byte[] {3}));
        RecoveredAction recoveredAction = new RecoveredAction(
                1,
                1,
                2,
                ACTOR,
                action,
                expected,
                provider.stateHash(initial),
                provider.stateHash(next));
        RulePackRef reference = new RulePackRef(
                new RuleId("riichi"), "1.0.0", "a".repeat(64), 1);
        MatchBinding binding = new MatchBinding(
                MatchId.random(),
                reference,
                new ProfileId("standard"),
                "b".repeat(64),
                Instant.parse("2026-08-08T00:00:00Z"));
        MatchInstanceRecord match = new MatchInstanceRecord(
                binding,
                TableId.random(),
                TableLifecycle.ACTIVE,
                Instant.parse("2026-08-08T00:00:01Z"),
                2);
        return new MatchRecoveryData(match, List.of(), snapshot, 0, List.of(recoveredAction));
    }

    private record CounterState(int value) implements RuleState {}

    private static final class CounterProvider implements RulePackProvider {
        @Override
        public RulePackDescriptor descriptor() {
            return new RulePackDescriptor(
                    new RuleId("riichi"),
                    "1.0.0",
                    SpiVersion.CURRENT,
                    ">=1.5.0",
                    1,
                    List.of(new RuleProfileDescriptor(
                            new ProfileId("standard"), "Standard", "{}")),
                    Set.of());
        }

        @Override
        public RuleState createMatch(top.ellan.mahjong.spi.MatchSetup setup) {
            return new CounterState(0);
        }

        @Override
        public RuleTransition transition(RuleState state, PlayerId actor, RuleAction action) {
            CounterState current = (CounterState) state;
            int amount = action.payload()[0];
            CounterState next = new CounterState(current.value() + amount);
            return new RuleTransition(
                    next,
                    TransitionDisposition.ACCEPTED,
                    List.of(
                            new RuleEvent("counter-added", new byte[] {(byte) amount}),
                            new RuleEvent("counter-value", new byte[] {(byte) next.value()})),
                    "accepted");
        }

        @Override
        public List<LegalAction> legalActions(RuleState state, PlayerId actor) {
            return List.of();
        }

        @Override
        public PublicRuleView publicView(RuleState state, long revision) {
            return new PublicRuleView(revision, "test", List.of(), Map.of());
        }

        @Override
        public PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision) {
            return new PrivateRuleView(revision, viewer, List.of(), Map.of());
        }

        @Override
        public String stateHash(RuleState state) {
            return hash(payload((CounterState) state));
        }

        @Override
        public RuleStateSnapshot snapshot(RuleState state, long sequence) {
            byte[] payload = payload((CounterState) state);
            return new RuleStateSnapshot(1, sequence, payload, hash(payload));
        }

        @Override
        public RuleState restore(RuleStateSnapshot snapshot) {
            return new CounterState(ByteBuffer.wrap(snapshot.payload()).getInt());
        }

        private static byte[] payload(CounterState state) {
            return ByteBuffer.allocate(Integer.BYTES).putInt(state.value()).array();
        }

        private static String hash(byte[] payload) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(payload));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
