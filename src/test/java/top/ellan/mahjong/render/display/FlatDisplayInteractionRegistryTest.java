package top.ellan.mahjong.render.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.SeatWind;

final class DisplayInteractionRayRegistryTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @AfterEach
    void clearRegistry() {
        DisplayInteractionRayRegistry.clear();
    }

    @Test
    void resolvesFlatControlsFacingEveryTableSide() {
        DisplayClickAction south = action("south");
        DisplayClickAction north = action("north");
        DisplayClickAction east = action("east");
        DisplayClickAction west = action("west");

        assertEquals(south, resolve(List.of(interaction(0.0D, 3.0D, 1.0D, 0.0D, south)), 0.0D, 1.5D, 0.0D, 0.0D, 0.0D, 1.0D));
        assertEquals(north, resolve(List.of(interaction(0.0D, -3.0D, 1.0D, 0.0D, north)), 0.0D, 1.5D, 0.0D, 0.0D, 0.0D, -1.0D));
        assertEquals(east, resolve(List.of(interaction(3.0D, 0.0D, 0.0D, 1.0D, east)), 0.0D, 1.5D, 0.0D, 1.0D, 0.0D, 0.0D));
        assertEquals(west, resolve(List.of(interaction(-3.0D, 0.0D, 0.0D, 1.0D, west)), 0.0D, 1.5D, 0.0D, -1.0D, 0.0D, 0.0D));
    }

    @Test
    void rejectsRaysOutsideTheVisualWidthHeightWorldOrReach() {
        DisplayClickAction action = action("target");
        List<DisplayInteractionRayRegistry.RayInteraction> interactions = List.of(
            interaction(0.0D, 3.0D, 1.0D, 0.0D, action)
        );

        assertNull(resolve(interactions, 0.7D, 1.5D, 0.0D, 0.0D, 0.0D, 1.0D));
        assertNull(resolve(interactions, 0.0D, 2.2D, 0.0D, 0.0D, 0.0D, 1.0D));
        assertNull(DisplayInteractionRayRegistry.resolveRay(
            interactions,
            UUID.randomUUID(),
            0.0D,
            1.5D,
            0.0D,
            0.0D,
            0.0D,
            1.0D,
            6.0D
        ));
        assertNull(DisplayInteractionRayRegistry.resolveRay(
            interactions,
            WORLD_ID,
            0.0D,
            1.5D,
            0.0D,
            0.0D,
            0.0D,
            1.0D,
            2.9D
        ));
    }

    @Test
    void nearestMatchingPlaneWins() {
        DisplayClickAction near = action("near");
        DisplayClickAction far = action("far");

        DisplayClickAction resolved = resolve(
            List.of(
                interaction(0.0D, 4.0D, 1.0D, 0.0D, far),
                interaction(0.0D, 2.0D, 1.0D, 0.0D, near)
            ),
            0.0D,
            1.5D,
            0.0D,
            0.0D,
            0.0D,
            1.0D
        );

        assertEquals(near, resolved);
    }

    @Test
    void orientedTileBoxUsesItsExactWidthHeightAndDepth() {
        DisplayClickAction tile = action("tile");
        DisplayInteractionRayRegistry.RayInteraction interaction = new DisplayInteractionRayRegistry.RayInteraction(
            WORLD_ID,
            0.0D,
            1.5D,
            3.0D,
            1.0D,
            0.0D,
            0.1125F,
            0.15F,
            0.075F,
            tile
        );

        assertEquals(tile, resolve(List.of(interaction), 0.056D, 1.5D, 0.0D, 0.0D, 0.0D, 1.0D));
        assertNull(resolve(List.of(interaction), 0.057D, 1.5D, 0.0D, 0.0D, 0.0D, 1.0D));
        assertEquals(tile, resolve(List.of(interaction), -1.0D, 1.5D, 3.037D, 1.0D, 0.0D, 0.0D));
        assertNull(resolve(List.of(interaction), -1.0D, 1.5D, 3.038D, 1.0D, 0.0D, 0.0D));
    }

    @Test
    void independentActionAndHandRegionsCoexistAndClearSeparately() {
        DisplayInteractionRayRegistry.RayInteraction action = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            action("button")
        );
        DisplayInteractionRayRegistry.RayInteraction tile = interaction(
            0.2D,
            3.0D,
            1.0D,
            0.0D,
            action("tile")
        );
        DisplayInteractionRayRegistry.replaceRegion(VIEWER_ID, "table-a", "viewer-actions", List.of(action));
        DisplayInteractionRayRegistry.replaceRegion(VIEWER_ID, "table-a", "hand-private-0:EAST", List.of(tile));

        assertEquals(List.of(action, tile), DisplayInteractionRayRegistry.snapshot(VIEWER_ID));
        DisplayInteractionRayRegistry.clearRegion(VIEWER_ID, "table-a", "viewer-actions");
        assertEquals(List.of(tile), DisplayInteractionRayRegistry.snapshot(VIEWER_ID));
    }

    @Test
    void identicalRegionReplacementKeepsTheExistingSnapshotAndActionChangesStillApply() {
        DisplayInteractionRayRegistry.RayInteraction first = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            action("first")
        );
        DisplayInteractionRayRegistry.replaceRegion(
            VIEWER_ID,
            "table-a",
            "viewer-actions",
            List.of(first)
        );
        List<DisplayInteractionRayRegistry.RayInteraction> firstSnapshot =
            DisplayInteractionRayRegistry.snapshot(VIEWER_ID);

        DisplayInteractionRayRegistry.replaceRegion(
            VIEWER_ID,
            "table-a",
            "viewer-actions",
            List.of(first)
        );

        assertSame(firstSnapshot, DisplayInteractionRayRegistry.snapshot(VIEWER_ID));

        DisplayInteractionRayRegistry.RayInteraction changed = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            action("second")
        );
        DisplayInteractionRayRegistry.replaceRegion(
            VIEWER_ID,
            "table-a",
            "viewer-actions",
            List.of(changed)
        );

        assertEquals(List.of(changed), DisplayInteractionRayRegistry.snapshot(VIEWER_ID));
    }

    @Test
    void identicalPublicJoinReplacementKeepsTheExistingFlattenedSnapshot() {
        DisplayInteractionRayRegistry.RayInteraction join = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            DisplayClickAction.joinSeat("table-a", SeatWind.EAST)
        );
        DisplayInteractionRayRegistry.RayInteraction privateDecision = interaction(
            0.0D,
            4.0D,
            1.0D,
            0.0D,
            action("private")
        );
        List<DisplayInteractionRayRegistry.RayInteraction> input = List.of(join, privateDecision);

        DisplayInteractionRayRegistry.replacePublicJoinRegion("table-a", "seat-label:EAST", input);
        List<DisplayInteractionRayRegistry.RayInteraction> firstSnapshot =
            DisplayInteractionRayRegistry.publicJoinSnapshot();

        DisplayInteractionRayRegistry.replacePublicJoinRegion("table-a", "seat-label:EAST", input);

        assertSame(firstSnapshot, DisplayInteractionRayRegistry.publicJoinSnapshot());
        assertEquals(List.of(join), firstSnapshot);
    }

    @Test
    void replacingPublicJoinActionStillUpdatesTheFlattenedSnapshot() {
        DisplayInteractionRayRegistry.RayInteraction east = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            DisplayClickAction.joinSeat("table-a", SeatWind.EAST)
        );
        DisplayInteractionRayRegistry.RayInteraction south = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            DisplayClickAction.joinSeat("table-a", SeatWind.SOUTH)
        );

        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:EAST",
            List.of(east)
        );
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:EAST",
            List.of(south)
        );

        assertEquals(List.of(south), DisplayInteractionRayRegistry.publicJoinSnapshot());
    }

    @Test
    void tableAndViewerCleanupCannotLeaveStaleControls() {
        DisplayInteractionRayRegistry.RayInteraction interaction = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            action("target")
        );
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of(interaction));

        assertEquals(List.of(interaction), DisplayInteractionRayRegistry.snapshot(VIEWER_ID));
        DisplayInteractionRayRegistry.clearTable("table-a");
        assertEquals(List.of(), DisplayInteractionRayRegistry.snapshot(VIEWER_ID));
    }

    @Test
    void publicJoinRegionsExposeOnlyJoinActionsAndClearWithTheirTable() {
        DisplayClickAction join = DisplayClickAction.joinSeat("table-a", SeatWind.EAST);
        DisplayInteractionRayRegistry.RayInteraction joinInteraction = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            join
        );
        DisplayInteractionRayRegistry.RayInteraction privateDecision = interaction(
            0.0D,
            4.0D,
            1.0D,
            0.0D,
            action("turn:dingque:wan")
        );

        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:EAST",
            List.of(joinInteraction, privateDecision)
        );

        assertEquals(List.of(joinInteraction), DisplayInteractionRayRegistry.publicJoinSnapshot());
        DisplayInteractionRayRegistry.clearTable("table-a");
        assertEquals(List.of(), DisplayInteractionRayRegistry.publicJoinSnapshot());
    }

    @Test
    void replacingAnOccupiedSeatRegionRemovesItsPreviousPublicJoin() {
        DisplayInteractionRayRegistry.RayInteraction join = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            DisplayClickAction.joinSeat("table-a", SeatWind.SOUTH)
        );
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:SOUTH",
            List.of(join)
        );

        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            "table-a",
            "seat-label:SOUTH",
            List.of()
        );

        assertEquals(List.of(), DisplayInteractionRayRegistry.publicJoinSnapshot());
        assertFalse(DisplayInteractionRayRegistry.isPublicJoinRegionCurrent("table-a", "seat-label:SOUTH"));
    }

    @Test
    void delayedCleanupFromAnOldTableCannotEraseTheNewTablesControls() {
        DisplayInteractionRayRegistry.RayInteraction interaction = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            action("target")
        );
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-b", List.of(interaction));

        DisplayInteractionRayRegistry.clearViewer(VIEWER_ID, "table-a");

        assertEquals(List.of(interaction), DisplayInteractionRayRegistry.snapshot(VIEWER_ID));
    }

    @Test
    void delayedEmptyPlanAndShutdownFromAnOldTableCannotEraseTheNewTablesControls() {
        DisplayInteractionRayRegistry.RayInteraction interaction = interaction(
            0.0D,
            3.0D,
            1.0D,
            0.0D,
            action("target")
        );
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of(interaction));
        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-b", List.of(interaction));

        DisplayInteractionRayRegistry.replace(VIEWER_ID, "table-a", List.of());
        DisplayInteractionRayRegistry.clearTable("table-a");

        assertEquals(List.of(interaction), DisplayInteractionRayRegistry.snapshot(VIEWER_ID));
    }

    private static DisplayClickAction resolve(
        List<DisplayInteractionRayRegistry.RayInteraction> interactions,
        double originX,
        double originY,
        double originZ,
        double directionX,
        double directionY,
        double directionZ
    ) {
        return DisplayInteractionRayRegistry.resolveRay(
            interactions,
            WORLD_ID,
            originX,
            originY,
            originZ,
            directionX,
            directionY,
            directionZ,
            6.0D
        );
    }

    private static DisplayInteractionRayRegistry.RayInteraction interaction(
        double centerX,
        double centerZ,
        double acrossX,
        double acrossZ,
        DisplayClickAction action
    ) {
        return new DisplayInteractionRayRegistry.RayInteraction(
            WORLD_ID,
            centerX,
            1.5D,
            centerZ,
            acrossX,
            acrossZ,
            1.0F,
            0.8F,
            0.0F,
            action
        );
    }

    private static DisplayClickAction action(String command) {
        return DisplayClickAction.playerCommand("table-a", VIEWER_ID, command);
    }
}
