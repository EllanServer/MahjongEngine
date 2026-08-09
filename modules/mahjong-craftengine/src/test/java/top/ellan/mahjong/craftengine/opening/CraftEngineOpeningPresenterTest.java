package top.ellan.mahjong.craftengine.opening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.opening.TableOpeningBatch;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.RuleDiceRoll;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleOpeningPresentation;
import top.ellan.mahjong.spi.SeatId;

class CraftEngineOpeningPresenterTest {
    @Test
    void twoPhysicalRollsUseBoundedCeAssetSwapsAndFinishByRemovingTheOverlay() {
        ManualScheduler scheduler = new ManualScheduler();
        ArrayList<OverlayCall> calls = new ArrayList<>();
        CraftEngineOpeningPresenter presenter = new CraftEngineOpeningPresenter(
                scheduler,
                (table, generation, managed, desired) -> calls.add(
                        new OverlayCall(table, generation, managed, desired)),
                new CraftEngineOpeningAnimationConfig(
                        "mahjongpaper:dice_face_",
                        3,
                        Duration.ofSeconds(1),
                        Duration.ofMillis(600),
                        0.22D,
                        0.62D));
        TableId table = TableId.random();
        RuleOpeningPresentation opening = new RuleOpeningPresentation(
                3,
                List.of(
                        new RuleDiceRoll(List.of(2, 5)),
                        new RuleDiceRoll(List.of(3, 4))),
                new SeatId(2),
                14);

        presenter.present(new TableOpeningBatch(table, new RuleId("mcr"), 0, opening));
        assertEquals(1, calls.size());
        assertEquals(2, calls.getFirst().desired().size());

        scheduler.runAll();

        assertEquals(9, calls.size());
        assertEquals(4, calls.get(7).desired().size());
        assertTrue(calls.get(7).desired().stream()
                .allMatch(node -> node.assetId().matches("mahjongpaper:dice_face_[1-6]")));
        assertTrue(calls.getLast().desired().isEmpty());
        assertEquals(4, calls.getLast().managed().size());
    }

    private record OverlayCall(
            TableId table,
            long generation,
            List<SceneNodeId> managed,
            List<FurnitureNode> desired) {
        private OverlayCall {
            managed = List.copyOf(managed);
            desired = List.copyOf(desired);
        }
    }

    private static final class ManualScheduler implements TaskScheduler {
        private final ArrayList<Scheduled> scheduled = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable task, Duration delay) {
            Scheduled value = new Scheduled(task, delay);
            scheduled.add(value);
            return () -> {
                if (value.cancelled) {
                    return false;
                }
                value.cancelled = true;
                return true;
            };
        }

        void runAll() {
            scheduled.sort(Comparator.comparing(Scheduled::delay));
            for (Scheduled value : List.copyOf(scheduled)) {
                if (!value.cancelled) {
                    value.task.run();
                }
            }
        }

        private static final class Scheduled {
            private final Runnable task;
            private final Duration delay;
            private boolean cancelled;

            private Scheduled(Runnable task, Duration delay) {
                this.task = task;
                this.delay = delay;
            }

            private Duration delay() {
                return delay;
            }
        }
    }
}
