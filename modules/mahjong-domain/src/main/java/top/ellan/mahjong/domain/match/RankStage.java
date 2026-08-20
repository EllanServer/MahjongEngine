package top.ellan.mahjong.domain.match;

import java.util.List;

/**
 * One promotable rank stage: a tier at one level, with its entry points, promotion threshold and
 * fourth-place penalty.
 *
 * <p>The table is the authoritative 1.5.0 ladder. Celestial is deliberately absent: it is open ended
 * and handled by {@link RankLadder} instead of by a fixed stage.
 */
public record RankStage(
        RankTier tier,
        int level,
        int initialPoints,
        int upgradePoints,
        int eastPenalty,
        int southPenalty) {

    private static final List<RankStage> STAGES = List.of(
            new RankStage(RankTier.NOVICE, 1, 0, 20, 0, 0),
            new RankStage(RankTier.NOVICE, 2, 80, 80, 0, 0),
            new RankStage(RankTier.NOVICE, 3, 200, 200, 0, 0),
            new RankStage(RankTier.ADEPT, 1, 300, 600, 10, 20),
            new RankStage(RankTier.ADEPT, 2, 400, 800, 20, 40),
            new RankStage(RankTier.ADEPT, 3, 500, 1000, 30, 60),
            new RankStage(RankTier.EXPERT, 1, 600, 1200, 40, 80),
            new RankStage(RankTier.EXPERT, 2, 700, 1400, 50, 100),
            new RankStage(RankTier.EXPERT, 3, 1000, 2000, 60, 120),
            new RankStage(RankTier.MASTER, 1, 1400, 2800, 80, 165),
            new RankStage(RankTier.MASTER, 2, 1600, 3200, 90, 180),
            new RankStage(RankTier.MASTER, 3, 1800, 3600, 100, 195),
            new RankStage(RankTier.SAINT, 1, 2000, 4000, 110, 210),
            new RankStage(RankTier.SAINT, 2, 3000, 6000, 120, 225),
            new RankStage(RankTier.SAINT, 3, 4500, 9000, 130, 240));

    public RankStage {
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        if (level < 1 || level > 3) {
            throw new IllegalArgumentException("A promotable stage has levels one to three");
        }
    }

    public int penalty(RankMatchLength length) {
        return length == RankMatchLength.EAST ? eastPenalty : southPenalty;
    }

    static int count() {
        return STAGES.size();
    }

    static RankStage at(int index) {
        return STAGES.get(index);
    }

    static int indexOf(RankTier tier, int level) {
        for (int index = 0; index < STAGES.size(); index++) {
            RankStage stage = STAGES.get(index);
            if (stage.tier() == tier && stage.level() == level) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unsupported rank stage: " + tier + " " + level);
    }

    /** The promotable stage for a tier and level; celestial has no fixed stage. */
    public static RankStage of(RankTier tier, int level) {
        return STAGES.get(indexOf(tier, level));
    }
}
