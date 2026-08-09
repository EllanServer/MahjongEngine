package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.concurrent.BoundedDeadlineScheduler;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.persistence.EventStorePort;
import top.ellan.mahjong.application.persistence.MatchWriteBatch;
import top.ellan.mahjong.application.persistence.PersistAck;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.security.SecureActionTokenIssuer;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.MatchId;
import top.ellan.mahjong.domain.ParticipantRole;
import top.ellan.mahjong.domain.TableAggregate;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.domain.TableParticipant;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.ActionToken;
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
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RuleProfileDescriptor;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.SpiVersion;
import top.ellan.mahjong.spi.TransitionDisposition;

class TableActorTest {
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void commitsMemoryBeforePersistenceAndRejectsAStaleToken() throws Exception {
        ThreadPoolExecutor dispatcher =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(128),
                        new ThreadPoolExecutor.AbortPolicy());
        try (dispatcher;
                FairRuleExecutor rules = new FairRuleExecutor(2, 32, "actor-rule-test");
                BoundedDeadlineScheduler deadlines =
                        new BoundedDeadlineScheduler(64, dispatcher, "actor-deadline-test")) {
            MatchId matchId = MatchId.random();
            PersistenceOutbox outbox =
                    new PersistenceOutbox(
                            matchId,
                            0,
                            new ImmediateStore(),
                            deadlines,
                            Clock.fixed(
                                    Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));
            ArrayBlockingQueue<TableProjection> projections = new ArrayBlockingQueue<>(8);
            TableActor actor =
                    new TableActor(
                            dispatcher,
                            rules,
                            new CounterProvider(),
                            outbox,
                            projections::offer,
                            new SecureActionTokenIssuer(),
                            Clock.systemUTC(),
                            TableActorConfig.DEFAULT,
                            aggregate(matchId),
                            new CounterState(0),
                            0);
            actor.start();
            TableProjection initial = projections.poll(2, TimeUnit.SECONDS);
            assertNotNull(initial);
            ActionToken token =
                    initial.authorizedActions().get(PLAYER).getFirst().token();

            TableActionResult accepted =
                    actor.submit(PLAYER, token).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(TableActionCode.ACCEPTED_MEMORY, accepted.code());
            assertEquals(1, accepted.revision());

            TableActionResult stale =
                    actor.submit(PLAYER, token).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(TableActionCode.STALE_TOKEN, stale.code());
            actor.close();
        }
    }

    @Test
    void mailboxRejectsImmediatelyAtItsConfiguredCapacity() throws Exception {
        FairRuleExecutor rules = new FairRuleExecutor(1, 8, "mailbox-rule-test");
        try (rules) {
            MatchId matchId = MatchId.random();
            TaskScheduler neverRuns = (task, delay) -> () -> true;
            PersistenceOutbox outbox =
                    new PersistenceOutbox(
                            matchId, 0, new ImmediateStore(), neverRuns, Clock.systemUTC());
            TableActor actor =
                    new TableActor(
                            ignored -> {},
                            rules,
                            new CounterProvider(),
                            outbox,
                            ignored -> {},
                            new SecureActionTokenIssuer(),
                            Clock.systemUTC(),
                            new TableActorConfig(8, 1, 8),
                            aggregate(matchId),
                            new CounterState(0),
                            0);
            ActionToken token = new ActionToken(UUID.randomUUID(), PLAYER, 0);
            for (int index = 0; index < 8; index++) {
                actor.submit(PLAYER, token);
            }
            TableActionResult overflow =
                    actor.submit(PLAYER, token).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TableActionCode.MAILBOX_FULL, overflow.code());
        }
    }

    @Test
    void hardOutboxCapacityRejectsTransitionBeforeMemoryCommit() throws Exception {
        ThreadPoolExecutor dispatcher =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(32),
                        new ThreadPoolExecutor.AbortPolicy());
        try (dispatcher; FairRuleExecutor rules = new FairRuleExecutor(1, 8, "capacity-rule-test")) {
            MatchId matchId = MatchId.random();
            PersistenceOutbox outbox =
                    new PersistenceOutbox(
                            matchId,
                            0,
                            new ImmediateStore(),
                            (task, delay) -> () -> true,
                            Clock.systemUTC(),
                            1,
                            1,
                            1,
                            Duration.ofMillis(50),
                            Duration.ofSeconds(2));
            ArrayBlockingQueue<TableProjection> projections = new ArrayBlockingQueue<>(4);
            TableActor actor =
                    new TableActor(
                            dispatcher,
                            rules,
                            new CounterProvider(2),
                            outbox,
                            projections::offer,
                            new SecureActionTokenIssuer(),
                            Clock.systemUTC(),
                            TableActorConfig.DEFAULT,
                            aggregate(matchId),
                            new CounterState(0),
                            0);
            actor.start();
            TableProjection initial = projections.poll(2, TimeUnit.SECONDS);
            assertNotNull(initial);

            TableActionResult result = actor.submit(
                            PLAYER,
                            initial.authorizedActions().get(PLAYER).getFirst().token())
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals(TableActionCode.TABLE_PAUSED, result.code());
            assertEquals(0, result.revision());
            assertEquals(0, actor.snapshot().revision());
            assertEquals(0, outbox.health().unpersistedEvents());
            actor.close();
        }
    }

    @Test
    void providerCannotExceedPerActionEventLimit() throws Exception {
        ThreadPoolExecutor dispatcher =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(32),
                        new ThreadPoolExecutor.AbortPolicy());
        try (dispatcher; FairRuleExecutor rules = new FairRuleExecutor(1, 8, "event-limit-test")) {
            MatchId matchId = MatchId.random();
            PersistenceOutbox outbox = new PersistenceOutbox(
                    matchId,
                    0,
                    new ImmediateStore(),
                    (task, delay) -> () -> true,
                    Clock.systemUTC());
            ArrayBlockingQueue<TableProjection> projections = new ArrayBlockingQueue<>(4);
            TableActor actor = new TableActor(
                    dispatcher,
                    rules,
                    new CounterProvider(2),
                    outbox,
                    projections::offer,
                    new SecureActionTokenIssuer(),
                    Clock.systemUTC(),
                    new TableActorConfig(16, 8, 8, 1),
                    aggregate(matchId),
                    new CounterState(0),
                    0);
            actor.start();
            TableProjection initial = projections.poll(2, TimeUnit.SECONDS);
            assertNotNull(initial);

            TableActionResult result = actor.submit(
                            PLAYER,
                            initial.authorizedActions().get(PLAYER).getFirst().token())
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals(TableActionCode.RULE_PACK_FAILURE, result.code());
            assertEquals(0, result.revision());
            TableProjection blocked = projections.poll(2, TimeUnit.SECONDS);
            assertNotNull(blocked);
            assertEquals(TableLifecycle.BLOCKED_RULE_PACK, blocked.lifecycle());
            assertEquals(0, outbox.health().unpersistedEvents());
            actor.close();
        }
    }

    private static TableAggregate aggregate(MatchId matchId) {
        RulePackRef ref = new RulePackRef(new RuleId("riichi"), "1.0.0", "0".repeat(64), 1);
        MatchBinding binding =
                new MatchBinding(
                        matchId,
                        ref,
                        new ProfileId("standard"),
                        "0".repeat(64),
                        Instant.parse("2026-08-08T00:00:00Z"));
        return new TableAggregate(
                TableId.random(),
                0,
                TableLifecycle.ACTIVE,
                List.of(
                        new TableParticipant(
                                PLAYER, ParticipantRole.PLAYER, Optional.of(new top.ellan.mahjong.spi.SeatId(0)))),
                Optional.of(binding),
                CompetitionRef.none());
    }

    private record CounterState(int value) implements RuleState {}

    private static final class CounterProvider implements RulePackProvider {
        private static final RulePackDescriptor DESCRIPTOR =
                new RulePackDescriptor(
                        new RuleId("riichi"),
                        "1.0.0",
                        SpiVersion.CURRENT,
                        ">=1.5.0",
                        1,
                        List.of(
                                new RuleProfileDescriptor(
                                        new ProfileId("standard"), "Standard", "{}")),
                        Set.of());
        private final int eventCount;

        private CounterProvider() {
            this(1);
        }

        private CounterProvider(int eventCount) {
            this.eventCount = eventCount;
        }

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
            CounterState current = (CounterState) state;
            return new RuleTransition(
                    new CounterState(current.value() + 1),
                    TransitionDisposition.ACCEPTED,
                    java.util.stream.IntStream.range(0, eventCount)
                            .mapToObj(index -> new RuleEvent(
                                    "incremented-" + index, action.payload()))
                            .toList(),
                    "accepted");
        }

        @Override
        public List<LegalAction> legalActions(RuleState state, PlayerId actor) {
            return List.of(
                    new LegalAction(
                            "increment",
                            new RuleAction("increment", new byte[] {1}),
                            ActionPresentation.actionRow("increment")));
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
                            Optional.of(new top.ellan.mahjong.spi.SeatId(0)),
                            Optional.empty()));
        }

        @Override
        public PrivateRuleView privateView(RuleState state, PlayerId viewer, long revision) {
            return new PrivateRuleView(
                    revision,
                    viewer,
                    new top.ellan.mahjong.spi.SeatId(0),
                    List.of(),
                    Map.of());
        }

        @Override
        public RuleStateSnapshot snapshot(RuleState state, long sequence) {
            int value = ((CounterState) state).value();
            return new RuleStateSnapshot(
                    1, sequence, ByteBuffer.allocate(4).putInt(value).array(), stateHash(state));
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
