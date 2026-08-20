package top.ellan.mahjong.domain.match;

import java.util.List;

/**
 * The 1.5.0 Mahjong Soul rank ladder: how one finished match moves a player's stage.
 *
 * <p>A promotable stage carries points over on promotion and borrows from the stage below on
 * demotion. Novice never demotes and neither does Adept 1, so a beginner cannot fall out of the
 * ladder. Celestial is open ended: it repeats a 200-point level and drops back to Saint 3 only when a
 * first-level celestial reaches zero.
 */
public final class RankLadder {
    private RankLadder() {}

    /** One player's ranked outcome, before and after the match. */
    public record Outcome(RankProfile previous, RankProfile updated, int pointChange) {
        public Outcome {
            if (previous == null || updated == null) {
                throw new IllegalArgumentException("Both profiles are required");
            }
        }

        public boolean promoted() {
            return updated.tier().ordinal() > previous.tier().ordinal()
                    || updated.tier() == previous.tier() && updated.level() > previous.level();
        }

        public boolean demoted() {
            return updated.tier().ordinal() < previous.tier().ordinal()
                    || updated.tier() == previous.tier() && updated.level() < previous.level();
        }
    }

    public static Outcome applyMatch(
            RankProfile profile,
            RankRoom room,
            RankMatchLength length,
            int place,
            int rawScore,
            boolean allPlayersCelestial) {
        return applyMatch(profile, room, length, place, rawScore, allPlayersCelestial, List.of());
    }

    /**
     * Applies one match. {@code field} carries every seat's profile so a player meeting a stronger
     * table earns a bounded bonus; pass an empty list to skip that adjustment.
     */
    public static Outcome applyMatch(
            RankProfile profile,
            RankRoom room,
            RankMatchLength length,
            int place,
            int rawScore,
            boolean allPlayersCelestial,
            List<RankProfile> field) {
        requireInputs(profile, room, length, place);
        if (profile.isCelestial()) {
            return applyCelestial(profile, length, place, allPlayersCelestial);
        }
        RankStage stage = RankStage.of(profile.tier(), profile.level());
        int pointChange = (int)
                        Math.ceil(
                                (rawScore - 25_000) / 1_000.0D
                                        + uma(place)
                                        + roomPoints(room, stage, length, place))
                + fieldAdjustment(profile, field, length, place);
        return new Outcome(
                profile, applyPromotableTransition(profile, stage, pointChange, place), pointChange);
    }

    private static RankProfile applyPromotableTransition(
            RankProfile profile, RankStage stage, int pointChange, int place) {
        int index = RankStage.indexOf(stage.tier(), stage.level());
        int points = profile.points() + pointChange;
        RankTier tier = profile.tier();
        int level = profile.level();
        while (true) {
            RankStage current = RankStage.at(index);
            if (points >= current.upgradePoints()) {
                if (index == RankStage.count() - 1) {
                    return profile.atStage(
                                    RankTier.CELESTIAL, 1, RankProfile.CELESTIAL_START_POINTS)
                            .withPlacement(place);
                }
                int overflow = points - current.upgradePoints();
                RankStage next = RankStage.at(++index);
                tier = next.tier();
                level = next.level();
                points = next.initialPoints() + overflow;
                continue;
            }
            if (points < 0) {
                if (tier == RankTier.NOVICE || tier == RankTier.ADEPT && level == 1) {
                    points = 0;
                    break;
                }
                RankStage previous = RankStage.at(--index);
                tier = previous.tier();
                level = previous.level();
                points = previous.upgradePoints() + points;
                continue;
            }
            break;
        }
        return profile.atStage(tier, level, points).withPlacement(place);
    }

    private static Outcome applyCelestial(
            RankProfile profile, RankMatchLength length, int place, boolean allPlayersCelestial) {
        int delta = celestialDelta(length, place) * (allPlayersCelestial ? 2 : 1);
        int level = Math.max(1, profile.level());
        int points = profile.points() + delta;
        while (points >= RankProfile.CELESTIAL_UPGRADE_POINTS) {
            points = RankProfile.CELESTIAL_START_POINTS
                    + (points - RankProfile.CELESTIAL_UPGRADE_POINTS);
            level++;
        }
        while (points <= 0 && level > 1) {
            points = RankProfile.CELESTIAL_START_POINTS + points;
            level--;
        }
        RankProfile updated = points <= 0 && level == 1
                ? profile.atStage(RankTier.SAINT, 3, 0).withPlacement(place)
                : profile.atStage(RankTier.CELESTIAL, level, points).withPlacement(place);
        return new Outcome(profile, updated, delta);
    }

    private static int celestialDelta(RankMatchLength length, int place) {
        return switch (length) {
            case EAST -> switch (place) {
                case 1 -> 3;
                case 2 -> 1;
                case 3 -> -1;
                default -> -3;
            };
            case SOUTH -> switch (place) {
                case 1 -> 5;
                case 2 -> 2;
                case 3 -> -2;
                default -> -5;
            };
        };
    }

    private static int uma(int place) {
        return switch (place) {
            case 1 -> 15;
            case 2 -> 5;
            case 3 -> -5;
            default -> -15;
        };
    }

    private static int roomPoints(
            RankRoom room, RankStage stage, RankMatchLength length, int place) {
        return switch (place) {
            case 1 -> room.first(length);
            case 2 -> room.second(length);
            case 3 -> 0;
            default -> -stage.penalty(length);
        };
    }

    /** Bounded bonus for beating a stronger table, or malus for losing to a weaker one. */
    private static int fieldAdjustment(
            RankProfile profile, List<RankProfile> field, RankMatchLength length, int place) {
        if (field == null || field.size() < 4) {
            return 0;
        }
        double opponentTotal = 0.0D;
        int opponents = 0;
        for (RankProfile other : field) {
            if (other == null || other == profile) {
                continue;
            }
            opponentTotal += strength(other);
            opponents++;
        }
        if (opponents == 0) {
            return 0;
        }
        double stageDifference = (opponentTotal / opponents - strength(profile)) / 100.0D;
        double placeWeight = place == 1 || place == 4 ? 1.0D : 0.55D;
        double lengthWeight = length == RankMatchLength.SOUTH ? 1.0D : 0.6D;
        int adjustment = (int) Math.round(stageDifference * 10.0D * placeWeight * lengthWeight);
        int cap = length == RankMatchLength.SOUTH ? 18 : 11;
        return Math.max(-cap, Math.min(cap, adjustment));
    }

    private static double strength(RankProfile profile) {
        if (profile.isCelestial()) {
            return RankStage.count() * 100.0D
                    + Math.max(0, profile.level() - 1) * 100.0D
                    + boundedRatio(profile.points(), RankProfile.CELESTIAL_UPGRADE_POINTS) * 100.0D;
        }
        RankStage stage = RankStage.of(profile.tier(), profile.level());
        return RankStage.indexOf(stage.tier(), stage.level()) * 100.0D
                + boundedRatio(profile.points(), stage.upgradePoints()) * 100.0D;
    }

    private static double boundedRatio(int points, int threshold) {
        return threshold <= 0 ? 0.0D : Math.max(0.0D, Math.min(1.0D, points / (double) threshold));
    }

    private static void requireInputs(
            RankProfile profile, RankRoom room, RankMatchLength length, int place) {
        if (profile == null || room == null || length == null) {
            throw new IllegalArgumentException("Profile, room and length are required");
        }
        if (place < 1 || place > 4) {
            throw new IllegalArgumentException("A ranked place is between one and four");
        }
    }
}
