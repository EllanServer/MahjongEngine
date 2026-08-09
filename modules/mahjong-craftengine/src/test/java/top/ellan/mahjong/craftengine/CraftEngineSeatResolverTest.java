package top.ellan.mahjong.craftengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.SeatId;

class CraftEngineSeatResolverTest {
    @Test
    void configuredChairPositionsMapClockwiseWithoutDuplicatingFurnitureGeometry() {
        assertEquals(
                new SeatId(0),
                CraftEngineSeatResolver.resolveConfiguredPosition(0, 2.25F).orElseThrow());
        assertEquals(
                new SeatId(1),
                CraftEngineSeatResolver.resolveConfiguredPosition(2.25F, 0).orElseThrow());
        assertEquals(
                new SeatId(2),
                CraftEngineSeatResolver.resolveConfiguredPosition(0, -2.25F).orElseThrow());
        assertEquals(
                new SeatId(3),
                CraftEngineSeatResolver.resolveConfiguredPosition(-2.25F, 0).orElseThrow());
        assertTrue(CraftEngineSeatResolver.resolveConfiguredPosition(0, 0).isEmpty());
    }
}
