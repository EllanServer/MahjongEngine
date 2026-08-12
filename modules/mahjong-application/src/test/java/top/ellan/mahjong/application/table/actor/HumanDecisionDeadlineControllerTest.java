package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
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

    private static final class RecordingScheduler implements TaskScheduler {
        private Runnable task;
        private Duration delay;

        @Override
        public Cancellable schedule(Runnable task, Duration delay) {
            this.task = task;
            this.delay = delay;
            return () -> {
                boolean pending = this.task == task;
                if (pending) {
                    this.task = null;
                }
                return pending;
            };
        }

        private void run() {
            Runnable pending = task;
            task = null;
            assertNotNull(pending);
            pending.run();
        }
    }
}
