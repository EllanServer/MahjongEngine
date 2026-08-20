package top.ellan.mahjong.domain.match;

import java.util.Locale;

/**
 * Ranked room tiers and the rank points their first and second places award.
 *
 * <p>Values are the 1.5.0 table, which follows the Mahjong Soul room ladder. Third place always
 * awards zero and fourth place is charged the player's own stage penalty, so neither is stored here.
 */
public enum RankRoom {
    BRONZE(10, 20, 5, 10),
    SILVER(20, 40, 10, 20),
    GOLD(40, 80, 20, 40),
    JADE(55, 110, 30, 55),
    THRONE(60, 120, 30, 60);

    private final int eastFirst;
    private final int southFirst;
    private final int eastSecond;
    private final int southSecond;

    RankRoom(int eastFirst, int southFirst, int eastSecond, int southSecond) {
        this.eastFirst = eastFirst;
        this.southFirst = southFirst;
        this.eastSecond = eastSecond;
        this.southSecond = southSecond;
    }

    public int first(RankMatchLength length) {
        return length == RankMatchLength.EAST ? eastFirst : southFirst;
    }

    public int second(RankMatchLength length) {
        return length == RankMatchLength.EAST ? eastSecond : southSecond;
    }

    /** Unknown or missing configuration falls back to the silver room, as in 1.5.0. */
    public static RankRoom parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SILVER;
        }
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
