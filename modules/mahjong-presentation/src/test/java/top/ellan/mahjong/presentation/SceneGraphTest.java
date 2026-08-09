package top.ellan.mahjong.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.TableProjection;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleTilePresentation;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

class SceneGraphTest {
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final SeatId SEAT_ZERO = new SeatId(0);
    private static final TableSceneAssets ASSETS =
            new TableSceneAssets(
                    "mahjongpaper:table_visual",
                    "mahjongpaper:tile_standing_face_down_back",
                    "mahjongpaper:tile_flat_face_down_back",
                    "mahjongpaper:hand_tile_hitbox",
                    "mahjongpaper:action_button_hitbox");
    private static final TableGeometry GEOMETRY =
            new TableGeometry(
                    0.1125D,
                    0.15D,
                    0.075D,
                    0.0025D,
                    0.52D,
                    1.225D,
                    1.0D,
                    1.4375D,
                    0.06D,
                    0.55D,
                    0.24D,
                    0.34D,
                    18,
                    48,
                    24,
                    64,
                    32,
                    64);

    @Test
    void mapperKeepsSecretFacesOutOfWorldBackedNodes() {
        SceneGraph graph = mapper().map(projection(TableId.random(), 4, "playing"));

        assertTrue(
                graph.nodes().values().stream()
                        .filter(SceneNode::worldBacked)
                        .allMatch(node -> node.visibility().isPublic()));
        assertTrue(
                graph.nodes().values().stream()
                        .filter(FurnitureNode.class::isInstance)
                        .map(FurnitureNode.class::cast)
                        .anyMatch(node -> node.assetId()
                                .equals("mahjongpaper:tile_standing_face_down_back")));
        assertEquals(
                1,
                graph.nodes().values().stream()
                        .filter(InteractionNode.class::isInstance)
                        .count());
        assertTrue(
                graph.nodes().values().stream()
                        .filter(PrivateItemNode.class::isInstance)
                        .map(PrivateItemNode.class::cast)
                        .anyMatch(node -> node.visualId().value().equals("riichi:tile/m5_red")));
        assertEquals(1, graph.interactionBindings().size());
        assertEquals(4, graph.interactionBindings().getFirst().actionToken().revision());
    }

    @Test
    void privateFaceSitsJustOutsideTheReusablePublicBack() {
        SceneGraph graph = mapper().map(projection(TableId.random(), 4, "playing"));
        FurnitureNode publicBack = (FurnitureNode) graph.nodes().get(
                new SceneNodeId("tile/public/1"));
        String viewer = PLAYER.toString().replace("-", "");
        PrivateItemNode privateFace = (PrivateItemNode) graph.nodes().get(
                new SceneNodeId("tile/private/" + viewer + "/1"));

        assertEquals(publicBack.transform().x(), privateFace.transform().x());
        assertTrue(privateFace.transform().z() > publicBack.transform().z());
        assertTrue(privateFace.transform().z() - publicBack.transform().z() < 0.01D);
    }

    @Test
    void publiclyRevealedHandDoesNotCreateARedundantPrivateOverlay() {
        TableProjection base = projection(TableId.random(), 4, "settlement");
        RuleViewTile revealed =
                tile(1, "riichi:tile/m5_red", RuleViewZone.HAND, 0, true, 0);
        TableProjection projection = new TableProjection(
                base.tableId(),
                base.revision(),
                base.lifecycle(),
                new PublicRuleView(
                        base.revision(),
                        "settlement",
                        List.of(revealed),
                        Map.of(),
                        table(136)),
                base.privateViews(),
                Map.of());

        SceneGraph graph = mapper().map(projection);

        assertTrue(graph.nodes().values().stream()
                .noneMatch(PrivateItemNode.class::isInstance));
        assertTrue(graph.nodes().values().stream()
                .filter(FurnitureNode.class::isInstance)
                .map(FurnitureNode.class::cast)
                .anyMatch(node -> node.assetId()
                        .equals("mahjongpaper:tile_standing_m5_red")));
    }

