package top.ellan.mahjong.domain.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Locks the 1.5.0 Mahjong Soul ladder: point maths, promotion, demotion and celestial SP. */
class RankLadderTest {

    @Test
    void aNewcomerStartsAtNoviceOneWithNoProgress() {
        RankProfile initial = RankProfile.initial();

        assertEquals(RankTier.NOVICE, initial.tier());
        assertEquals(1, initial.level());
        assertEquals(0, initial.points());
        assertEquals(20, initial.nextThreshold());
        assertEquals(20, initial.promotionRemaining());
        assertTrue(initial.averagePlace().isEmpty());
    }

    @Test
    void pointsCombineScoreDifferenceUmaAndRoomAward() {
        // Silver south, first place, 35000 points: (35000-25000)/1000 + 15 uma + 40 room = 65.
        RankLadder.Outcome outcome = RankLadder.applyMatch(
                RankProfile.initial(),
                RankRoom.SILVER,
                RankMatchLength.SOUTH,
                1,
                35_000,
                false);

        assertEquals(65, outcome.pointChange());
        assertTrue(outcome.promoted());
    }

    @Test
    void promotionCascadesThroughStagesWhoseEntryEqualsTheirThreshold() {
        // Novice 2 and 3 enter at exactly their own promotion points (80/80 and 200/200), so a
        // single strong result carries straight through Novice into Adept 1: 65 points clears
        // Novice 1 (20) with 45 overflow, and that 45 rides on top of each following entry value.
        RankLadder.Outcome outcome = RankLadder.applyMatch(
                RankProfile.initial(),
                RankRoom.SILVER,
                RankMatchLength.SOUTH,
                1,
                35_000,
                false);

        assertEquals(RankTier.ADEPT, outcome.updated().tier());
        assertEquals(1, outcome.updated().level());
        assertEquals(345, outcome.updated().points());
        assertTrue(outcome.promoted());
    }

    @Test
    void noviceAndAdeptOneNeverDemote() {
        RankProfile noviceThree = new RankProfile(RankTier.NOVICE, 3, 0, 5, 0, 0, 0, 5);
        RankLadder.Outcome novice = RankLadder.applyMatch(
                noviceThree, RankRoom.SILVER, RankMatchLength.SOUTH, 4, 5_000, false);
        assertEquals(0, novice.updated().points());
        assertEquals(RankTier.NOVICE, novice.updated().tier());
        assertEquals(3, novice.updated().level());
        assertFalse(novice.demoted());

        RankProfile adeptOne = new RankProfile(RankTier.ADEPT, 1, 300, 5, 0, 0, 0, 5);
        RankLadder.Outcome adept = RankLadder.applyMatch(
                adeptOne, RankRoom.SILVER, RankMatchLength.SOUTH, 4, 0, false);
        assertEquals(RankTier.ADEPT, adept.updated().tier());
        assertEquals(1, adept.updated().level());
        assertFalse(adept.demoted());
    }

    @Test
    void aNegativeTotalBorrowsFromTheStageBelow() {
        // Adept 2 at 5 points loses 25/1000 + 15 uma + 40 penalty; the deficit drops it to Adept 1.
        RankProfile adeptTwo = new RankProfile(RankTier.ADEPT, 2, 5, 10, 1, 2, 3, 4);
        RankLadder.Outcome outcome = RankLadder.applyMatch(
                adeptTwo, RankRoom.SILVER, RankMatchLength.SOUTH, 4, 25_000, false);

        assertTrue(outcome.demoted());
        assertEquals(RankTier.ADEPT, outcome.updated().tier());
        assertEquals(1, outcome.updated().level());
        // Adept 1 promotes at 600, so the shortfall is taken off that ceiling.
        assertEquals(600 + 5 - 55, outcome.updated().points());
    }

    @Test
    void clearingSaintThreePromotesIntoCelestial() {
        RankProfile saintThree = new RankProfile(RankTier.SAINT, 3, 8_990, 400, 200, 100, 60, 40);
        RankLadder.Outcome outcome = RankLadder.applyMatch(
                saintThree, RankRoom.THRONE, RankMatchLength.SOUTH, 1, 40_000, false);

        assertEquals(RankTier.CELESTIAL, outcome.updated().tier());
        assertEquals(1, outcome.updated().level());
        assertEquals(RankProfile.CELESTIAL_START_POINTS, outcome.updated().points());
        assertTrue(outcome.updated().isCelestial());
    }

