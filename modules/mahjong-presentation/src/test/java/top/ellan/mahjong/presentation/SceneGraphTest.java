package top.ellan.mahjong.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.TableProjection;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

class SceneGraphTest {
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void mapperKeepsSecretFacesOutOfWorldBackedNodes() {
        DefaultTableSceneMapper mapper =
                new DefaultTableSceneMapper(
                        new RadialTableLayout(0.08), "mahjong:tile/back");
        SceneGraph graph = mapper.map(projection(TableId.random(), 4, "playing"));

        assertTrue(
                graph.nodes().values().stream()
                        .filter(SceneNode::worldBacked)
                        .allMatch(node -> node.visibility().isPublic()));
        assertTrue(
                graph.nodes().values().stream()
                        .filter(node -> node instanceof FurnitureNode)
                        .map(node -> (FurnitureNode) node)
                        .anyMatch(node -> node.assetId().equals("mahjong:tile/back")));
        assertEquals(
                1,
                graph.nodes().values().stream()
                        .filter(node -> node instanceof InteractionNode)
                        .count());
        assertTrue(
                graph.nodes().values().stream()
                        .filter(node -> node instanceof PrivateItemNode)
                        .map(node -> (PrivateItemNode) node)
                        .anyMatch(node -> node.visualId().value().equals("tile/red-five")));
        assertEquals(1, graph.interactionBindings().size());
        assertEquals(4, graph.interactionBindings().getFirst().actionToken().revision());
    }

    @Test
    void privateFurnitureIsRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FurnitureNode(
                                new SceneNodeId("secret"),
                                SceneVisibility.privateTo(PLAYER),
                                "mahjong:tile/red-five",
                                new SceneTransform(0, 0, 0, 0, 0, 0, 1)));
    }

    @Test
    void rulePackNotationsMapToSharedCraftEngineAssets() {
        assertEquals("m5_red", DefaultTableSceneMapper.normalizeTileName("5mr"));
        assertEquals("p3", DefaultTableSceneMapper.normalizeTileName("3p"));
        assertEquals("green_dragon", DefaultTableSceneMapper.normalizeTileName("6z"));
        assertEquals("white_dragon", DefaultTableSceneMapper.normalizeTileName("white_dragon"));
    }

    @Test
    void pointSticksUseCraftEngineConfiguredFurnitureAssets() {
        DefaultTableSceneMapper mapper =
                new DefaultTableSceneMapper(
                        new RadialTableLayout(0.08), "mahjongpaper:tile_standing_back");
        RuleViewTile pointStick =
                new RuleViewTile(
                        new TileInstanceId(10_001),
                        new TileVisualId("riichi:stick/p1000"),
                        Optional.of(new SeatId(2)),
                        RuleViewZone.POINT_STICK,
                        0,
                        true);
        TableProjection base = projection(TableId.random(), 1, "playing");
        TableProjection withStick =
                new TableProjection(
                        base.tableId(),
                        base.revision(),
                        base.lifecycle(),
                        new PublicRuleView(1, "playing", List.of(pointStick), Map.of()),
                        base.privateViews(),
                        base.authorizedActions());

        SceneGraph graph = mapper.map(withStick);

        assertTrue(
                graph.nodes().values().stream()
                        .filter(FurnitureNode.class::isInstance)
                        .map(FurnitureNode.class::cast)
                        .anyMatch(node -> node.assetId().equals("mahjongpaper:stick_p1000")));
    }

    @Test
    void differTouchesOnlyChangedStableNodes() {
        DefaultTableSceneMapper mapper =
                new DefaultTableSceneMapper(
                        new RadialTableLayout(0.08), "mahjong:tile/back");
        TableId table = TableId.random();
        SceneGraph before = mapper.map(projection(table, 1, "playing"));
        SceneGraph after = mapper.map(projection(table, 2, "settlement"));
        SceneDiff diff = new SceneGraphDiffer().diff(before, after);

        assertFalse(diff.upserts().isEmpty());
        assertTrue(diff.upserts().stream().anyMatch(node -> node instanceof HudNode));
        assertTrue(diff.upserts().stream().noneMatch(node -> node instanceof InteractionNode));
        assertTrue(diff.mutationCount() < after.nodes().size() + before.nodes().size());
    }

    @Test
    void mapperDoesNotPlaceUnusedCraftEngineHitboxes() {
        DefaultTableSceneMapper mapper =
                new DefaultTableSceneMapper(
                        new RadialTableLayout(0.08), "mahjong:tile/back");
        TableProjection base = projection(TableId.random(), 1, "playing");
        TableProjection withoutActions =
                new TableProjection(
                        base.tableId(),
                        base.revision(),
                        base.lifecycle(),
                        base.publicView(),
                        base.privateViews(),
                        Map.of());

        SceneGraph graph = mapper.map(withoutActions);

        assertTrue(
                graph.nodes().values().stream()
                        .noneMatch(InteractionNode.class::isInstance));
        assertTrue(graph.interactionBindings().isEmpty());
    }

    @Test
    void latestProjectorDropsSupersededFramesBeforeMapping() {
        ManualExecutor executor = new ManualExecutor();
        List<SceneDiff> submitted = new ArrayList<>();
        List<Long> mapped = new ArrayList<>();
        LatestSceneProjector projector =
                new LatestSceneProjector(
                        executor,
                        projection -> {
                            mapped.add(projection.revision());
                            return SceneGraph.empty(
                                    projection.tableId(), projection.revision());
                        },
                        submitted::add,
                        new SceneGraphDiffer());
        TableId table = TableId.random();
        projector.publish(projection(table, 1, "one"));
        projector.publish(projection(table, 2, "two"));
        projector.publish(projection(table, 3, "three"));

        executor.runAll();

        assertEquals(List.of(3L), mapped);
        assertEquals(1, submitted.size());
        assertEquals(3, submitted.getFirst().toRevision());
    }

    private static TableProjection projection(TableId tableId, long revision, String phase) {
        RuleViewTile publicBack =
                new RuleViewTile(
                        new TileInstanceId(1),
                        new TileVisualId("tile/red-five"),
                        Optional.of(new SeatId(0)),
                        RuleViewZone.HAND,
                        0,
                        false);
        RuleViewTile privateFace =
                new RuleViewTile(
                        new TileInstanceId(1),
                        new TileVisualId("tile/red-five"),
                        Optional.of(new SeatId(0)),
                        RuleViewZone.HAND,
                        0,
                        true);
        ActionToken token = new ActionToken(UUID.randomUUID(), PLAYER, revision);
        AuthorizedAction action =
                new AuthorizedAction(
                        token,
                        new LegalAction(
                                "discard.1",
                                new RuleAction("discard", new byte[] {1}),
                                Map.of()));
        return new TableProjection(
                tableId,
                revision,
                TableLifecycle.ACTIVE,
                new PublicRuleView(revision, phase, List.of(publicBack), Map.of()),
                Map.of(
                        PLAYER,
                        new PrivateRuleView(
                                revision, PLAYER, List.of(privateFace), Map.of())),
                Map.of(PLAYER, List.of(action)));
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }
}
