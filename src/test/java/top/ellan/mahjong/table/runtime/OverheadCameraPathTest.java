package top.ellan.mahjong.table.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

final class OverheadCameraPathTest {
    private static final double EPSILON = 1.0E-6D;

    @Test
    void catmullRomKeepsBothEndpointsExact() {
        Location start = new Location(null, 1.25D, 2.5D, -3.75D, 35.0F, -20.0F);
        Location target = new Location(null, 9.0D, 12.0D, 4.0D, 125.0F, 90.0F);

        assertLocationEquals(start, OverheadCameraPath.interpolate(start, target, 0, 16));
        assertLocationEquals(target, OverheadCameraPath.interpolate(start, target, 16, 16));
    }

    @Test
    void catmullRomEasesIntermediatePositionInsteadOfUsingLinearProgress() {
        Location start = new Location(null, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F);
        Location target = new Location(null, 100.0D, 200.0D, -100.0D, 0.0F, 80.0F);

        Location quarter = OverheadCameraPath.interpolate(start, target, 1, 4);

        // Catmull-Rom(start, start, target, target, 0.25) = 0.203125.
        assertEquals(20.3125D, quarter.getX(), EPSILON);
        assertEquals(40.625D, quarter.getY(), EPSILON);
        assertEquals(-20.3125D, quarter.getZ(), EPSILON);
        assertEquals(16.25F, quarter.getPitch(), (float) EPSILON);
    }

    @Test
    void yawUsesTheShortestPathAcrossTheDegreeBoundary() {
        Location start = new Location(null, 0.0D, 0.0D, 0.0D, 350.0F, 0.0F);
        Location target = new Location(null, 0.0D, 0.0D, 0.0D, 10.0F, 0.0F);

        Location halfway = OverheadCameraPath.interpolate(start, target, 1, 2);

        assertEquals(360.0F, halfway.getYaw(), (float) EPSILON);
        assertEquals(10.0F, OverheadCameraPath.interpolate(start, target, 2, 2).getYaw(), (float) EPSILON);
    }

    @Test
    void clientInterpolationPreservesDurationWhileReducingServerKeyframes() {
        assertEquals(10, OverheadCameraPath.clientInterpolationTicks(16));
        assertEquals(6, OverheadCameraPath.serverKeyframeCount(16, true));
        assertEquals(16, OverheadCameraPath.serverKeyframeCount(16, false));
        assertEquals(16, OverheadCameraPath.targetArrivalTick(16, true));
        assertEquals(16, OverheadCameraPath.targetArrivalTick(16, false));

        assertEquals(4, OverheadCameraPath.clientInterpolationTicks(5));
        assertEquals(1, OverheadCameraPath.serverKeyframeCount(5, true));
        assertEquals(5, OverheadCameraPath.targetArrivalTick(5, true));
        assertEquals(0, OverheadCameraPath.clientInterpolationTicks(1));
        assertEquals(1, OverheadCameraPath.serverKeyframeCount(1, true));
        assertEquals(1, OverheadCameraPath.targetArrivalTick(1, true));
    }

    private static void assertLocationEquals(Location expected, Location actual) {
        assertEquals(expected.getX(), actual.getX(), EPSILON);
        assertEquals(expected.getY(), actual.getY(), EPSILON);
        assertEquals(expected.getZ(), actual.getZ(), EPSILON);
        assertEquals(expected.getYaw(), actual.getYaw(), (float) EPSILON);
        assertEquals(expected.getPitch(), actual.getPitch(), (float) EPSILON);
    }
}
