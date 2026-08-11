package top.ellan.mahjong.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuleOpeningPresentationTest {
    @Test
    void preservesOneOrTwoPhysicalRollsWithoutExposingAConcreteRulePack() {
        RuleDiceRoll first = new RuleDiceRoll(List.of(2, 5));
        RuleDiceRoll second = new RuleDiceRoll(List.of(3, 4));
        RuleOpeningPresentation opening =
                new RuleOpeningPresentation(7, List.of(first, second), new SeatId(2), 14);

        assertEquals(7, first.total());
        assertEquals(2, first.smallerPoint());
        assertEquals(List.of(first, second), opening.rolls());

        RuleTablePresentation table = new RuleTablePresentation(
                4,
                new RuleWallPresentation(
                        List.of(18, 18, 18, 18),
                        50,
                        RuleWallDirection.CLOCKWISE),
                6,
                Optional.of(new SeatId(0)),
                Optional.of(new SeatId(0)),
                Optional.empty(),
                Optional.of(opening));
        assertEquals(opening, table.opening().orElseThrow());
    }

    @Test
    void rejectsImpossibleDiceAndWallMetadataAtTheSpiBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new RuleDiceRoll(List.of(0, 6)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RuleOpeningPresentation(
                        0,
                        List.of(
                                new RuleDiceRoll(List.of(1, 1)),
                                new RuleDiceRoll(List.of(2, 2)),
                                new RuleDiceRoll(List.of(3, 3))),
                        new SeatId(0),
                        1));
        RuleOpeningPresentation outsideWall = new RuleOpeningPresentation(
                0, List.of(new RuleDiceRoll(List.of(6, 6))), new SeatId(0), 72);
        assertThrows(
                IllegalArgumentException.class,
                () -> new RuleTablePresentation(
                        4,
                        new RuleWallPresentation(
                                List.of(18, 18, 18, 18),
                                0,
                                RuleWallDirection.CLOCKWISE),
                        6,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(outsideWall)));
    }
}
