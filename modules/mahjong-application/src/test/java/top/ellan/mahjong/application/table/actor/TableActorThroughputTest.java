package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.concurrent.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.feedback.HumanDecisionWarningPort;
import top.ellan.mahjong.application.table.MatchCompletionPort;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.application.persistence.EventStorePort;
import top.ellan.mahjong.application.persistence.MatchWriteBatch;
import top.ellan.mahjong.application.persistence.PersistAck;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.security.SecureActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.match.CompetitionRef;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.LegalAction;
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
import top.ellan.mahjong.spi.RulePackRef;
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

/**
 * Records how many accepted actions the actor pipeline sustains across many concurrent tables.
 *
 * <p>The rule provider here is deliberately trivial, so the number measures the plumbing — mailbox
 * handoff, fair executor dispatch, projection rebuild, token issue and outbox append — rather than
 * rule evaluation. Real rule costs are measured by each pack's own microbenchmark.
 *
 * <p>The assertions are correctness invariants only. Throughput is printed rather than asserted,
 * because a timing threshold on shared CI hardware fails for reasons unrelated to this code. Compare
 * printed runs on one machine to spot a regression.
 */
class TableActorThroughputTest {
    private static final int TABLES = 64;
    private static final int PLAYERS_PER_TABLE = 4;
    private static final int ROUNDS = 12;

