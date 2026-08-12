package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;

/**
 * Declarative physical wall: reusable geometry consumes this without knowing the rule mode.
 *
 * @param stackCountsBySide physical stack count for each table side
 * @param drawStartStack zero-based global stack at which drawing begins
 * @param direction direction in which the physical wall is consumed
 */
public record RuleWallPresentation(
        List<Integer> stackCountsBySide,
        int drawStartStack,
        RuleWallDirection direction) {
    private static final int MAX_STACKS_PER_SIDE = 32;
    private static final int MAX_TOTAL_STACKS = 128;

    public RuleWallPresentation {
        stackCountsBySide = List.copyOf(
                Objects.requireNonNull(stackCountsBySide, "stackCountsBySide"));
        Objects.requireNonNull(direction, "direction");
        if (stackCountsBySide.size() < 2 || stackCountsBySide.size() > 4) {
            throw new IllegalArgumentException("A physical wall requires two through four sides");
        }
        int total = 0;
        for (Integer count : stackCountsBySide) {
            if (count == null || count < 1 || count > MAX_STACKS_PER_SIDE) {
                throw new IllegalArgumentException("Wall stack count is outside the physical range");
            }
            total = Math.addExact(total, count);
        }
        if (total > MAX_TOTAL_STACKS) {
            throw new IllegalArgumentException("Wall has too many physical stacks");
        }
        if (drawStartStack < 0 || drawStartStack >= total) {
            throw new IllegalArgumentException("Wall draw start is outside the physical stacks");
        }
    }

    public int totalStacks() {
        int total = 0;
        for (int count : stackCountsBySide) {
            total += count;
        }
        return total;
    }

    public int tileCapacity() {
        return totalStacks() * 2;
    }
}
