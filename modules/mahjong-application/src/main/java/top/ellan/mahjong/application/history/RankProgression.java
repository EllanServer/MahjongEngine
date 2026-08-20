package top.ellan.mahjong.application.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.match.RankLadder;
import top.ellan.mahjong.domain.match.RankMatchLength;
import top.ellan.mahjong.domain.match.RankProfile;
import top.ellan.mahjong.domain.match.RankRoom;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Applies the shared rank ladder to one finished match.
 *
 * <p>Rank progression is common to every variant, so it lives here rather than in a rule pack. A pack
 * only reports what is rule-specific — each seat's terminal score and placement — and this class
 * converts that into stage movement. All three packs currently report {@code rankingPointsMilli} as
 * their score restated in thousandths, which carries no ladder information and is therefore not used
 * for progression.
 *
 * <p>Every method is a pure function over immutable values, so it is safe to call from whichever
 * thread already owns the terminal-result transaction.
 */
public final class RankProgression {
    private RankProgression() {}

    /** One seat's rule-reported terminal standing. */
    public record Standing(PlayerId playerId, int placement, long score) {
        public Standing {
            Objects.requireNonNull(playerId, "playerId");
            if (placement < 1 || placement > 4) {
                throw new IllegalArgumentException("A ranked placement is between one and four");
            }
        }
    }

    /**
     * Moves every listed seat along the ladder.
     *
     * @param standings rule-reported placements and scores, one per seat
     * @param current each seat's stored profile; a missing seat starts from {@link
     *     RankProfile#initial()}
     * @param room configured room tier for this match length
     * @param length whether the match was east-only or east-south
     * @return one outcome per standing, in the order the standings were given
     */
    public static Map<PlayerId, RankLadder.Outcome> apply(
            List<Standing> standings,
            Map<PlayerId, RankProfile> current,
            RankRoom room,
            RankMatchLength length) {
        Objects.requireNonNull(standings, "standings");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(length, "length");

        List<RankProfile> field = new ArrayList<>(standings.size());
        for (Standing standing : standings) {
            field.add(profileOf(current, standing.playerId()));
        }
        boolean allCelestial = !field.isEmpty() && field.stream().allMatch(RankProfile::isCelestial);

        Map<PlayerId, RankLadder.Outcome> outcomes = new LinkedHashMap<>();
        for (int index = 0; index < standings.size(); index++) {
            Standing standing = standings.get(index);
            outcomes.put(
                    standing.playerId(),
                    RankLadder.applyMatch(
                            field.get(index),
                            room,
                            length,
                            standing.placement(),
                            clampScore(standing.score()),
                            allCelestial,
                            field));
        }
        return outcomes;
    }

    private static RankProfile profileOf(Map<PlayerId, RankProfile> current, PlayerId playerId) {
        RankProfile stored = current.get(playerId);
        return stored == null ? RankProfile.initial() : stored;
    }

    /** The ladder works in table points; a rule reporting an absurd score must not overflow it. */
    private static int clampScore(long score) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, score));
    }
}