    @Test
    void privateFurnitureIsRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FurnitureNode(
                                new SceneNodeId("secret"),
                                SceneVisibility.privateTo(PLAYER),
                                "mahjongpaper:tile_standing_m5_red",
                                new SceneTransform(0, 0, 0, 0, 0, 0, 1)));
    }

    @Test
    void pointSticksUseCraftEngineConfiguredFurnitureAssets() {
        RuleViewTile pointStick =
                tile(10_001, "riichi:stick/p1000", RuleViewZone.POINT_STICK, 0, true, 0);
        TableProjection base = projection(TableId.random(), 1, "playing");
        TableProjection withStick =
                new TableProjection(
                        base.tableId(),
                        base.revision(),
                        base.lifecycle(),
                        new PublicRuleView(
                                1,
                                "playing",
                                List.of(pointStick),
                                Map.of(),
                                table(136)),
                        base.privateViews(),
                        base.authorizedActions());

        SceneGraph graph = mapper().map(withStick);

        assertTrue(
                graph.nodes().values().stream()
                        .filter(FurnitureNode.class::isInstance)
                        .map(FurnitureNode.class::cast)
                        .anyMatch(node -> node.assetId().equals("mahjongpaper:stick_p1000")));
    }

    @Test
    void directHandActionReusesThePrivateTileTransform() {
        SceneGraph graph = mapper().map(projection(TableId.random(), 7, "playing"));
        PrivateItemNode privateTile = graph.nodes().values().stream()
                .filter(PrivateItemNode.class::isInstance)
                .map(PrivateItemNode.class::cast)
                .findFirst()
                .orElseThrow();
        InteractionNode interaction = graph.nodes().values().stream()
                .filter(InteractionNode.class::isInstance)
                .map(InteractionNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(privateTile.transform(), interaction.transform());
        assertEquals("mahjongpaper:hand_tile_hitbox", interaction.assetId());
    }

    @Test
    void rowActionUsesDedicatedCraftEngineHitbox() {
        TableProjection base = projection(TableId.random(), 2, "playing");
        AuthorizedAction action =
                action(2, "win", ActionPresentation.actionRow("action.win"));
        TableProjection rowAction =
                new TableProjection(
                        base.tableId(),
                        base.revision(),
                        base.lifecycle(),
                        base.publicView(),
                        base.privateViews(),
                        Map.of(PLAYER, List.of(action)));

        InteractionNode interaction = mapper().map(rowAction).nodes().values().stream()
                .filter(InteractionNode.class::isInstance)
                .map(InteractionNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("mahjongpaper:action_button_hitbox", interaction.assetId());
    }

    @Test
    void actionRowsPreserveTheRulePacksDeterministicOrder() {
        TableProjection base = projection(TableId.random(), 2, "playing");
        AuthorizedAction first =
                action(2, "z_first", ActionPresentation.actionRow("action.first"));
        AuthorizedAction second =
                action(2, "a_second", ActionPresentation.actionRow("action.second"));
        TableProjection ordered = new TableProjection(
                base.tableId(),
                base.revision(),
                base.lifecycle(),
                base.publicView(),
                base.privateViews(),
                Map.of(PLAYER, List.of(first, second)));

        SceneGraph graph = mapper().map(ordered);
        String player = PLAYER.toString().replace("-", "");
        InteractionNode firstNode = (InteractionNode) graph.nodes().get(
                new SceneNodeId("interaction/action/" + player + "/z_first"));
        InteractionNode secondNode = (InteractionNode) graph.nodes().get(
                new SceneNodeId("interaction/action/" + player + "/a_second"));

        assertTrue(firstNode.transform().x() < secondNode.transform().x());
    }

    @Test
    void duplicateDirectActionsForOnePhysicalTileAreRejected() {
        TableProjection base = projection(TableId.random(), 2, "playing");
        ActionPresentation direct =
                ActionPresentation.handTile("action.discard", new TileInstanceId(1));
        TableProjection duplicate =
                new TableProjection(
                        base.tableId(),
                        base.revision(),
                        base.lifecycle(),
                        base.publicView(),
                        base.privateViews(),
                        Map.of(
                                PLAYER,
                                List.of(
                                        action(2, "discard.one", direct),
                                        action(2, "discard.two", direct))));

        assertThrows(IllegalArgumentException.class, () -> mapper().map(duplicate));
    }

    @Test
    void stableWallSlotsPreventUnchangedTilesFromMovingAfterADraw() {
        TableId tableId = TableId.random();
        RuleViewTile first = wallTile(101, 0);
        RuleViewTile drawn = wallTile(102, 1);
        RuleViewTile third = wallTile(103, 2);
        SceneGraph before = mapper().map(publicProjection(
                tableId, 1, List.of(first, drawn, third), table(136)));
        SceneGraph after = mapper().map(publicProjection(
                tableId, 2, List.of(first, third), table(136)));
        SceneDiff diff = new SceneGraphDiffer().diff(before, after);

        assertEquals(List.of(new SceneNodeId("tile/public/102")), diff.removals());
        assertTrue(diff.upserts().stream()
                .noneMatch(node -> node.id().value().startsWith("tile/public/")));
    }

    @Test
    void wallGeometryUsesFourSidesAndTwoPhysicalLayers() {
        ResolvedTableLayout layout =
                new UniversalTableLayout(GEOMETRY).resolve(table(136));
        SceneTransform seatZeroTop = layout.tile(wallTile(1, 0), 136);
        SceneTransform seatZeroBottom = layout.tile(wallTile(2, 1), 136);
        SceneTransform seatOne = layout.tile(wallTile(3, 34), 136);
        SceneTransform seatTwo = layout.tile(wallTile(4, 68), 136);
        SceneTransform seatThree = layout.tile(wallTile(5, 102), 136);

        assertNotEquals(seatZeroTop.y(), seatZeroBottom.y());
        assertEquals(1.0D, seatZeroTop.z(), 0.000_001D);
        assertEquals(1.0D, seatOne.x(), 0.000_001D);
        assertEquals(-1.0D, seatTwo.z(), 0.000_001D);
        assertEquals(-1.0D, seatThree.x(), 0.000_001D);
    }

    @Test
    void clockwiseWallSlotsRemainAdjacentWhenCrossingEveryCorner() {
        ResolvedTableLayout layout =
                new UniversalTableLayout(GEOMETRY).resolve(table(136));
        SceneTransform sideZeroEnd = layout.tile(wallTile(1, 32), 136);
        SceneTransform sideOneStart = layout.tile(wallTile(2, 34), 136);
        SceneTransform sideOneEnd = layout.tile(wallTile(3, 66), 136);
        SceneTransform sideTwoStart = layout.tile(wallTile(4, 68), 136);
        SceneTransform sideTwoEnd = layout.tile(wallTile(5, 100), 136);
        SceneTransform sideThreeStart = layout.tile(wallTile(6, 102), 136);
        SceneTransform sideThreeEnd = layout.tile(wallTile(7, 134), 136);
        SceneTransform sideZeroStart = layout.tile(wallTile(8, 0), 136);

        double maximumCornerGap = (GEOMETRY.tileWidth() + GEOMETRY.tileGap()) * 1.5D;
        assertTrue(distance(sideZeroEnd, sideOneStart) < maximumCornerGap);
        assertTrue(distance(sideOneEnd, sideTwoStart) < maximumCornerGap);
        assertTrue(distance(sideTwoEnd, sideThreeStart) < maximumCornerGap);
        assertTrue(distance(sideThreeEnd, sideZeroStart) < maximumCornerGap);
    }

    @Test
    void discardRiverWrapsAfterSixTiles() {
        ResolvedTableLayout layout =
                new UniversalTableLayout(GEOMETRY).resolve(table(144));
        RuleViewTile first = tile(1, "mcr:tile/m1", RuleViewZone.DISCARD, 0, true, 0);
        RuleViewTile seventh = tile(7, "mcr:tile/m7", RuleViewZone.DISCARD, 6, true, 6);

        SceneTransform firstTransform = layout.tile(first, 7);
        SceneTransform seventhTransform = layout.tile(seventh, 7);

        assertEquals(firstTransform.x(), seventhTransform.x(), 0.000_001D);
        assertNotEquals(firstTransform.z(), seventhTransform.z());
        assertEquals(firstTransform.y(), seventhTransform.y());
    }

    @Test
    void universalLayoutSupportsEveryOfficialWallShape() {
        UniversalTableLayout layout = new UniversalTableLayout(GEOMETRY);

        assertEquals(108, table(108).wall().tileCapacity());
        assertEquals(136, table(136).wall().tileCapacity());
        assertEquals(144, table(144).wall().tileCapacity());
        assertEquals(
                List.of(14, 13, 14, 13), table(108).wall().stackCountsBySide());
        assertEquals(
                -1.0D,
                layout.resolve(table(108)).tile(wallTile(1, 107), 108).x(),
                0.000_001D);
        assertEquals(
                -1.0D,
                layout.resolve(table(136)).tile(wallTile(2, 135), 136).x(),
                0.000_001D);
        assertEquals(
                -1.0D,
                layout.resolve(table(144)).tile(wallTile(3, 143), 144).x(),
                0.000_001D);
    }

    @Test
    void wallOriginAndDirectionDoNotChangeTheSharedTableShape() {
        UniversalTableLayout layout = new UniversalTableLayout(GEOMETRY);
        RuleTablePresentation base = table(136);
        RuleTablePresentation shifted = new RuleTablePresentation(
                4,
                new RuleWallPresentation(
                        List.of(17, 17, 17, 17),
                        1,
                        RuleWallDirection.CLOCKWISE),
                6,
                Optional.of(SEAT_ZERO),
                Optional.of(SEAT_ZERO),
                Optional.empty());
        RuleTablePresentation reversed = new RuleTablePresentation(
                4,
                new RuleWallPresentation(
                        List.of(17, 17, 17, 17),
                        1,
                        RuleWallDirection.COUNTERCLOCKWISE),
                6,
                Optional.of(SEAT_ZERO),
                Optional.of(SEAT_ZERO),
                Optional.empty());

        SceneTransform baseSecondStack =
                layout.resolve(base).tile(wallTile(1, 2), 136);
        SceneTransform shiftedFirstStack =
                layout.resolve(shifted).tile(wallTile(2, 0), 136);
        SceneTransform baseFirstStack =
                layout.resolve(base).tile(wallTile(3, 0), 136);
        SceneTransform reversedSecondStack =
                layout.resolve(reversed).tile(wallTile(4, 2), 136);

        assertEquals(baseSecondStack, shiftedFirstStack);
        assertEquals(baseFirstStack, reversedSecondStack);
    }

    @Test
    void differTouchesOnlyChangedStableNodes() {
        TableId table = TableId.random();
        SceneGraph before = mapper().map(projection(table, 1, "playing"));
        SceneGraph after = mapper().map(projection(table, 2, "settlement"));
        SceneDiff diff = new SceneGraphDiffer().diff(before, after);

        assertFalse(diff.upserts().isEmpty());
        assertTrue(diff.upserts().stream().anyMatch(HudNode.class::isInstance));
        assertTrue(diff.upserts().stream().noneMatch(InteractionNode.class::isInstance));
        assertTrue(diff.mutationCount() < after.nodes().size() + before.nodes().size());
    }

    @Test
    void mapperDoesNotPlaceUnusedCraftEngineHitboxes() {
        TableProjection base = projection(TableId.random(), 1, "playing");
        TableProjection withoutActions =
                new TableProjection(
                        base.tableId(),
                        base.revision(),
                        base.lifecycle(),
                        base.publicView(),
                        base.privateViews(),
                        Map.of());

        SceneGraph graph = mapper().map(withoutActions);

        assertTrue(graph.nodes().values().stream().noneMatch(InteractionNode.class::isInstance));
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
                            return SceneGraph.empty(projection.tableId(), projection.revision());
                        },
                        submitted::add,
                        new SceneGraphDiffer(),
                        (task, delay) -> {
                            executor.execute(task);
                            return () -> true;
                        });
        TableId table = TableId.random();
        projector.publish(projection(table, 1, "one"));
        projector.publish(projection(table, 2, "two"));
        projector.publish(projection(table, 3, "three"));

        executor.runAll();

        assertEquals(List.of(3L), mapped);
        assertEquals(1, submitted.size());
        assertEquals(3, submitted.getFirst().toRevision());
    }

    @Test
    void rejectedRenderDispatchRetriesOnlyTheLatestProjection() {
        ManualExecutor executor = new ManualExecutor();
        AtomicBoolean rejectFirst = new AtomicBoolean(true);
        Executor rejectOnce =
                task -> {
                    if (rejectFirst.getAndSet(false)) {
                        throw new RejectedExecutionException("render queue full");
                    }
                    executor.execute(task);
                };
        List<Long> mapped = new ArrayList<>();
        LatestSceneProjector projector =
                new LatestSceneProjector(
                        rejectOnce,
                        projection -> {
                            mapped.add(projection.revision());
                            return SceneGraph.empty(projection.tableId(), projection.revision());
                        },
                        ignored -> {},
                        new SceneGraphDiffer(),
                        (task, delay) -> {
                            executor.execute(task);
                            return () -> true;
                        });
        TableId table = TableId.random();

        projector.publish(projection(table, 1, "superseded"));
        projector.publish(projection(table, 2, "latest"));
        executor.runAll();

        assertEquals(List.of(2L), mapped);
    }

    private static DefaultTableSceneMapper mapper() {
        return new DefaultTableSceneMapper(new UniversalTableLayout(GEOMETRY), ASSETS);
    }

    private static TableProjection projection(TableId tableId, long revision, String phase) {
        RuleViewTile publicBack =
                tile(1, "riichi:tile/m5_red", RuleViewZone.HAND, 0, false, 0);
        RuleViewTile privateFace =
                tile(1, "riichi:tile/m5_red", RuleViewZone.HAND, 0, true, 0);
        AuthorizedAction action =
                action(
                        revision,
                        "discard.1",
                        ActionPresentation.handTile("action.discard", new TileInstanceId(1)));
        return new TableProjection(
                tableId,
                revision,
                TableLifecycle.ACTIVE,
                new PublicRuleView(
                        revision, phase, List.of(publicBack), Map.of(), table(136)),
                Map.of(
                        PLAYER,
                        new PrivateRuleView(
                                revision,
                                PLAYER,
                                SEAT_ZERO,
                                List.of(privateFace),
                                Map.of())),
                Map.of(PLAYER, List.of(action)));
    }

    private static TableProjection publicProjection(
            TableId tableId,
            long revision,
            List<RuleViewTile> tiles,
            RuleTablePresentation tablePresentation) {
        return new TableProjection(
                tableId,
                revision,
                TableLifecycle.ACTIVE,
                new PublicRuleView(
                        revision, "playing", tiles, Map.of(), tablePresentation),
                Map.of(),
                Map.of());
    }

    private static AuthorizedAction action(
            long revision, String key, ActionPresentation presentation) {
        return new AuthorizedAction(
                new ActionToken(UUID.randomUUID(), PLAYER, revision),
                new LegalAction(key, new RuleAction("test", new byte[] {1}), presentation));
    }

    private static RuleViewTile wallTile(long id, int layoutIndex) {
        return new RuleViewTile(
                new TileInstanceId(id),
                new TileVisualId("riichi:tile/m1"),
                Optional.empty(),
                RuleViewZone.WALL,
                layoutIndex,
                false,
                RuleTilePresentation.natural(layoutIndex));
    }

    private static double distance(SceneTransform first, SceneTransform second) {
        return Math.hypot(first.x() - second.x(), first.z() - second.z());
    }

    private static RuleViewTile tile(
            long id,
            String visualId,
            RuleViewZone zone,
            int index,
            boolean faceUp,
            int layoutIndex) {
        return new RuleViewTile(
                new TileInstanceId(id),
                new TileVisualId(visualId),
                Optional.of(SEAT_ZERO),
                zone,
                index,
                faceUp,
                RuleTilePresentation.natural(layoutIndex));
    }

    private static RuleTablePresentation table(int wallCapacity) {
        List<Integer> stacks = switch (wallCapacity) {
            case 108 -> List.of(14, 13, 14, 13);
            case 136 -> List.of(17, 17, 17, 17);
            case 144 -> List.of(18, 18, 18, 18);
            default -> throw new IllegalArgumentException("Unsupported test wall");
        };
        return new RuleTablePresentation(
                4,
                new RuleWallPresentation(stacks, 0, RuleWallDirection.CLOCKWISE),
                6,
                Optional.of(SEAT_ZERO),
                Optional.of(SEAT_ZERO),
                Optional.empty());
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
