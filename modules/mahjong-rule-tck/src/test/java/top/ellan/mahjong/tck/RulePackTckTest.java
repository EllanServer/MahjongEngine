package top.ellan.mahjong.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.MatchPlayer;
import top.ellan.mahjong.spi.MatchSeed;
import top.ellan.mahjong.spi.MatchSetup;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleProfileDescriptor;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.SpiVersion;
import top.ellan.mahjong.spi.TransitionDisposition;

class RulePackTckTest {
    private static final ProfileId PROFILE = new ProfileId("standard");
    private static final PlayerId FIRST =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final PlayerId SECOND =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test
    void acceptsAStableImmutableProvider() {
        RulePackTckReport report = RulePackTck.verify(
                new CounterProvider(false), fixture());

        assertEquals(2, report.playersVerified());
        assertEquals(2, report.legalActionsVerified());
        assertEquals(1, report.rejectedActionsVerified());
        assertEquals(3, report.snapshotsVerified());
    }

    @Test
    void rejectsSeedNondeterminism() {
        assertThrows(
                RulePackContractViolation.class,
                () -> RulePackTck.verify(new CounterProvider(true), fixture()));
    }

    @Test
    void rejectsAProviderThatAuthorizesUnseatedPlayers() {
        assertThrows(
                RulePackContractViolation.class,
                () -> RulePackTck.verify(new CounterProvider(false, true), fixture()));
    }

    private static RulePackTckCase fixture() {
        MatchSetup setup = new MatchSetup(
                PROFILE,
                new MatchSeed(7, 11),
                List.of(
                        new MatchPlayer(FIRST, new SeatId(0)),
                        new MatchPlayer(SECOND, new SeatId(1))),
                Map.of());
        return new RulePackTckCase(
                setup, Map.of(FIRST, new RuleAction("illegal", new byte[0])));
    }

    private record CounterState(int value) implements RuleState {}

    private static final class CounterProvider implements RulePackProvider {
        private static final RulePackDescriptor DESCRIPTOR = new RulePackDescriptor(
                new RuleId("riichi"),
                "1.0.0",
                SpiVersion.CURRENT,
                ">=1.5.0",
                1,
                List.of(new RuleProfileDescriptor(PROFILE, "Standard", "{}")),
                Set.of());
        private final boolean nondeterministic;
        private final boolean authorizesOutsiders;
        private int creations;

        private CounterProvider(boolean nondeterministic) {
            this(nondeterministic, false);
        }

        private CounterProvider(boolean nondeterministic, boolean authorizesOutsiders) {
            this.nondeterministic = nondeterministic;
            this.authorizesOutsiders = authorizesOutsiders;
        }

        @Override
        public RulePackDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public RuleState createMatch(MatchSetup setup) {
            return new CounterState(nondeterministic ? creations++ : 0);
        }

        @Override
        public RuleTransition transition(RuleState state, PlayerId actor, RuleAction action) {
            CounterState current = (CounterState) state;
            if (!seated(actor) && !authorizesOutsiders) {
                return RuleTransition.rejected(state, "actor-not-seated");
            }
            if (!action.type().equals("advance")) {
                return RuleTransition.rejected(state, "illegal-action");
            }
            return new RuleTransition(
                    new CounterState(current.value() + 1),
                    TransitionDisposition.ACCEPTED,
                    List.of(new RuleEvent("advanced", action.payload())),
                    "accepted");
        }

        @Override
        public List<LegalAction> legalActions(RuleState state, PlayerId actor) {
            if (!seated(actor) && !authorizesOutsiders) {
                return List.of();
            }
            return List.of(new LegalAction(
                    "advance", new RuleAction("advance", new byte[] {1}), Map.of()));
        }

        @Override
        public PublicRuleView publicView(RuleState state, long revision) {
            return new PublicRuleView(revision, "playing", List.of(), Map.of());
        }

        @Override
        public PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision) {
            if (!seated(viewer) && !authorizesOutsiders) {
                throw new IllegalArgumentException("viewer is not seated");
            }
            return new PrivateRuleView(revision, viewer, List.of(), Map.of());
        }

        private static boolean seated(PlayerId player) {
            return FIRST.equals(player) || SECOND.equals(player);
        }

        @Override
        public RuleStateSnapshot snapshot(RuleState state, long sequence) {
            byte[] payload = ByteBuffer.allocate(Integer.BYTES)
                    .putInt(((CounterState) state).value())
                    .array();
            return new RuleStateSnapshot(1, sequence, payload, sha256(payload));
        }

        @Override
        public RuleState restore(RuleStateSnapshot snapshot) {
            return new CounterState(ByteBuffer.wrap(snapshot.payload()).getInt());
        }

        private static String sha256(byte[] value) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(value));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
