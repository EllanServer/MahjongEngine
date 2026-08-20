package top.ellan.mahjong.domain.match;

/**
 * One player's ranked standing: their stage, points within it, and placement history.
 *
 * <p>The stage cannot be derived from a running points total, because a promotion resets points to
 * the next stage's entry value. The whole triple must therefore be persisted.
 */
public record RankProfile(
        RankTier tier,
        int level,
        int points,
        int matches,
        int firstPlaces,
        int secondPlaces,
        int thirdPlaces,
        int fourthPlaces) {

    /** Celestial SP is displayed out of 20.0, i.e. one tenth of the stored point value. */
    public static final int CELESTIAL_START_POINTS = 100;

    public static final int CELESTIAL_UPGRADE_POINTS = 200;

    public RankProfile {
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        if (level < 1 || points < 0 || matches < 0) {
            throw new IllegalArgumentException("A rank profile cannot hold negative progress");
        }
        if (firstPlaces < 0 || secondPlaces < 0 || thirdPlaces < 0 || fourthPlaces < 0) {
            throw new IllegalArgumentException("Placement counts cannot be negative");
        }
        if (firstPlaces + secondPlaces + thirdPlaces + fourthPlaces > matches) {
            throw new IllegalArgumentException("Placements cannot exceed the match count");
        }
    }

    /** A newcomer starts at Novice 1 with no points, exactly as in 1.5.0. */
    public static RankProfile initial() {
        return new RankProfile(RankTier.NOVICE, 1, 0, 0, 0, 0, 0, 0);
    }

    public boolean isCelestial() {
        return tier == RankTier.CELESTIAL;
    }

    /** Points needed to leave this stage; celestial repeats a fixed 200-point level. */
    public int nextThreshold() {
        return isCelestial()
                ? CELESTIAL_UPGRADE_POINTS
                : RankStage.of(tier, level).upgradePoints();
    }

    public int promotionRemaining() {
        return Math.max(0, nextThreshold() - points);
    }

    /** Mean finishing position, or an empty result before any ranked match. */
    public java.util.OptionalDouble averagePlace() {
        if (matches <= 0) {
            return java.util.OptionalDouble.empty();
        }
        double weighted =
                firstPlaces + secondPlaces * 2.0D + thirdPlaces * 3.0D + fourthPlaces * 4.0D;
        return java.util.OptionalDouble.of(weighted / matches);
    }

    public double firstRate() {
        return rate(firstPlaces);
    }

    public double topTwoRate() {
        return rate(firstPlaces + secondPlaces);
    }

    public double fourthRate() {
        return rate(fourthPlaces);
    }

    private double rate(int numerator) {
        return matches <= 0 ? 0.0D : numerator * 100.0D / matches;
    }

    /** Records one more finish at the given place without touching stage progress. */
    public RankProfile withPlacement(int place) {
        return new RankProfile(
                tier,
                level,
                points,
                matches + 1,
                firstPlaces + (place == 1 ? 1 : 0),
                secondPlaces + (place == 2 ? 1 : 0),
                thirdPlaces + (place == 3 ? 1 : 0),
                fourthPlaces + (place == 4 ? 1 : 0));
    }

    RankProfile atStage(RankTier nextTier, int nextLevel, int nextPoints) {
        return new RankProfile(
                nextTier,
                nextLevel,
                nextPoints,
                matches,
                firstPlaces,
                secondPlaces,
                thirdPlaces,
                fourthPlaces);
    }
}
