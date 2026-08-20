package top.ellan.mahjong.application.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.match.RankLadder;
import top.ellan.mahjong.domain.match.RankMatchLength;
import top.ellan.mahjong.domain.match.RankProfile;
import top.ellan.mahjong.domain.match.RankRoom;
import top.ellan.mahjong.domain.match.RankTier;
import top.ellan.mahjong.spi.PlayerId;

/** Locks the core-owned progression policy: packs report placement and score, the core ranks. */
class RankProgressionTest {
    private static final PlayerId EAST = new PlayerId(UUID.randomUUID());
    private static final PlayerId SOUTH = new PlayerId(UUID.randomUUID());
    private static final PlayerId WEST = new PlayerId(UUID.randomUUID());
    private static final PlayerId NORTH = new PlayerId(UUID.randomUUID());

    private static List<RankProgression.Standing> table() {
        return List.of(
                new RankProgression.Standing(EAST, 1, 40_000),
                new RankProgression.Standing(SOUTH, 2, 28_000),
                new RankProgression.Standing(WEST, 3, 20_000),
                new RankProgression.Standing(NORTH, 4, 12_000));
    }

    @Test
    void anUnknownSeatEntersTheLadderAtNoviceOne() {
        Map<PlayerId, RankLadder.Outcome> outcomes =
                RankProgression.apply(table(), Map.of(), RankRoom.SILVER, RankMatchLength.SOUTH);

        assertEquals(4, outcomes.size());
        assertEquals(RankTier.NOVICE, outcomes.get(EAST).previous().tier());
        assertEquals(1, outcomes.get(EAST).previous().level());
        // Winning promotes; the loser is held at the novice floor rather than demoted.
        assertTrue(outcomes.get(EAST).promoted());
        assertEquals(RankTier.NOVICE, outcomes.get(NORTH).updated().tier());
        assertEquals(0, outcomes.get(NORTH).updated().points());
    }

    @Test
    void everySeatRecordsExactlyOneMoreMatchAtItsOwnPlace() {
        Map<PlayerId, RankLadder.Outcome> outcomes =
                RankProgression.apply(table(), Map.of(), RankRoom.SILVER, RankMatchLength.SOUTH);

        assertEquals(1, outcomes.get(EAST).updated().matches());
        assertEquals(1, outcomes.get(EAST).updated().firstPlaces());
        assertEquals(1, outcomes.get(SOUTH).updated().secondPlaces());
        assertEquals(1, outcomes.get(WEST).updated().thirdPlaces());
        assertEquals(1, outcomes.get(NORTH).updated().fourthPlaces());
    }

    @Test
    void anAllCelestialTableDoublesTheSpSwing() {
        RankProfile celestial = new RankProfile(RankTier.CELESTIAL, 2, 100, 500, 200, 150, 90, 60);
        Map<PlayerId, RankProfile> allCelestial = Map.of(
                EAST, celestial, SOUTH, celestial, WEST, celestial, NORTH, celestial);
        Map<PlayerId, RankProfile> mixed = Map.of(
                EAST, celestial,
                SOUTH, celestial,
                WEST, celestial,
                NORTH, new RankProfile(RankTier.SAINT, 3, 4_500, 400, 200, 100, 60, 40));

        int doubled = RankProgression.apply(
                        table(), allCelestial, RankRoom.THRONE, RankMatchLength.SOUTH)
                .get(EAST)
                .pointChange();
        int single = RankProgression.apply(table(), mixed, RankRoom.THRONE, RankMatchLength.SOUTH)
                .get(EAST)
                .pointChange();

        assertEquals(10, doubled);
        assertEquals(5, single);
    }

    @Test
    void theWholeFieldFeedsTheStrongerTableBonus() {
        RankProfile beginner = RankProfile.initial();
        RankProfile saint = new RankProfile(RankTier.SAINT, 3, 4_500, 400, 200, 100, 60, 40);
        Map<PlayerId, RankProfile> weakField = Map.of(
                EAST, beginner, SOUTH, beginner, WEST, beginner, NORTH, beginner);
        Map<PlayerId, RankProfile> strongField = Map.of(
                EAST, beginner, SOUTH, saint, WEST, saint, NORTH, saint);

        int againstBeginners = RankProgression.apply(
                        table(), weakField, RankRoom.SILVER, RankMatchLength.SOUTH)
                .get(EAST)
                .pointChange();
        int againstSaints = RankProgression.apply(
                        table(), strongField, RankRoom.SILVER, RankMatchLength.SOUTH)
                .get(EAST)
                .pointChange();

        assertTrue(againstSaints > againstBeginners);
        assertNotEquals(againstSaints, againstBeginners);
    }

    @Test
    void ruleReportedScoresAreNotTrustedToFitTheLadder() {
        List<RankProgression.Standing> absurd = List.of(
                new RankProgression.Standing(EAST, 1, Long.MAX_VALUE),
                new RankProgression.Standing(SOUTH, 2, Long.MIN_VALUE),
                new RankProgression.Standing(WEST, 3, 20_000),
                new RankProgression.Standing(NORTH, 4, 12_000));

        Map<PlayerId, RankLadder.Outcome> outcomes =
                RankProgression.apply(absurd, Map.of(), RankRoom.SILVER, RankMatchLength.SOUTH);

        assertEquals(4, outcomes.size());
        assertTrue(outcomes.get(EAST).promoted());
    }

    @Test
    void malformedInputIsRejectedRatherThanSilentlyRanked() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RankProgression.Standing(EAST, 0, 25_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RankProgression.Standing(EAST, 5, 25_000));
        assertThrows(
                NullPointerException.class,
                () -> RankProgression.apply(table(), Map.of(), null, RankMatchLength.SOUTH));
    }
}
