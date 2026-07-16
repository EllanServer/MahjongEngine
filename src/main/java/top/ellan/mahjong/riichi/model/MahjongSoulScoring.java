package top.ellan.mahjong.riichi.model;

public final class MahjongSoulScoring {
    public static final int RETURN_POINTS = 25000;
    private static final double[] PLACEMENT_BONUS = {15.0D, 5.0D, -5.0D, -15.0D};

    private MahjongSoulScoring() {
    }

    public static double placementBonus(int place) {
        int index = Math.max(1, Math.min(place, PLACEMENT_BONUS.length)) - 1;
        return PLACEMENT_BONUS[index];
    }

    public static double gameScore(int points, int place) {
        return ((points - RETURN_POINTS) / 1000.0D) + placementBonus(place);
    }
}
