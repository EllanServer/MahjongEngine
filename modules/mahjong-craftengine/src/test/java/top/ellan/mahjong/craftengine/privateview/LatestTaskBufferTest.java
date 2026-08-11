package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatestTaskBufferTest {
    @Test
    void supersededNodeRunsOnlyItsLatestProjection() {
        LatestTaskBuffer<String> buffer = new LatestTaskBuffer<>();
        List<String> applied = new ArrayList<>();

        buffer.offer("tile", () -> applied.add("old"));
        buffer.offer("tile", () -> applied.add("latest"));

        assertEquals(1, buffer.drain(16));
        assertEquals(List.of("latest"), applied);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void drainBudgetLeavesRemainingNodesForTheNextTick() {
        LatestTaskBuffer<Integer> buffer = new LatestTaskBuffer<>();
        List<Integer> applied = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            int value = index;
            buffer.offer(index, () -> applied.add(value));
        }

        assertEquals(16, buffer.drain(16));
        assertEquals(4, buffer.size());
        assertFalse(buffer.isEmpty());
        assertEquals(4, buffer.drain(16));
        assertEquals(20, applied.size());
        assertTrue(buffer.isEmpty());
    }
}
