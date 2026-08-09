package top.ellan.mahjong.presentation.layout;

import java.util.List;
import top.ellan.mahjong.spi.RuleTablePresentation;

/** The small structural key that makes a physical layout reusable across rule packs. */
record UniversalLayoutSpec(int seatCount, List<Integer> wallStacks, int discardsPerRow) {
    UniversalLayoutSpec {
        wallStacks = List.copyOf(wallStacks);
    }

    static UniversalLayoutSpec from(RuleTablePresentation presentation) {
        return new UniversalLayoutSpec(
                presentation.seatCount(),
                presentation.wall().stackCountsBySide(),
                presentation.discardsPerRow());
    }

    int totalWallStacks() {
        return wallStacks.stream().mapToInt(Integer::intValue).sum();
    }
}
