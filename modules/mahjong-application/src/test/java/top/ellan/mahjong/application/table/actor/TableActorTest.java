package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
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
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.feedback.TableCueBatch;
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
import top.ellan.mahjong.domain.match.CompetitionRef;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AutomatedPlayerActions;
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
import top.ellan.mahjong.spi.RulePresentationCue;
import top.ellan.mahjong.spi.RulePresentationCueType;
import top.ellan.mahjong.spi.RuleProfileDescriptor;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.ScheduledRuleAction;
import top.ellan.mahjong.spi.SpiVersion;
import top.ellan.mahjong.spi.TransitionDisposition;

class TableActorTest {
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void scheduledRuleActionReturnsThroughActorAndCommitsOneRevision() throws Exception {
        ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                new ThreadPoolExecutor.AbortPolicy());
        try (dispatcher;
                FairRuleExecutor rules = new FairRuleExecutor(1, 8, "scheduled-rule-test")) {
            MatchId matchId = MatchId.random();
            TaskScheduler neverRuns = (task, delay) -> () -> true;
            PersistenceOutbox outbox = new PersistenceOutbox(
                    matchId, 0, new ImmediateStore(), neverRuns, Clock.systemUTC());
            ManualScheduler deadlines = new ManualScheduler();
            ArrayBlockingQueue<TableProjection> projections = new ArrayBlockingQueue<>(4);
            TableActor actor = new TableActor(
                    dispatcher,
                    rules,
                    new CounterProvider(1, true),
                    outbox,
                    deadlines,
                    projections::offer,
                    TablePresentationCuePort.NONE,
                    TableOpeningPresentationPort.NONE,
                    true,
                    new SecureActionTokenIssuer(),
                    Clock.systemUTC(),
                    TableActorConfig.DEFAULT,
                    aggregate(matchId),
                    new CounterState(0),
                    0);
            actor.start();

            assertEquals(0, projections.poll(2, TimeUnit.SECONDS).revision());
            deadlines.awaitScheduled();
            assertEquals(1, deadlines.pendingCount());
            deadlines.runNext();
            assertEquals(1, projections.poll(2, TimeUnit.SECONDS).revision());
            actor.close();
        }
    }

    @Test
    void trusteeControlReframesOnceAndRunsThroughTheRevisionBoundTimer() throws Exception {
        ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
        try (dispatcher; FairRuleExecutor rules = new FairRuleExecutor(1, 8, "trustee-rule-test")) {
            MatchId matchId = MatchId.random();
            TaskScheduler neverRuns = (task, delay) -> () -> true;
            PersistenceOutbox outbox = new PersistenceOutbox(
                    matchId, 0, new ImmediateStore(), neverRuns, Clock.systemUTC());
            ManualScheduler deadlines = new ManualScheduler();
            ArrayBlockingQueue<TableProjection> projections = new ArrayBlockingQueue<>(4);
            TableActor actor = new TableActor(
                    dispatcher,
                    rules,
                    new CounterProvider(),
                    outbox,
                    deadlines,
                    projections::offer,
                    TablePresentationCuePort.NONE,
                    TableOpeningPresentationPort.NONE,
                    true,
                    new SecureActionTokenIssuer(),
                    Clock.systemUTC(),
                    TableActorConfig.DEFAULT,
                    aggregate(matchId),
                    new CounterState(0),
                    0);
            actor.start();
            assertEquals(0, projections.poll(2, TimeUnit.SECONDS).revision());

            TableActionResult enabled = actor.setAutomated(PLAYER, true)
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(TableActionCode.ACCEPTED_MEMORY, enabled.code());
            assertEquals(0, projections.poll(2, TimeUnit.SECONDS).revision());
            deadlines.awaitScheduled();
            assertEquals(1, deadlines.pendingCount());

            deadlines.runNext();
            assertEquals(1, projections.poll(2, TimeUnit.SECONDS).revision());
            actor.close();
        }
    }

    @Test
    void fixedBotStartsAutomatedWithoutReceivingAPlayerPrivateProjection() throws Exception {
        ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
        try (dispatcher; FairRuleExecutor rules = new FairRuleExecutor(1, 8, "bot-rule-test")) {
            MatchId matchId = MatchId.random();
            TaskScheduler neverRuns = (task, delay) -> () -> true;
            PersistenceOutbox outbox = new PersistenceOutbox(
                    matchId, 0, new ImmediateStore(), neverRuns, Clock.systemUTC());
            ManualScheduler deadlines = new ManualScheduler();
            ArrayBlockingQueue<TableProjection> projections = new ArrayBlockingQueue<>(4);
            TableActor actor = new TableActor(
                    dispatcher,
                    rules,
                    new CounterProvider(),
                    outbox,
                    deadlines,
                    projections::offer,
                    TablePresentationCuePort.NONE,
                    TableOpeningPresentationPort.NONE,
                    true,
                    new SecureActionTokenIssuer(),
                    Clock.systemUTC(),
                    TableActorConfig.DEFAULT,
                    aggregate(matchId, ParticipantRole.BOT),
                    new CounterState(0),
                    0);
            actor.start();

            TableProjection initial = projections.poll(2, TimeUnit.SECONDS);
            assertNotNull(initial);
            assertFalse(initial.authorizedActions().containsKey(PLAYER));
            deadlines.awaitScheduled();
            deadlines.runNext();
            assertEquals(1, projections.poll(2, TimeUnit.SECONDS).revision());
            actor.close();
        }
    }

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
                            deadlines,
                            projections::offer,
                            TablePresentationCuePort.NONE,
                            TableOpeningPresentationPort.NONE,
                            true,
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
    void transientCuesPublishOnceAfterCommitAndCannotBreakTheTable() throws Exception {
        ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                new ThreadPoolExecutor.AbortPolicy());
        try (dispatcher; FairRuleExecutor rules = new FairRuleExecutor(1, 8, "cue-rule-test")) {
            MatchId matchId = MatchId.random();
            TaskScheduler neverRuns = (task, delay) -> () -> true;
            PersistenceOutbox outbox = new PersistenceOutbox(
                    matchId, 0, new ImmediateStore(), neverRuns, Clock.systemUTC());
            ArrayBlockingQueue<TableProjection> projections = new ArrayBlockingQueue<>(4);
            ArrayBlockingQueue<TableCueBatch> cues = new ArrayBlockingQueue<>(2);
            TableActor actor = new TableActor(
                    dispatcher,
                    rules,
                    new CounterProvider(),
                    outbox,
                    neverRuns,
                    projections::offer,
                    batch -> {
                        cues.offer(batch);
                        throw new IllegalStateException("sound backend unavailable");
                    },
                    TableOpeningPresentationPort.NONE,
                    true,
                    new SecureActionTokenIssuer(),
                    Clock.systemUTC(),
                    TableActorConfig.DEFAULT,
                    aggregate(matchId),
                    new CounterState(0),
                    0);
            actor.start();
            TableProjection initial = projections.poll(2, TimeUnit.SECONDS);
            assertNotNull(initial);

            TableActionResult accepted = actor.submit(
                            PLAYER,
                            initial.authorizedActions().get(PLAYER).getFirst().token())
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            TableCueBatch published = cues.poll(2, TimeUnit.SECONDS);

            assertEquals(TableActionCode.ACCEPTED_MEMORY, accepted.code());
            assertNotNull(published);
            assertEquals(1, published.revision());
            assertEquals(
                    List.of(RulePresentationCue.broadcast(
                            RulePresentationCueType.TILE_DISCARD)),
                    published.cues());
            assertEquals(TableLifecycle.ACTIVE, actor.snapshot().lifecycle());
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
                            neverRuns,
                            ignored -> {},
                            TablePresentationCuePort.NONE,
                            TableOpeningPresentationPort.NONE,
                            true,
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
                            (task, delay) -> () -> true,
                            projections::offer,
                            TablePresentationCuePort.NONE,
                            TableOpeningPresentationPort.NONE,
                            true,
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
                    (task, delay) -> () -> true,
                    projections::offer,
                    TablePresentationCuePort.NONE,
                    TableOpeningPresentationPort.NONE,
                    true,
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
        return aggregate(matchId, ParticipantRole.PLAYER);
    }

    private static TableAggregate aggregate(MatchId matchId, ParticipantRole role) {
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
                                PLAYER, role, Optional.of(new top.ellan.mahjong.spi.SeatId(0)))),
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
        private final boolean scheduleInitialAction;

        private CounterProvider() {
            this(1, false);
        }

        private CounterProvider(int eventCount) {
            this(eventCount, false);
        }

        private CounterProvider(int eventCount, boolean scheduleInitialAction) {
            this.eventCount = eventCount;
            this.scheduleInitialAction = scheduleInitialAction;
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
                    List.of(RulePresentationCue.broadcast(
                            RulePresentationCueType.TILE_DISCARD)),
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
        public Optional<ScheduledRuleAction> scheduledAction(RuleState state) {
            if (!scheduleInitialAction || ((CounterState) state).value() != 0) {
                return Optional.empty();
            }
            return Optional.of(new ScheduledRuleAction(
                    PLAYER,
                    new RuleAction("increment", new byte[] {1}),
                    Duration.ZERO,
                    "initial-timeout"));
        }

        @Override
        public Optional<ScheduledRuleAction> automatedAction(
                RuleState state, List<AutomatedPlayerActions> candidates) {
            if (candidates.isEmpty() || ((CounterState) state).value() != 0) {
                return Optional.empty();
            }
            AutomatedPlayerActions candidate = candidates.getFirst();
            if (candidate.legalActions().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ScheduledRuleAction(
                    candidate.actor(),
                    candidate.legalActions().getFirst().action(),
                    Duration.ZERO,
                    "automation.counter"));
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

    private static final class ManualScheduler implements TaskScheduler {
        private final ArrayDeque<Scheduled> tasks = new ArrayDeque<>();
        private final CountDownLatch scheduled = new CountDownLatch(1);

        @Override
        public top.ellan.mahjong.application.concurrent.Cancellable schedule(
                Runnable task, Duration delay) {
            Scheduled scheduled = new Scheduled(task);
            tasks.addLast(scheduled);
            this.scheduled.countDown();
            return () -> {
                scheduled.cancelled = true;
                return tasks.remove(scheduled);
            };
        }

        int pendingCount() {
            return tasks.size();
        }

        void awaitScheduled() throws InterruptedException {
            if (!scheduled.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("scheduled rule action was not registered");
            }
        }

        void runNext() {
            Scheduled scheduled = tasks.removeFirst();
            if (!scheduled.cancelled) {
                scheduled.task.run();
            }
        }

        private static final class Scheduled {
            private final Runnable task;
            private boolean cancelled;

            private Scheduled(Runnable task) {
                this.task = task;
            }
        }
    }
}
