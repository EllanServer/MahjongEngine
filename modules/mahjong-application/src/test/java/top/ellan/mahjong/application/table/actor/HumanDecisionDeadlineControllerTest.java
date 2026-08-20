package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.feedback.HumanDecisionWarning;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.SeatId;

class HumanDecisionDeadlineControllerTest {
    private static final PlayerId PLAYER = new PlayerId(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void preservesV15DiscardLadderAndManualReset() {
        RecordingScheduler scheduler = new RecordingScheduler();
        TableActorInbox inbox = new TableActorInbox(8);
        HumanDecisionDeadlineController controller =
                new HumanDecisionDeadlineController(scheduler, inbox, () -> {});
        Duration[] ladder = {
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(15),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10)
        };

        for (int revision = 0; revision < ladder.length; revision++) {
            controller.install(frame("discard"), revision, Set.of(), true);
            assertEquals(ladder[revision], scheduler.delay);
            scheduler.run();
            HumanDecisionTimeoutTrigger trigger =
                    (HumanDecisionTimeoutTrigger) inbox.pollIngress();
            assertNotNull(trigger);
            assertEquals(1, controller.accept(trigger, revision).size());
        }

        controller.acceptedManual(PLAYER, new RuleAction("discard", new byte[0]));
        controller.install(frame("discard"), ladder.length, Set.of(), true);
        assertEquals(Duration.ofSeconds(60), scheduler.delay);
    }

    @Test
    void schedulesOtherDecisionsOnceAndSkipsAutomatedPlayers() {
        RecordingScheduler scheduler = new RecordingScheduler();
        HumanDecisionDeadlineController controller = new HumanDecisionDeadlineController(
                scheduler, new TableActorInbox(8), () -> {});

        controller.install(frame("respond"), 0, Set.of(), true);
        assertEquals(Duration.ofSeconds(25), scheduler.delay);

        controller.install(frame("respond"), 1, Set.of(PLAYER), true);
        assertNull(scheduler.task);
    }

    @Test
    void nonDiscardDecisionsShareTheV15ExtraPoolWithinAHand() {
        RecordingScheduler scheduler = new RecordingScheduler();
        TableActorInbox inbox = new TableActorInbox(8);
        long[] clock = {0L};
        HumanDecisionDeadlineController controller = new HumanDecisionDeadlineController(
                scheduler, inbox, () -> {}, () -> clock[0]);

        // The first non-discard decision gets the 5-second base plus the whole 20-second pool.
        controller.install(frame("respond"), 0, Set.of(), true);
        assertEquals(Duration.ofSeconds(25), scheduler.delay);

        // Answering after 13 seconds charges the 8 seconds beyond the base, leaving 12.
        clock[0] = Duration.ofSeconds(13).toNanos();
        controller.acceptedManual(PLAYER, new RuleAction("respond", new byte[0]));
        controller.install(frame("respond"), 1, Set.of(), true);
        assertEquals(Duration.ofSeconds(17), scheduler.delay);

        // Answering inside the base costs nothing, so the pool still holds 12 seconds.
        clock[0] += Duration.ofSeconds(3).toNanos();
        controller.acceptedManual(PLAYER, new RuleAction("respond", new byte[0]));
        controller.install(frame("respond"), 2, Set.of(), true);
        assertEquals(Duration.ofSeconds(17), scheduler.delay);

        // Letting the window expire spends the remaining pool, leaving only the base.
        scheduler.run();
        HumanDecisionTimeoutTrigger trigger =
                (HumanDecisionTimeoutTrigger) inbox.pollIngress();
        assertNotNull(trigger);
        assertEquals(1, controller.accept(trigger, 2).size());
        controller.install(frame("respond"), 3, Set.of(), true);
        assertEquals(Duration.ofSeconds(5), scheduler.delay);

        // The pool is per hand, so the next hand starts from the full budget again.
        controller.clear();
        controller.install(frame("respond"), 4, Set.of(), true);
        assertEquals(Duration.ofSeconds(25), scheduler.delay);
    }

    @Test
    void aDiscardTurnNeverSpendsTheNonDiscardPool() {
        RecordingScheduler scheduler = new RecordingScheduler();
        long[] clock = {0L};
        HumanDecisionDeadlineController controller = new HumanDecisionDeadlineController(
                scheduler, new TableActorInbox(8), () -> {}, () -> clock[0]);

        controller.install(frame("discard"), 0, Set.of(), true);
        assertEquals(Duration.ofSeconds(60), scheduler.delay);
        clock[0] = Duration.ofSeconds(55).toNanos();
        controller.acceptedManual(PLAYER, new RuleAction("discard", new byte[0]));

        controller.install(frame("respond"), 1, Set.of(), true);
        assertEquals(Duration.ofSeconds(25), scheduler.delay);
    }

