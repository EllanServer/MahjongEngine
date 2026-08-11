package top.ellan.mahjong.presentation.layout;

import java.util.List;
import top.ellan.mahjong.spi.RuleTablePresentation;

/** The small structural key that makes a physical layout reusable across rule packs. */
final class UniversalLayoutSpec {
    private final int seatCount;
    private final List<Integer> wallStacks;
    private final int discardsPerRow;
    private final int totalWallStacks;

    UniversalLayoutSpec(int seatCount, List<Integer> wallStacks, int discardsPerRow) {
        this.seatCount = seatCount;
        this.wallStacks = List.copyOf(wallStacks);
        this.discardsPerRow = discardsPerRow;
        int sum = 0;
        for (int stack : wallStacks) {
            sum += stack;
        }
        this.totalWallStacks = sum;
    }

    static UniversalLayoutSpec from(RuleTablePresentation presentation) {
        return new UniversalLayoutSpec(
                presentation.seatCount(),
                presentation.wall().stackCountsBySide(),
                presentation.discardsPerRow());
    }

    int seatCount() {
        return seatCount;
    }

    List<Integer> wallStacks() {
        return wallStacks;
    }

    int discardsPerRow() {
        return discardsPerRow;
    }

    int totalWallStacks() {
        return totalWallStacks;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UniversalLayoutSpec that
                && seatCount == that.seatCount
                && wallStacks.equals(that.wallStacks)
                && discardsPerRow == that.discardsPerRow;
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(seatCount);
        result = 31 * result + wallStacks.hashCode();
        result = 31 * result + Integer.hashCode(discardsPerRow);
        return result;
    }
}
