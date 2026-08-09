package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.MatchSetup;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleEvent;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleMatchResult;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePlayerResult;
import top.ellan.mahjong.spi.RuleProfileDescriptor;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.SpiVersion;
import top.ellan.mahjong.spi.TransitionDisposition;

class RuleComputationEngineTest {
    private static final PlayerId FIRST = new PlayerId(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final PlayerId SECOND = new PlayerId(
            UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test
    void requiresAndCarriesCompleteTerminalResult() {
        RuleComputation computed = engine(new TerminalProvider(false)).transition(
                new TerminalState(false),
                FIRST,
                new RuleAction("finish", new byte[0]),
                0,
                0,
                1,
                List.of());

        assertEquals("test.rank.v1", computed.matchResult().orElseThrow().rankSystem());
        assertEquals(2, computed.matchResult().orElseThrow().players().size());
        assertThrows(
                IllegalStateException.class,
                () -> engine(new TerminalProvider(true)).transition(
                        new TerminalState(false),
                        FIRST,
                        new RuleAction("finish", new byte[0]),
                        0,
                        0,
                        1,
                        List.of()));
    }

    private static RuleComputationEngine engine(RulePackProvider provider) {
        return new RuleComputationEngine(
                provider,
                List.of(
                        new TableParticipant(
                                FIRST,
                                ParticipantRole.PLAYER,
                                Optional.of(new SeatId(0))),
                        new TableParticipant(
                                SECOND,
                                ParticipantRole.PLAYER,
                                Optional.of(new SeatId(1)))),
                TableActorConfig.DEFAULT);
    }

    private record TerminalState(boolean ended) implements RuleState {}

    private static final class TerminalProvider implements RulePackProvider {
        private final boolean omitResult;

        private TerminalProvider(boolean omitResult) {
            this.omitResult = omitResult;
        }

        @Override
        public RulePackDescriptor descriptor() {
            return new RulePackDescriptor(
                    new RuleId("test"),
                    "1.0.0",
                    SpiVersion.CURRENT,
                    ">=2.0.0",
                    1,
                    List.of(new RuleProfileDescriptor(
                            new ProfileId("test"), "Test", "{}")),
                    Set.of());
        }

        @Override
        public RuleState createMatch(MatchSetup setup) {
            return new TerminalState(false);
        }

        @Override
        public RuleTransition transition(RuleState state, PlayerId actor, RuleAction action) {
            return new RuleTransition(
                    new TerminalState(true),
                    TransitionDisposition.MATCH_ENDED,
                    List.of(new RuleEvent("match-ended", new byte[0])),
                    List.of(),
                    "accepted");
        }

        @Override
        public List<LegalAction> legalActions(RuleState state, PlayerId actor) {
            return List.of();
        }

        @Override
        public Optional<RuleMatchResult> matchResult(RuleState state) {
            if (omitResult) {
                return Optional.empty();
            }
            return Optional.of(new RuleMatchResult(
                    "test.rank.v1",
                    List.of(
                            new RulePlayerResult(
                                    FIRST, new SeatId(0), 1, 10, 4_000, new byte[0]),
                            new RulePlayerResult(
                                    SECOND, new SeatId(1), 2, -10, 3_000, new byte[0]))));
        }

        @Override
        public PublicRuleView publicView(RuleState state, long revision) {
            return new PublicRuleView(
                    revision,
                    "ended",
                    List.of(),
                    Map.of(),
                    new RuleTablePresentation(
                            2,
                            new RuleWallPresentation(
                                    List.of(1, 1), 0, RuleWallDirection.CLOCKWISE),
                            6,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Override
        public PrivateRuleView privateView(
                RuleState state, PlayerId viewer, long revision) {
            return new PrivateRuleView(
                    revision,
                    viewer,
                    new SeatId(viewer.equals(FIRST) ? 0 : 1),
                    List.of(),
                    Map.of());
        }

        @Override
        public RuleStateSnapshot snapshot(RuleState state, long sequence) {
            int value = ((TerminalState) state).ended() ? 1 : 0;
            return new RuleStateSnapshot(
                    1,
                    sequence,
                    ByteBuffer.allocate(4).putInt(value).array(),
                    String.format("%064x", value));
        }

        @Override
        public RuleState restore(RuleStateSnapshot snapshot) {
            return new TerminalState(ByteBuffer.wrap(snapshot.payload()).getInt() == 1);
        }
    }
}
