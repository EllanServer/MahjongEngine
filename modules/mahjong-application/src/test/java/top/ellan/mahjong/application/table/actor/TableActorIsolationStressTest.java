package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.concurrent.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.application.persistence.EventStorePort;
import top.ellan.mahjong.application.persistence.MatchWriteBatch;
import top.ellan.mahjong.application.persistence.PersistAck;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.security.SecureActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.application.table.MatchCompletionPort;
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
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.SpiVersion;
import top.ellan.mahjong.spi.TransitionDisposition;

/** Deterministic isolation coverage for the 64-table / 256-player acceptance topology. */
class TableActorIsolationStressTest {
    private static final int TABLES = 64;
    private static final int PLAYERS_PER_TABLE = 4;

    @Test
    void oneSlowTableCannotDelayTheOtherSixtyThree() throws Exception {
        ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(
                8,
                8,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4096),
                new ThreadPoolExecutor.AbortPolicy());
        try (dispatcher;
                FairRuleExecutor rules = new FairRuleExecutor(8, 512, "isolation-rule-test");
                BoundedDeadlineScheduler deadlines =
                        new BoundedDeadlineScheduler(1024, dispatcher, "isolation-deadline-test")) {
            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch releaseSlow = new CountDownLatch(1);
            LoadProvider provider = new LoadProvider(slowStarted, releaseSlow);
            List<Fixture> fixtures = new ArrayList<>(TABLES);

            for (int tableIndex = 0; tableIndex < TABLES; tableIndex++) {
                MatchId matchId = MatchId.random();
                TableId tableId = TableId.random();
                List<TableParticipant> participants = participants(tableIndex);
                CompletableFuture<TableProjection> firstProjection = new CompletableFuture<>();
                PersistenceOutbox outbox = new PersistenceOutbox(
                        matchId,
                        0,
                        new ImmediateStore(),
                        deadlines,
                        Clock.systemUTC());
                TableActor actor = new TableActor(
                        dispatcher,
                        rules,
                        rules,
                        provider,
                        outbox,
                        deadlines,
                        firstProjection::complete,
                        TablePresentationCuePort.NONE,
                        TableOpeningPresentationPort.NONE,
                        true,
                        MatchCompletionPort.NONE,
                        new SecureActionTokenIssuer(),
                        Clock.systemUTC(),
                        TableActorConfig.DEFAULT,
                        aggregate(tableId, matchId, participants),
                        new LoadState(tableIndex == 0, 0),
                        0);
                fixtures.add(new Fixture(actor, participants.getFirst().playerId(), firstProjection));
                actor.start();
            }

            for (Fixture fixture : fixtures) {
                fixture.projection().get(5, TimeUnit.SECONDS);
            }

            CompletableFuture<TableActionResult> slow = submit(fixtures.getFirst());
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS));

            List<CompletableFuture<TableActionResult>> fast = fixtures.subList(1, TABLES).stream()
                    .map(TableActorIsolationStressTest::submit)
                    .toList();
            CompletableFuture.allOf(fast.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
            for (CompletableFuture<TableActionResult> result : fast) {
                assertEquals(TableActionCode.ACCEPTED_MEMORY, result.join().code());
                assertEquals(1, result.join().revision());
            }
            assertFalse(slow.isDone());

            releaseSlow.countDown();
            assertEquals(
                    TableActionCode.ACCEPTED_MEMORY,
                    slow.get(2, TimeUnit.SECONDS).code());

            CompletableFuture.allOf(fixtures.stream()
                            .map(fixture -> fixture.actor().closeAndDrain().toCompletableFuture())
                            .toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
        }
    }

    private static CompletableFuture<TableActionResult> submit(Fixture fixture) {
        TableProjection projection = fixture.projection().join();
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
        RulePackRef reference = new RulePackRef(
                new RuleId("riichi"), "1.0.0", "0".repeat(64), 1);
        MatchBinding binding = new MatchBinding(
                matchId,
                reference,
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
            CompletableFuture<TableProjection> projection) {}

    private record LoadState(boolean slow, int value) implements RuleState {}

    private static final class LoadProvider implements RulePackProvider {
        private static final RulePackDescriptor DESCRIPTOR = new RulePackDescriptor(
                new RuleId("riichi"),
                "1.0.0",
                SpiVersion.CURRENT,
                ">=1.5.0",
                1,
                List.of(new RuleProfileDescriptor(
                        new ProfileId("standard"), "Standard", "{}")),
                Set.of());
        private final CountDownLatch slowStarted;
        private final CountDownLatch releaseSlow;

        private LoadProvider(CountDownLatch slowStarted, CountDownLatch releaseSlow) {
            this.slowStarted = slowStarted;
            this.releaseSlow = releaseSlow;
        }

        @Override
        public RulePackDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public RuleState createMatch(MatchSetup setup) {
            return new LoadState(false, 0);
        }

        @Override
        public RuleTransition transition(RuleState state, PlayerId actor, RuleAction action) {
            LoadState current = (LoadState) state;
            if (current.slow() && current.value() == 0) {
                slowStarted.countDown();
                try {
                    if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("slow-table test release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("slow-table test interrupted", failure);
                }
            }
            return new RuleTransition(
                    new LoadState(current.slow(), current.value() + 1),
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
                                    List.of(17, 17, 17, 17),
                                    0,
                                    RuleWallDirection.CLOCKWISE),
                            6,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Override
        public PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision) {
            int seat = Math.floorMod((int) viewer.value().getLeastSignificantBits() - 1, 4);
            return new PrivateRuleView(
                    revision, viewer, new SeatId(seat), List.of(), Map.of());
        }

        @Override
        public RuleStateSnapshot snapshot(RuleState state, long sequence) {
            LoadState current = (LoadState) state;
            byte[] payload = ByteBuffer.allocate(5)
                    .put((byte) (current.slow() ? 1 : 0))
                    .putInt(current.value())
                    .array();
            return new RuleStateSnapshot(1, sequence, payload, stateHash(state));
        }

        @Override
        public String stateHash(RuleState state) {
            LoadState current = (LoadState) state;
            return String.format("%064x", current.value() + (current.slow() ? 1_000_000 : 0));
        }

        @Override
        public RuleState restore(RuleStateSnapshot snapshot) {
            ByteBuffer payload = ByteBuffer.wrap(snapshot.payload());
            return new LoadState(payload.get() == 1, payload.getInt());
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