    @Test
    void aSeatIsWarnedBeforeItIsPlayedAutomatically() {
        RecordingScheduler scheduler = new RecordingScheduler();
        TableActorInbox inbox = new TableActorInbox(8);
        List<HumanDecisionWarning> warned = new java.util.ArrayList<>();
        HumanDecisionDeadlineController controller = new HumanDecisionDeadlineController(
                scheduler, inbox, () -> {}, warned::add);

        // A 60-second discard turn arms the deadline first, then a warning five seconds earlier.
        controller.install(frame("discard"), 0, Set.of(), true);
        assertEquals(List.of(Duration.ofSeconds(60), Duration.ofSeconds(55)), scheduler.delays);

        scheduler.run(1);
        assertEquals(1, warned.size());
        assertEquals(PLAYER, warned.getFirst().actor());
        assertTrue(warned.getFirst().discardTurn());
        assertEquals(5, warned.getFirst().remainingSeconds());

        // A non-discard decision warns too, and reports itself as a non-discard window.
        scheduler.reset();
        warned.clear();
        controller.install(frame("respond"), 1, Set.of(), true);
        assertEquals(List.of(Duration.ofSeconds(25), Duration.ofSeconds(20)), scheduler.delays);
        scheduler.run(1);
        assertEquals(1, warned.size());
        assertFalse(warned.getFirst().discardTurn());
    }

    @Test
    void aWindowNoLongerThanTheWarningLeadIsNotWarnedAbout() {
        RecordingScheduler scheduler = new RecordingScheduler();
        TableActorInbox inbox = new TableActorInbox(8);
        List<HumanDecisionWarning> warned = new java.util.ArrayList<>();
        HumanDecisionDeadlineController controller = new HumanDecisionDeadlineController(
                scheduler, inbox, () -> {}, warned::add);

        // Letting the first non-discard window expire spends the whole extra pool.
        controller.install(frame("respond"), 0, Set.of(), true);
        scheduler.run(0);
        HumanDecisionTimeoutTrigger trigger =
                (HumanDecisionTimeoutTrigger) inbox.pollIngress();
        assertNotNull(trigger);
        assertEquals(1, controller.accept(trigger, 0).size());

        // What remains is the bare five-second base, which leaves no room to warn ahead.
        scheduler.reset();
        warned.clear();
        controller.install(frame("respond"), 1, Set.of(), true);
        assertEquals(List.of(Duration.ofSeconds(5)), scheduler.delays);
        assertTrue(warned.isEmpty());
    }

    private static RuleFrame frame(String actionType) {
        RuleTablePresentation table = new RuleTablePresentation(
                4,
                new RuleWallPresentation(
                        List.of(17, 17, 17, 17), 0, RuleWallDirection.CLOCKWISE),
                6,
                Optional.empty(),
                Optional.of(new SeatId(0)),
                Optional.empty());
        PublicRuleView publicView =
                new PublicRuleView(0, "playing", List.of(), Map.of(), table);
        LegalAction action = new LegalAction(
                actionType + ":0",
                new RuleAction(actionType, new byte[0]),
                ActionPresentation.actionRow("action." + actionType));
        return new RuleFrame(
                publicView, Map.of(), Map.of(PLAYER, List.of(action)), Optional.empty());
    }

    /**
     * Records every timer. The controller arms the deadline before the warning, so {@code task} and
     * {@code delay} always describe the deadline while {@code delays} exposes both in order.
     */
    private static final class RecordingScheduler implements TaskScheduler {
        private final List<Runnable> tasks = new java.util.ArrayList<>();
        private final List<Duration> delays = new java.util.ArrayList<>();
        private Runnable task;
        private Duration delay;
        private boolean deadlineArmed;

        @Override
        public Cancellable schedule(Runnable scheduled, Duration scheduledDelay) {
            tasks.add(scheduled);
            delays.add(scheduledDelay);
            if (!deadlineArmed) {
                deadlineArmed = true;
                this.task = scheduled;
                this.delay = scheduledDelay;
            }
            return () -> {
                deadlineArmed = false;
                boolean pending = this.task == scheduled;
                if (pending) {
                    this.task = null;
                }
                return pending;
            };
        }

        /** Forgets earlier windows so arm order can be asserted for the next one. */
        private void reset() {
            tasks.clear();
            delays.clear();
            task = null;
            delay = null;
            deadlineArmed = false;
        }

        /** Runs the deadline timer. */
        private void run() {
            Runnable pending = task;
            task = null;
            deadlineArmed = false;
            assertNotNull(pending);
            pending.run();
        }

        /** Runs one recorded timer by arm order: 0 is the deadline, 1 the warning. */
        private void run(int index) {
            deadlineArmed = false;
            assertTrue(index < tasks.size());
            tasks.get(index).run();
        }
    }
}
