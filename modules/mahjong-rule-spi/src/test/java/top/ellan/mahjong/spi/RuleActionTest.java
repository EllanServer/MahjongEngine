package top.ellan.mahjong.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuleActionTest {
    @Test
    void payloadIsDefensivelyCopiedInBothDirections() {
        byte[] source = {1, 2, 3};
        RuleAction action = new RuleAction("discard", source);
        source[0] = 9;
        byte[] returned = action.payload();
        returned[1] = 9;
        assertEquals(1, action.payload()[0]);
        assertEquals(2, action.payload()[1]);
    }

    @Test
    void binaryValueObjectsUseContentEquality() {
        assertEquals(
                new RuleAction("discard", new byte[] {1, 2}),
                new RuleAction("discard", new byte[] {1, 2}));
        assertEquals(
                new RuleEvent("discarded", new byte[] {3, 4}),
                new RuleEvent("discarded", new byte[] {3, 4}));
        assertEquals(
                new RuleStateSnapshot(1, 7, new byte[] {5, 6}, "a".repeat(64)),
                new RuleStateSnapshot(1, 7, new byte[] {5, 6}, "a".repeat(64)));
    }
}