    @Test
    void celestialUsesFlatSpDeltasAndDoublesInAnAllCelestialTable() {
        RankProfile celestial =
                new RankProfile(RankTier.CELESTIAL, 1, 100, 500, 200, 150, 90, 60);

        assertEquals(
                5,
                RankLadder.applyMatch(
                                celestial, RankRoom.THRONE, RankMatchLength.SOUTH, 1, 40_000, false)
                        .pointChange());
        assertEquals(
                10,
                RankLadder.applyMatch(
                                celestial, RankRoom.THRONE, RankMatchLength.SOUTH, 1, 40_000, true)
                        .pointChange());
        assertEquals(
                3,
                RankLadder.applyMatch(
                                celestial, RankRoom.THRONE, RankMatchLength.EAST, 1, 40_000, false)
                        .pointChange());
    }

    @Test
    void celestialLevelsUpAtTwoHundredAndFallsBackToSaintThreeAtZero() {
        RankProfile nearLevelUp =
                new RankProfile(RankTier.CELESTIAL, 1, 197, 500, 200, 150, 90, 60);
        RankLadder.Outcome up = RankLadder.applyMatch(
                nearLevelUp, RankRoom.THRONE, RankMatchLength.SOUTH, 1, 40_000, false);
        assertEquals(2, up.updated().level());
        assertEquals(RankProfile.CELESTIAL_START_POINTS + 2, up.updated().points());

        RankProfile nearDrop = new RankProfile(RankTier.CELESTIAL, 1, 3, 500, 200, 150, 90, 60);
        RankLadder.Outcome down = RankLadder.applyMatch(
                nearDrop, RankRoom.THRONE, RankMatchLength.SOUTH, 4, 5_000, false);
        assertEquals(RankTier.SAINT, down.updated().tier());
        assertEquals(3, down.updated().level());
        assertTrue(down.demoted());
    }

    @Test
    void aStrongerTableEarnsABoundedBonusAndTheWeakerOneAMalus() {
        RankProfile self = new RankProfile(RankTier.ADEPT, 1, 300, 50, 10, 15, 15, 10);
        List<RankProfile> strongField = List.of(
                self,
                new RankProfile(RankTier.SAINT, 3, 4_500, 400, 200, 100, 60, 40),
                new RankProfile(RankTier.SAINT, 3, 4_500, 400, 200, 100, 60, 40),
                new RankProfile(RankTier.SAINT, 3, 4_500, 400, 200, 100, 60, 40));

        int alone = RankLadder.applyMatch(
                        self, RankRoom.SILVER, RankMatchLength.SOUTH, 1, 35_000, false)
                .pointChange();
        int againstSaints = RankLadder.applyMatch(
                        self, RankRoom.SILVER, RankMatchLength.SOUTH, 1, 35_000, false, strongField)
                .pointChange();

        assertTrue(againstSaints > alone);
        assertTrue(againstSaints - alone <= 18, "the south-game bonus is capped at 18");
    }

    @Test
    void placementStatisticsFollowEveryRecordedMatch() {
        RankProfile profile = new RankProfile(RankTier.EXPERT, 1, 600, 4, 1, 1, 1, 1);

        assertEquals(2.5D, profile.averagePlace().orElseThrow(), 1.0e-9);
        assertEquals(25.0D, profile.firstRate(), 1.0e-9);
        assertEquals(50.0D, profile.topTwoRate(), 1.0e-9);
        assertEquals(25.0D, profile.fourthRate(), 1.0e-9);

        RankProfile after = RankLadder.applyMatch(
                        profile, RankRoom.GOLD, RankMatchLength.SOUTH, 1, 35_000, false)
                .updated();
        assertEquals(5, after.matches());
        assertEquals(2, after.firstPlaces());
    }

    @Test
    void unsupportedStagesAndPlacesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RankStage.of(RankTier.CELESTIAL, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RankLadder.applyMatch(
                        RankProfile.initial(), RankRoom.SILVER, RankMatchLength.SOUTH, 5, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RankProfile(RankTier.NOVICE, 1, 0, 1, 1, 1, 0, 0));
    }

    @Test
    void roomNamesParseCaseInsensitivelyAndFallBackToSilver() {
        assertEquals(RankRoom.THRONE, RankRoom.parse(" throne "));
        assertEquals(RankRoom.SILVER, RankRoom.parse(null));
        assertEquals(RankRoom.SILVER, RankRoom.parse("  "));
        assertEquals(120, RankRoom.THRONE.first(RankMatchLength.SOUTH));
        assertEquals(60, RankRoom.THRONE.first(RankMatchLength.EAST));
    }
}
