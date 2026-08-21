package top.ellan.mahjong.presentation.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import top.ellan.mahjong.application.interaction.InteractionPurpose;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.layout.TableGeometry;
import top.ellan.mahjong.presentation.layout.UniversalTableLayout;
import top.ellan.mahjong.presentation.node.ActionFurnitureNode;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.PrivateFurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.presentation.scene.SceneDiff;
import top.ellan.mahjong.presentation.scene.SceneGraph;
import top.ellan.mahjong.presentation.scene.SceneGraphDiffer;
import top.ellan.mahjong.presentation.scene.SceneInteractionBinding;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleMeldPresentation;
import top.ellan.mahjong.spi.RuleMeldTileRole;
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
    private static final PlayerId SECOND_PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"));
    private static final PlayerId THIRD_PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000b3"));
    private static final PlayerId FOURTH_PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000b4"));
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final SeatId SEAT_ZERO = new SeatId(0);
    private static final TableSceneAssets ASSETS =
            new TableSceneAssets(
                    "mahjongpaper:table_visual",
                    "mahjongpaper:seat_chair",
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
                    18,
                    48,
                    24,
                    64,
                    32,
                    64);

    @Test
    void tableAndIndependentChairsMatchTheV15FurnitureAnchors() {
        SceneGraph graph = mapper().map(projection(TableId.random(), 4, "playing"));

        assertEquals(
                new SceneTransform(0, 0.895D, 0, 0, 0, 0, 1),
                ((FurnitureNode) graph.nodes().get(new SceneNodeId("furniture/table"))).transform());
        assertEquals(
                new SceneTransform(2.125D, 0.9D, 0, 90, 0, 0, 1),
                ((FurnitureNode) graph.nodes().get(new SceneNodeId("furniture/seat/0"))).transform());
        assertEquals(
                new SceneTransform(0, 0.9D, 2.125D, 180, 0, 0, 1),
                ((FurnitureNode) graph.nodes().get(new SceneNodeId("furniture/seat/1"))).transform());
        assertEquals(
                new SceneTransform(-2.125D, 0.9D, 0, 270, 0, 0, 1),
                ((FurnitureNode) graph.nodes().get(new SceneNodeId("furniture/seat/2"))).transform());
        assertEquals(
                new SceneTransform(0, 0.9D, -2.125D, 0, 0, 0, 1),
                ((FurnitureNode) graph.nodes().get(new SceneNodeId("furniture/seat/3"))).transform());
    }

    @Test
    void newestDiscardIsEchoedAtTableCentreLikeV15() {
        RuleViewTile discard = tile(7, "riichi:tile/m5_red", RuleViewZone.DISCARD, 0, true, 0);

        SceneGraph withHighlight =
                mapper().map(discardProjection(Optional.of(new TileInstanceId(7)), discard));
        FurnitureNode highlight = (FurnitureNode)
                withHighlight.nodes().get(new SceneNodeId("furniture/last-discard"));
        assertNotNull(highlight);
        // 1.5.0 floated a double-size copy dead centre at y 0.68.
        assertEquals(new SceneTransform(0, 0.68D, 0, 0, 0, 0, 2.0D), highlight.transform());
        assertTrue(highlight.visibility().isPublic());

        // Without a pending discard the node is absent, so the differ retires it.
        assertNull(mapper()
                .map(discardProjection(Optional.empty(), discard))
                .nodes()
                .get(new SceneNodeId("furniture/last-discard")));
    }

    @Test
    void mapperUsesOneConditionalCeFurnitureForBackAndSecretFace() {
        SceneGraph graph = mapper().map(projection(TableId.random(), 4, "playing"));

        assertFalse(
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
                        .filter(PrivateFurnitureNode.class::isInstance)
                        .map(PrivateFurnitureNode.class::cast)
                        .anyMatch(node -> node.worldBacked()
                                && node.visualId().value().equals("riichi:tile/m5_red")));
        assertEquals(1, graph.interactionBindings().size());
        assertEquals(4, graph.interactionBindings().getFirst().actionToken().revision());
    }

    @Test
    void conditionalFurnitureReplacesTheDuplicatePublicBack() {
        SceneGraph graph = mapper().map(projection(TableId.random(), 4, "playing"));
        String viewer = PLAYER.toString().replace("-", "");

        assertNull(graph.nodes().get(new SceneNodeId("tile/public/1")));
        PrivateFurnitureNode furniture = (PrivateFurnitureNode) graph.nodes().get(
                new SceneNodeId("tile/private/" + viewer + "/1"));
        assertNotNull(furniture);
        assertTrue(furniture.worldBacked());
        assertEquals(SceneVisibility.privateTo(PLAYER), furniture.visibility());
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
                .noneMatch(PrivateFurnitureNode.class::isInstance));
        assertTrue(graph.nodes().values().stream()
                .filter(FurnitureNode.class::isInstance)
                .map(FurnitureNode.class::cast)
                .anyMatch(node -> node.assetId()
                        .equals("mahjongpaper:tile_standing_m5_red")));
    }

    @Test
    void publicHudIsProjectedOncePerTableRatherThanOncePerSeat() {
        SceneGraph graph = mapper().map(fourSeatProjection(TableId.random(), 7));

        List<HudNode> huds = graph.nodes().values().stream()
                .filter(HudNode.class::isInstance)
                .map(HudNode.class::cast)
                .toList();
        List<HudNode> shared = huds.stream()
                .filter(node -> node.visibility().viewers().size() > 1)
                .toList();

        // Phase, wall capacity, current seat, round and wall are shared once for all four seats.
        // Scores and other detailed attributes belong to settlement, not the continuous HUD.
        assertEquals(5, huds.size());
        assertEquals(5, shared.size());
        for (HudNode node : shared) {
            assertEquals(4, node.visibility().viewers().size());
            assertFalse(node.visibility().isPublic());
            assertFalse(node.worldBacked());
        }
    }

    @Test
    void multiViewerNodesRemainIneligibleForWorldBackedEntities() {
        SceneNodeId id = SceneNodeId.trusted("furniture/shared");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FurnitureNode(
                        id,
                        SceneVisibility.privateTo(java.util.Set.of(PLAYER, SECOND_PLAYER)),
                        "mahjongpaper:tile_standing",
                        new SceneTransform(0, 1, 0, 0, 0, 0, 1)));
    }

    @Test
    void anEmptyPrivateAudienceIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SceneVisibility.privateTo(java.util.Set.<PlayerId>of()));
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
        PrivateFurnitureNode privateTile = graph.nodes().values().stream()
                .filter(PrivateFurnitureNode.class::isInstance)
                .map(PrivateFurnitureNode.class::cast)
                .findFirst()
                .orElseThrow();
        InteractionNode interaction = graph.nodes().values().stream()
                .filter(InteractionNode.class::isInstance)
                .map(InteractionNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(privateTile.transform(), interaction.transform());
        assertEquals("mahjongpaper:hand_tile_hitbox", interaction.assetId());
        assertEquals(0.1D, interaction.bounds().width(), 0.000_001D);
        assertEquals(0.18D, interaction.bounds().height(), 0.000_001D);
        assertEquals(GEOMETRY.tileDepth(), interaction.bounds().depth(), 0.000_001D);
        assertEquals(
                InteractionPurpose.HAND_TILE_ACTION,
                graph.interactionBindings().getFirst().purpose());
        assertEquals(
                privateTile.tileInstanceId(),
                graph.interactionBindings().getFirst().targetTile());
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
        ActionFurnitureNode label = mapper().map(rowAction).nodes().values().stream()
                .filter(ActionFurnitureNode.class::isInstance)
                .map(ActionFurnitureNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("mahjongpaper:action_button_hitbox", interaction.assetId());
        assertEquals(0.22D, interaction.bounds().height(), 0.000_001D);
        assertEquals(0.0D, interaction.bounds().depth(), 0.000_001D);
        assertEquals(0.01D, interaction.bounds().centerYOffset(), 0.000_001D);
        assertEquals("action.win", label.labelKey());
        assertEquals(interaction.transform(), label.transform());
        assertEquals(GEOMETRY.surfaceHeight() + 0.36D, label.transform().y(), 0.000_001D);
    }

    @Test
    void overheadViewKeepsItsHitboxPublicAndItsLabelAndCameraPrivate() {
        SceneGraph graph = overheadMapper().map(projection(TableId.random(), 11, "playing"));

        InteractionNode view = graph.nodes().values().stream()
                .filter(InteractionNode.class::isInstance)
                .map(InteractionNode.class::cast)
                .filter(node -> node.id().value().startsWith("interaction/view/"))
                .findFirst()
                .orElseThrow();
        ActionFurnitureNode label = graph.nodes().values().stream()
                .filter(ActionFurnitureNode.class::isInstance)
                .map(ActionFurnitureNode.class::cast)
                .filter(node -> node.labelKey().equals("action.view_river"))
                .findFirst()
                .orElseThrow();
        CameraNode camera = graph.nodes().values().stream()
                .filter(CameraNode.class::isInstance)
                .map(CameraNode.class::cast)
                .findFirst()
                .orElseThrow();
        SceneInteractionBinding binding = graph.interactionBindings().stream()
                .filter(candidate -> candidate.purpose() == InteractionPurpose.OVERHEAD_VIEW)
                .findFirst()
                .orElseThrow();

        assertTrue(view.worldBacked());
        assertTrue(view.visibility().isPublic());
        assertTrue(label.worldBacked());
        assertEquals(Optional.of(PLAYER), label.visibility().singleViewer());
        assertEquals(Optional.of(PLAYER), camera.visibility().singleViewer());
        assertEquals(4.5D, camera.transform().y());
        assertEquals(90.0D, camera.transform().yawDegrees());
        assertEquals(90.0D, camera.transform().pitchDegrees());
        assertTrue(view.transform().x() > 0.0D);
        assertEquals(view.transform(), label.transform());
        assertEquals(GEOMETRY.surfaceHeight() + 0.36D, label.transform().y(), 0.000_001D);
        assertEquals(11, binding.revision());
        assertEquals(PLAYER, binding.playerId());
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
                new SceneNodeId("interaction/action/" + player + "/z_first-0"));
        InteractionNode secondNode = (InteractionNode) graph.nodes().get(
                new SceneNodeId("interaction/action/" + player + "/a_second-1"));

        assertTrue(firstNode.transform().z() < secondNode.transform().z());
    }

    @Test
    void duplicateSemanticActionsKeepIndependentRoutes() {
        TableProjection base = projection(TableId.random(), 2, "playing");
        TableProjection duplicate = new TableProjection(
                base.tableId(),
                base.revision(),
                base.lifecycle(),
                base.publicView(),
                base.privateViews(),
                Map.of(
                        PLAYER,
                        List.of(
                                action(2, "chii", ActionPresentation.actionRow("action.chii")),
                                action(2, "chii", ActionPresentation.actionRow("action.chii")))));

        SceneGraph graph = mapper().map(duplicate);
        String prefix = "interaction/action/" + PLAYER.toString().replace("-", "") + "/chii-";

        assertEquals(
                2,
                graph.nodes().keySet().stream()
                        .filter(id -> id.value().startsWith(prefix))
                        .count());
        assertEquals(2, graph.interactionBindings().size());
    }

    @Test
    void fifthActionWrapsDownWithoutMovingAwayFromThePlayer() {
        TableProjection base = projection(TableId.random(), 5, "playing");
        List<AuthorizedAction> actions = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            actions.add(action(
                    5,
                    "action" + index,
                    ActionPresentation.actionRow("action.ready")));
        }
        TableProjection wrapped = new TableProjection(
                base.tableId(),
                base.revision(),
                base.lifecycle(),
                base.publicView(),
                base.privateViews(),
                Map.of(PLAYER, List.copyOf(actions)));

        SceneGraph graph = mapper().map(wrapped);
        String player = PLAYER.toString().replace("-", "");
        InteractionNode first = (InteractionNode) graph.nodes().get(
                new SceneNodeId("interaction/action/" + player + "/action0-0"));
        InteractionNode fifth = (InteractionNode) graph.nodes().get(
                new SceneNodeId("interaction/action/" + player + "/action4-4"));

        assertEquals(first.transform().x(), fifth.transform().x(), 0.000_001D);
        assertEquals(
                GEOMETRY.actionRowSpacing(),
                first.transform().y() - fifth.transform().y(),
                0.000_001D);
    }

    @Test
    void secondaryActionsContinueDownwardInsteadOfStackingFrontToBack() {
        TableProjection base = projection(TableId.random(), 2, "playing");
        TableProjection rows = new TableProjection(
                base.tableId(),
                base.revision(),
                base.lifecycle(),
                base.publicView(),
                base.privateViews(),
                Map.of(
                        PLAYER,
                        List.of(
                                action(2, "primary", ActionPresentation.actionRow("action.ready")),
                                action(
                                        2,
                                        "secondary",
                                        ActionPresentation.secondaryRow("action.leave")))));

        SceneGraph graph = mapper().map(rows);
        String player = PLAYER.toString().replace("-", "");
        InteractionNode primary = (InteractionNode) graph.nodes().get(
                new SceneNodeId("interaction/action/" + player + "/primary-0"));
        InteractionNode secondary = (InteractionNode) graph.nodes().get(
                new SceneNodeId("interaction/action/" + player + "/secondary-1"));

        assertEquals(primary.transform().x(), secondary.transform().x(), 0.000_001D);
        assertEquals(primary.transform().z(), secondary.transform().z(), 0.000_001D);
        assertEquals(
                GEOMETRY.actionRowSpacing(),
                primary.transform().y() - secondary.transform().y(),
                0.000_001D);
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
    void graphConstructorDoesNotMutateTheCallersMap() {
        SceneGraph base = mapper().map(projection(TableId.random(), 1, "playing"));
        Map<SceneNodeId, SceneNode> mutable = new java.util.LinkedHashMap<>(base.nodes());
        Map<SceneNodeId, SceneNode> before = Map.copyOf(mutable);

        new SceneGraph(base.tableId(), base.revision(), mutable, base.interactionBindings());

        assertEquals(before, mutable);
    }

    @Test
    void graphConstructorPublishesOneImmutableCopy() {
        SceneGraph base = mapper().map(projection(TableId.random(), 1, "playing"));
        Map<SceneNodeId, SceneNode> mutable = new java.util.LinkedHashMap<>(base.nodes());

        SceneGraph graph = new SceneGraph(base.tableId(), base.revision(), mutable, base.interactionBindings());

        mutable.clear();
        assertEquals(base.nodes().size(), graph.nodes().size());
        assertFalse(graph.nodes().isEmpty());
    }

    @Test
    void mapperOwnedGraphContainersRemainImmutableToConsumers() {
        SceneGraph graph = mapper().map(projection(TableId.random(), 1, "playing"));

        assertThrows(UnsupportedOperationException.class, graph.nodes()::clear);
        assertThrows(UnsupportedOperationException.class, graph.interactionBindings()::clear);
    }

    @Test
    void differOwnsTheSameRemovalsAndUpsertsAsTheDefensiveConstructor() {
        TableId table = TableId.random();
        SceneGraph before = mapper().map(projection(table, 1, "playing"));
        SceneGraph after = mapper().map(projection(table, 2, "settlement"));

        // The differ transfers ownership of private ArrayLists; the defensive constructor would
        // copy the same content. Both must observe identical removal/upsert content.
        SceneDiff trusted = new SceneGraphDiffer().diff(before, after);
        SceneDiff defensive = new SceneDiff(
                trusted.tableId(),
                trusted.fromRevision(),
                trusted.toRevision(),
                trusted.removals(),
                trusted.upserts(),
                trusted.interactionBindings());

        assertEquals(trusted.removals(), defensive.removals());
        assertEquals(trusted.upserts(), defensive.upserts());
        assertEquals(trusted.interactionBindings(), defensive.interactionBindings());
        assertEquals(trusted, defensive);
        assertEquals(trusted.hashCode(), defensive.hashCode());
    }

    @Test
    void differRejectsStaleSceneRevisions() {
        TableId table = TableId.random();
        SceneGraph graph = mapper().map(projection(table, 5, "playing"));
        SceneDiff diff = new SceneGraphDiffer().diff(graph, graph);

        assertEquals(5, diff.fromRevision());
        assertEquals(5, diff.toRevision());
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
        assertEquals(1.0D, seatZeroTop.x(), 0.000_001D);
        assertEquals(1.0D, seatOne.z(), 0.000_001D);
        assertEquals(-1.0D, seatTwo.x(), 0.000_001D);
        assertEquals(-1.0D, seatThree.z(), 0.000_001D);
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

        assertEquals(firstTransform.z(), seventhTransform.z(), 0.000_001D);
        assertNotEquals(firstTransform.x(), seventhTransform.x());
        assertEquals(firstTransform.y(), seventhTransform.y());
    }

    @Test
    void everyPrivateHandSlotPreservesTheFormerOutwardOffset() {
        ResolvedTableLayout layout = new UniversalTableLayout(GEOMETRY).resolve(table(136));
        double offset = Math.max(0.0005D, GEOMETRY.tileGap() * 0.5D);

        for (int seat = 0; seat < 4; seat++) {
            double angle = Math.PI * 2.0D * seat / 4.0D;
            for (int size = 1; size <= GEOMETRY.maxHandTiles(); size++) {
                for (int index = 0; index < size; index++) {
                    RuleViewTile tile = handTile(index + 1L, seat, index);
                    SceneTransform publicTransform = layout.tile(tile, size);
                    SceneTransform privateTransform = layout.privateTile(tile, size);

                    assertEquals(
                            publicTransform.x() + Math.cos(angle) * offset,
                            privateTransform.x(),
                            0.000_000_001D);
                    assertEquals(
                            publicTransform.z() + Math.sin(angle) * offset,
                            privateTransform.z(),
                            0.000_000_001D);
                    assertEquals(publicTransform.y(), privateTransform.y());
                    assertEquals(publicTransform.yawDegrees(), privateTransform.yawDegrees());
                    assertEquals(publicTransform.pitchDegrees(), privateTransform.pitchDegrees());
                    assertEquals(publicTransform.rollDegrees(), privateTransform.rollDegrees());
                    assertEquals(publicTransform.scale(), privateTransform.scale());
                }
            }
        }
    }

    @Test
    void sharedMeldSourceMarkersAreLeftMiddleRightFromTheOwnersView() {
        ResolvedTableLayout layout =
                new UniversalTableLayout(GEOMETRY).resolve(table(136));
        SceneTransform fromLeft = layout.tile(meldTile(
                1,
                RuleMeldPresentation.tile(
                        0, 3, 4, 0, 3, RuleMeldTileRole.CLAIMED, -1)), 3);
        SceneTransform fromOpposite = layout.tile(meldTile(
                2,
                RuleMeldPresentation.tile(
                        0, 3, 4, 0, 2, RuleMeldTileRole.CLAIMED, -1)), 3);
        SceneTransform fromRight = layout.tile(meldTile(
                3,
                RuleMeldPresentation.tile(
                        0, 3, 4, 0, 1, RuleMeldTileRole.CLAIMED, -1)), 3);

        assertTrue(fromLeft.z() < fromOpposite.z());
        assertTrue(fromOpposite.z() < fromRight.z());
    }

    @Test
    void universalLayoutSupportsEveryOfficialWallShape() {
        UniversalTableLayout layout = new UniversalTableLayout(GEOMETRY);

        assertEquals(108, table(108).wall().tileCapacity());
        assertEquals(136, table(136).wall().tileCapacity());
        assertEquals(144, table(144).wall().tileCapacity());
        assertEquals(
                List.of(13, 14, 13, 14), table(108).wall().stackCountsBySide());
        assertEquals(
                -1.0D,
                layout.resolve(table(108)).tile(wallTile(1, 107), 108).z(),
                0.000_001D);
        assertEquals(
                -1.0D,
                layout.resolve(table(136)).tile(wallTile(2, 135), 136).z(),
                0.000_001D);
        assertEquals(
                -1.0D,
                layout.resolve(table(144)).tile(wallTile(3, 143), 144).z(),
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
        return new DefaultTableSceneMapper(
                new UniversalTableLayout(GEOMETRY), ASSETS, 4.5D, false);
    }

    private static DefaultTableSceneMapper overheadMapper() {
        return new DefaultTableSceneMapper(
                new UniversalTableLayout(GEOMETRY), ASSETS, 4.5D, true);
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

    /** Four seated viewers plus three public and one private HUD attribute each. */
    private static TableProjection fourSeatProjection(TableId tableId, long revision) {
        Map<String, String> publicAttributes =
                Map.of("round", "east-1", "wall", "70", "dealer", "0");
        Map<PlayerId, PrivateRuleView> privateViews = new java.util.LinkedHashMap<>();
        List<PlayerId> seats = List.of(PLAYER, SECOND_PLAYER, THIRD_PLAYER, FOURTH_PLAYER);
        for (int seat = 0; seat < seats.size(); seat++) {
            privateViews.put(
                    seats.get(seat),
                    new PrivateRuleView(
                            revision,
                            seats.get(seat),
                            new SeatId(seat),
                            List.of(),
                            Map.of("score", Integer.toString(25_000 + seat))));
        }
        return new TableProjection(
                tableId,
                revision,
                TableLifecycle.ACTIVE,
                new PublicRuleView(
                        revision, "playing", List.of(), publicAttributes, table(136)),
                Map.copyOf(privateViews),
                Map.of());
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

    private static RuleViewTile handTile(long id, int seat, int layoutIndex) {
        return new RuleViewTile(
                new TileInstanceId(id),
                new TileVisualId("riichi:tile/m1"),
                Optional.of(new SeatId(seat)),
                RuleViewZone.HAND,
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

    private static RuleViewTile meldTile(long id, RuleTilePresentation presentation) {
        return new RuleViewTile(
                new TileInstanceId(id),
                new TileVisualId("mcr:tile/m1"),
                Optional.of(SEAT_ZERO),
                RuleViewZone.MELD,
                Math.toIntExact(id - 1),
                true,
                presentation);
    }

    /** One face-up discard, with the table optionally nominating it as the newest discard. */
    private static TableProjection discardProjection(
            Optional<TileInstanceId> lastDiscard, RuleViewTile discard) {
        RuleTablePresentation table = new RuleTablePresentation(
                4,
                new RuleWallPresentation(
                        List.of(17, 17, 17, 17), 0, RuleWallDirection.CLOCKWISE),
                6,
                Optional.of(SEAT_ZERO),
                Optional.of(SEAT_ZERO),
                lastDiscard);
        return new TableProjection(
                TableId.random(),
                9,
                TableLifecycle.ACTIVE,
                new PublicRuleView(9, "playing", List.of(discard), Map.of(), table),
                Map.of(),
                Map.of());
    }

    private static RuleTablePresentation table(int wallCapacity) {
        List<Integer> stacks = switch (wallCapacity) {
            case 108 -> List.of(13, 14, 13, 14);
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