    @Test
    void manyTablesSustainConcurrentAcceptedActions() throws Exception {
        ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(
                8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8192),
                new ThreadPoolExecutor.AbortPolicy());
        try (dispatcher;
                FairRuleExecutor rules = new FairRuleExecutor(8, 4096, "throughput-rule-test");
                BoundedDeadlineScheduler deadlines =
                        new BoundedDeadlineScheduler(4096, dispatcher, "throughput-deadline-test")) {
            List<Fixture> fixtures = start(dispatcher, rules, deadlines);

            long started = System.nanoTime();
            int accepted = 0;
            for (int round = 1; round <= ROUNDS; round++) {
                List<CompletableFuture<TableActionResult>> submitted =
                        new ArrayList<>(fixtures.size());
                for (Fixture fixture : fixtures) {
                    submitted.add(submitNext(fixture, round));
                }
                for (CompletableFuture<TableActionResult> result : submitted) {
                    TableActionResult outcome = result.get(20, TimeUnit.SECONDS);
                    assertEquals(TableActionCode.ACCEPTED_MEMORY, outcome.code());
                    accepted++;
                }
            }
            long elapsedNanos = System.nanoTime() - started;

            assertEquals(TABLES * ROUNDS, accepted);
            for (Fixture fixture : fixtures) {
                assertEquals(ROUNDS, fixture.latest().get().revision());
            }

            double perAction = (double) elapsedNanos / accepted;
            System.out.printf(
                    "BENCHMARK name=actorPipeline tables=%d rounds=%d actions=%d "
                            + "elapsedMs=%.1f us/action=%.1f actions/s=%,.0f%n",
                    TABLES,
                    ROUNDS,
                    accepted,
                    elapsedNanos / 1_000_000.0,
                    perAction / 1_000.0,
                    1_000_000_000.0 / perAction);

            CompletableFuture.allOf(fixtures.stream()
                            .map(fixture -> fixture.actor().closeAndDrain().toCompletableFuture())
                            .toArray(CompletableFuture[]::new))
                    .get(20, TimeUnit.SECONDS);
        }
    }

    private List<Fixture> start(
            ThreadPoolExecutor dispatcher, FairRuleExecutor rules, BoundedDeadlineScheduler deadlines)
            throws Exception {
        ThroughputProvider provider = new ThroughputProvider();
        List<Fixture> fixtures = new ArrayList<>(TABLES);
        for (int tableIndex = 0; tableIndex < TABLES; tableIndex++) {
            MatchId matchId = MatchId.random();
            TableId tableId = TableId.random();
            List<TableParticipant> participants = participants(tableIndex);
            AtomicReference<TableProjection> latest = new AtomicReference<>();
            CountDownLatch first = new CountDownLatch(1);
            TableActor actor = new TableActor(
                    dispatcher,
                    rules,
                    rules,
                    provider,
                    new PersistenceOutbox(
                            matchId, 0, new ImmediateStore(), deadlines, Clock.systemUTC()),
                    deadlines,
                    projection -> {
                        latest.set(projection);
                        first.countDown();
                    },
                    TablePresentationCuePort.NONE,
                    HumanDecisionWarningPort.NONE,
                    TableOpeningPresentationPort.NONE,
                    true,
                    MatchCompletionPort.NONE,
                    new SecureActionTokenIssuer(),
                    Clock.systemUTC(),
                    TableActorConfig.DEFAULT,
                    aggregate(tableId, matchId, participants),
                    new CounterState(0),
                    0);
            fixtures.add(new Fixture(actor, participants.getFirst().playerId(), latest, first));
            actor.start();
        }
        for (Fixture fixture : fixtures) {
            assertTrue(fixture.first().await(20, TimeUnit.SECONDS), "table never published");
        }
        return fixtures;
    }

    /**
     * Action tokens are single use, so each round must read the projection the previous round
     * produced. The actor publishes a projection per accepted action, so waiting for the expected
     * revision is what keeps this loop honest instead of racing the callback.
     */
    private static CompletableFuture<TableActionResult> submitNext(Fixture fixture, int round)
            throws InterruptedException {
        long required = round - 1L;
        TableProjection projection = fixture.latest().get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (projection == null || projection.revision() < required) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(
                        "table never reached revision " + required);
            }
            Thread.onSpinWait();
            projection = fixture.latest().get();
        }
        return fixture.actor()
                .submit(
                        fixture.actorId(),
                        projection.authorizedActions()
                                .get(fixture.actorId())
                                .getFirst()
                                .token())
                .toCompletableFuture();
    }

    private static List<TableParticipant> participants(int tableIndex) {
        List<TableParticipant> result = new ArrayList<>(PLAYERS_PER_TABLE);
        for (int seat = 0; seat < PLAYERS_PER_TABLE; seat++) {
            long discriminator = (long) tableIndex * PLAYERS_PER_TABLE + seat + 1;
            result.add(new TableParticipant(
                    new PlayerId(new UUID(0, discriminator)),
                    ParticipantRole.PLAYER,
                    Optional.of(new SeatId(seat))));
        }
        return List.copyOf(result);
    }

    private static TableAggregate aggregate(
            TableId tableId, MatchId matchId, List<TableParticipant> participants) {
        MatchBinding binding = new MatchBinding(
                matchId,
                new RulePackRef(new RuleId("riichi"), "1.0.0", "0".repeat(64), 1),
                new ProfileId("standard"),
                "0".repeat(64),
                Instant.parse("2026-08-08T00:00:00Z"));
        return new TableAggregate(
                tableId,
                0,
                TableLifecycle.ACTIVE,
                participants,
                Optional.of(binding),
                CompetitionRef.none());
    }

    private record Fixture(
            TableActor actor,
            PlayerId actorId,
            AtomicReference<TableProjection> latest,
            CountDownLatch first) {}

    private record CounterState(int value) implements RuleState {}

    /** Accepts every action immediately; the point is to measure the pipeline, not the rules. */
    private static final class ThroughputProvider implements RulePackProvider {
        private static final RulePackDescriptor DESCRIPTOR = new RulePackDescriptor(
                new RuleId("riichi"),
                "1.0.0",
                SpiVersion.CURRENT,
                ">=2.0.0",
                1,
                List.of(new RuleProfileDescriptor(new ProfileId("standard"), "Standard", "{}")),
                Set.of());

        @Override
        public RulePackDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public RuleState createMatch(MatchSetup setup) {
            return new CounterState(0);
        }

        @Override
        public RuleTransition transition(RuleState state, PlayerId actor, RuleAction action) {
            return new RuleTransition(
                    new CounterState(((CounterState) state).value() + 1),
                    TransitionDisposition.ACCEPTED,
                    List.of(new RuleEvent("advanced", action.payload())),
                    "accepted");
        }

        @Override
        public List<LegalAction> legalActions(RuleState state, PlayerId actor) {
            return List.of(new LegalAction(
                    "advance",
                    new RuleAction("advance", new byte[] {1}),
                    ActionPresentation.actionRow("advance")));
        }

        @Override
        public PublicRuleView publicView(RuleState state, long revision) {
            return new PublicRuleView(
                    revision,
                    "playing",
                    List.of(),
                    Map.of(),
                    new RuleTablePresentation(
                            4,
                            new RuleWallPresentation(
                                    List.of(17, 17, 17, 17), 0, RuleWallDirection.CLOCKWISE),
                            6,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Override
        public PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision) {
            int seat = Math.floorMod((int) viewer.value().getLeastSignificantBits() - 1, 4);
            return new PrivateRuleView(revision, viewer, new SeatId(seat), List.of(), Map.of());
        }

        @Override
        public RuleStateSnapshot snapshot(RuleState state, long sequence) {
            byte[] payload =
                    ByteBuffer.allocate(4).putInt(((CounterState) state).value()).array();
            return new RuleStateSnapshot(1, sequence, payload, stateHash(state));
        }

        @Override
        public String stateHash(RuleState state) {
            return String.format("%064x", ((CounterState) state).value());
        }

        @Override
        public RuleState restore(RuleStateSnapshot snapshot) {
            return new CounterState(ByteBuffer.wrap(snapshot.payload()).getInt());
        }
    }

    private static final class ImmediateStore implements EventStorePort {
        @Override
        public CompletionStage<PersistAck> appendBatch(MatchWriteBatch batch) {
            return CompletableFuture.completedFuture(
                    new PersistAck(batch.events().getLast().sequence()));
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
