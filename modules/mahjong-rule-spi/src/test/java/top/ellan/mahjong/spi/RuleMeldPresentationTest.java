package top.ellan.mahjong.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RuleMeldPresentationTest {
    private static final SeatId EAST = new SeatId(0);

    @Test
    void sourceSeatSelectsLeftMiddleOrRightOnAFourPlayerTable() {
        assertClaimedSlot(1, new SeatId(3));
        assertClaimedSlot(2, new SeatId(2));
        assertClaimedSlot(3, new SeatId(1));
    }

    @Test
    void ordinaryTilesFillAroundTheClaimedSlotWithoutGaps() {
        RuleTilePresentation first = ordinary(new SeatId(2), 0);
        RuleTilePresentation second = ordinary(new SeatId(2), 1);
        assertEquals(1, first.layoutIndex());
        assertEquals(3, second.layoutIndex());
        assertEquals(RuleTileRotation.NATURAL, first.rotation());
        assertEquals(RuleTileRotation.NATURAL, second.rotation());
    }

    @Test
    void addedKongUsesThreeBasePositionsAndStacksOnTheClaimedTile() {
        RuleTilePresentation claimed = RuleMeldPresentation.tile(
                2, 3, 4, 0, 1, RuleMeldTileRole.CLAIMED, -1);
        RuleTilePresentation added = RuleMeldPresentation.tile(
                2, 3, 4, 0, 1, RuleMeldTileRole.ADDED, -1);
        assertEquals(11, claimed.layoutIndex());
        assertEquals(claimed.layoutIndex(), added.layoutIndex());
        assertEquals(1, added.stackLevel());
        assertEquals(RuleTileRotation.CLOCKWISE, added.rotation());
    }

    @Test
    void commonPosesAreCachedAndInvalidSemanticsFailClosed() {
        assertSame(RuleTilePresentation.clockwise(3), RuleTilePresentation.clockwise(3));
        assertSame(
                RuleTilePresentation.stackedClockwise(3),
                RuleTilePresentation.stackedClockwise(3));
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleMeldPresentation.tile(
                        0, 3, 4, EAST, EAST, RuleMeldTileRole.CLAIMED, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleMeldPresentation.tile(
                        0, 3, 4, EAST, new SeatId(1), RuleMeldTileRole.ORDINARY, 2));
    }

    private static void assertClaimedSlot(int expected, SeatId source) {
        RuleTilePresentation presentation = RuleMeldPresentation.tile(
                0, 3, 4, EAST, source, RuleMeldTileRole.CLAIMED, -1);
        assertEquals(expected, presentation.layoutIndex());
        assertEquals(RuleTileRotation.CLOCKWISE, presentation.rotation());
        assertEquals(0, presentation.stackLevel());
    }

    private static RuleTilePresentation ordinary(SeatId source, int ordinal) {
        return RuleMeldPresentation.tile(
                0, 3, 4, EAST, source, RuleMeldTileRole.ORDINARY, ordinal);
    }
}
