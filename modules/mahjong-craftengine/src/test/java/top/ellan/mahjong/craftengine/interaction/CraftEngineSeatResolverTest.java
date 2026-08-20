package top.ellan.mahjong.craftengine.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.SeatId;

class CraftEngineSeatResolverTest {
    @Test
    void independentChairNodesMapInTheV15CompassOrder() {
        assertEquals(
                new SeatId(0), CraftEngineSeatResolver.resolveNode("furniture/seat/0").orElseThrow());
        assertEquals(
                new SeatId(1), CraftEngineSeatResolver.resolveNode("furniture/seat/1").orElseThrow());
        assertEquals(
                new SeatId(2), CraftEngineSeatResolver.resolveNode("furniture/seat/2").orElseThrow());
        assertEquals(
                new SeatId(3), CraftEngineSeatResolver.resolveNode("furniture/seat/3").orElseThrow());
        assertTrue(CraftEngineSeatResolver.resolveNode("furniture/table").isEmpty());
        assertTrue(CraftEngineSeatResolver.resolveNode("furniture/seat/4").isEmpty());
    }

    @Test
    void configuredChairOffsetsUseEastSouthWestNorth() {
        assertEquals(
                new SeatId(0),
                CraftEngineSeatResolver.resolveConfiguredPosition(2.125F, 0).orElseThrow());
        assertEquals(
                new SeatId(1),
                CraftEngineSeatResolver.resolveConfiguredPosition(0, 2.125F).orElseThrow());
        assertEquals(
                new SeatId(2),
                CraftEngineSeatResolver.resolveConfiguredPosition(-2.125F, 0).orElseThrow());
        assertEquals(
                new SeatId(3),
                CraftEngineSeatResolver.resolveConfiguredPosition(0, -2.125F).orElseThrow());
        assertTrue(CraftEngineSeatResolver.resolveConfiguredPosition(0, 0).isEmpty());
    }
}
