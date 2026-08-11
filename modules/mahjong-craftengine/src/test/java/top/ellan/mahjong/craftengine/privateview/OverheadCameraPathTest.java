package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

final class OverheadCameraPathTest {
    @Test
    void sixteenTickPathKeepsExactEndpointsAndUsesSmoothMidpoint() {
        Location start = new Location(null, 2.0D, 1.6D, 3.0D, 350.0F, 10.0F);
        Location target = new Location(null, 0.0D, 4.5D, 0.0D, 10.0F, 90.0F);

        assertEquals(start, OverheadCameraPath.frame(start, target, 0, 16));
        assertEquals(target, OverheadCameraPath.frame(start, target, 16, 16));

        Location midpoint = OverheadCameraPath.frame(start, target, 8, 16);
        assertEquals(1.0D, midpoint.getX(), 0.000_001D);
        assertEquals(3.05D, midpoint.getY(), 0.000_001D);
        assertEquals(1.5D, midpoint.getZ(), 0.000_001D);
        assertEquals(360.0F, midpoint.getYaw(), 0.000_001F);
        assertEquals(50.0F, midpoint.getPitch(), 0.000_001F);
    }

    @Test
    void clientInterpolationPreservesTheConfiguredArrivalTick() {
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
}
