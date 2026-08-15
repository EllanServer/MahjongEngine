package top.ellan.mahjong.presentation.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.presentation.port.SceneBackendPort;
import top.ellan.mahjong.presentation.scene.SceneDiff;
import top.ellan.mahjong.presentation.scene.SceneGraph;
import top.ellan.mahjong.presentation.scene.SceneGraphDiffer;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.SeatId;

class LatestSceneProjectorTest {
    private static final TableId TABLE = TableId.random();

    @Test
    void refreshForcesTheNextPublishToReEmitTheWholeScene() {
        RecordingBackend backend = new RecordingBackend();
        LatestSceneProjector projector =
                new LatestSceneProjector(
                        Runnable::run,
                        ignored -> scene(TABLE, ignored.revision()),
                        backend,
                        new SceneGraphDiffer(),
                        (task, delay) -> () -> true);

        projector.publish(projection(TABLE, 5));
        assertEquals(1, backend.diffs.size());
        assertEquals(2, backend.diffs.get(0).upserts().size());

        projector.publish(projection(TABLE, 5));
        assertEquals(2, backend.diffs.size());
        assertEquals(0, backend.diffs.get(1).upserts().size());

        projector.refresh(TABLE);
        projector.publish(projection(TABLE, 5));
        assertEquals(3, backend.diffs.size());
        assertEquals(2, backend.diffs.get(2).upserts().size());

        Optional<LatestSceneProjector.AppliedScene> applied = projector.appliedScene(TABLE);
        assertEquals(5, applied.orElseThrow().revision());
        assertEquals(2, applied.orElseThrow().nodes());
        assertEquals(0, applied.orElseThrow().interactionBindings());
    }

    @Test
    void removeClearsTheSceneAndDiagnostics() {
        RecordingBackend backend = new RecordingBackend();
        LatestSceneProjector projector =
                new LatestSceneProjector(
                        Runnable::run,
                        ignored -> scene(TABLE, ignored.revision()),
                        backend,
                        new SceneGraphDiffer(),
                        (task, delay) -> () -> true);

        projector.publish(projection(TABLE, 3));
        assertTrue(projector.appliedScene(TABLE).isPresent());

        projector.remove(TABLE);

        assertEquals(2, backend.diffs.size());
        assertEquals(2, backend.diffs.get(1).removals().size());
        assertTrue(projector.appliedScene(TABLE).isEmpty());
    }

    @Test
    void unknownTablesHaveNoSceneOrRefreshEffect() {
        LatestSceneProjector projector =
                new LatestSceneProjector(
                        Runnable::run,
                        ignored -> scene(TABLE, ignored.revision()),
                        new RecordingBackend(),
                        new SceneGraphDiffer(),
                        (task, delay) -> () -> true);

        TableId unknown = TableId.random();
        assertTrue(projector.appliedScene(unknown).isEmpty());
        projector.refresh(unknown);
        assertTrue(projector.appliedScene(unknown).isEmpty());
    }

    private static SceneGraph scene(TableId tableId, long revision) {
        SceneTransform transform = new SceneTransform(0, 0, 0, 0, 0, 0, 1);
        Map<SceneNodeId, SceneNode> nodes = new LinkedHashMap<>();
        nodes.put(
                new SceneNodeId("tile:a"),
                new FurnitureNode(
                        new SceneNodeId("tile:a"),
                        SceneVisibility.publicToAll(),
                        "mahjongpaper:tile_standing_m1",
                        transform));
        nodes.put(
                new SceneNodeId("tile:b"),
                new FurnitureNode(
                        new SceneNodeId("tile:b"),
                        SceneVisibility.publicToAll(),
                        "mahjongpaper:tile_standing_m1",
                        transform));
        return new SceneGraph(tableId, revision, nodes, List.of());
    }

    private static TableProjection projection(TableId tableId, long revision) {
        return new TableProjection(
                tableId,
                revision,
                TableLifecycle.ACTIVE,
                new PublicRuleView(
                        revision,
                        "settlement",
                        List.of(),
                        Map.of(),
                        new RuleTablePresentation(
                                4,
                                new RuleWallPresentation(
                                        List.of(17, 17, 17, 17), 0, RuleWallDirection.CLOCKWISE),
                                6,
                                Optional.of(new SeatId(0)),
                                Optional.of(new SeatId(0)),
                                Optional.empty())),
                Map.of(),
                Map.of());
    }

    private static final class RecordingBackend implements SceneBackendPort {
        final List<SceneDiff> diffs = new ArrayList<>();

        @Override
        public void submit(SceneDiff diff) {
            diffs.add(diff);
        }
    }
}
