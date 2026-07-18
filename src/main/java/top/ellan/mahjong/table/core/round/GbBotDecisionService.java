package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.gb.jni.GbFanEntry;
import top.ellan.mahjong.gb.jni.GbTingCandidate;
import top.ellan.mahjong.gb.jni.GbTingResponse;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.ReactionResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kotlin.Pair;

final class GbBotDecisionService {
    private static final int TILE_KIND_COUNT = MahjongTile.values().length;

    private final int minimumFan;

    GbBotDecisionService(int minimumFan) {
        this.minimumFan = Math.max(0, minimumFan);
    }

    int suggestedDiscardIndex(
        List<MahjongTile> hand,
        List<GbMeldState> melds,
        TingEvaluator tingEvaluator
    ) {
        if (hand == null || hand.isEmpty()) {
            return -1;
        }
        DiscardChoice best = this.bestDiscardChoice(hand, melds, tingEvaluator);
        return best == null ? hand.size() - 1 : best.index();
    }

    ReactionResponse suggestedReaction(
        ReactionOptions options,
        GbTingResponse skipTing,
        MahjongTile claimedTile,
        SeatWind fromSeat,
        SeatWind selfSeat,
        List<MahjongTile> hand,
        List<GbMeldState> melds,
        TingEvaluator tingEvaluator
    ) {
        if (options == null) {
            return ReactionResponses.SKIP;
        }
        if (options.getCanRon()) {
            return ReactionResponses.RON;
        }
        long skipReadyScore = readyScore(skipTing);
        ReactionChoice best = new ReactionChoice(ReactionResponses.SKIP, skipReadyScore, 0);
        if (options.getCanPon()) {
            ReactionChoice candidate = this.evaluatePung(claimedTile, fromSeat, selfSeat, hand, melds, tingEvaluator);
            if (candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        if (options.getCanMinkan()) {
            ReactionChoice candidate = this.evaluateOpenKong(claimedTile, fromSeat, selfSeat, hand, melds, tingEvaluator);
            if (candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        for (Pair<top.ellan.mahjong.riichi.model.MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> pair : options.getChiiPairs()) {
            ReactionChoice candidate = this.evaluateChow(claimedTile, fromSeat, hand, melds, pair, tingEvaluator);
            if (candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        return best.readyScore() > skipReadyScore ? best.response() : ReactionResponses.SKIP;
    }

    String suggestedKanTile(
        List<MahjongTile> hand,
        List<GbMeldState> melds,
        List<String> suggestedKanTiles,
        TingEvaluator tingEvaluator
    ) {
        if (hand == null || hand.isEmpty()) {
            return null;
        }
        long baselineReadyScore = this.readyScoreForBestDiscard(hand, melds, tingEvaluator);
        if (baselineReadyScore > 0) {
            return null;
        }
        KanChoice best = null;
        for (String tileName : suggestedKanTiles) {
            MahjongTile target;
            try {
                target = MahjongTile.valueOf(tileName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            BotState simulated = this.simulateKan(hand, melds, target);
            if (simulated == null) {
                continue;
            }
            long readyScore = readyScore(tingEvaluator.evaluate(simulated.hand(), simulated.melds()));
            if (readyScore <= 0) {
                continue;
            }
            KanChoice candidate = new KanChoice(tileName, readyScore);
            if (best == null || candidate.compareTo(best) > 0) {
                best = candidate;
            }
        }
        return best == null ? null : best.tileName();
    }

    private ReactionChoice evaluatePung(
        MahjongTile claimedTile,
        SeatWind fromSeat,
        SeatWind selfSeat,
        List<MahjongTile> sourceHand,
        List<GbMeldState> sourceMelds,
        TingEvaluator tingEvaluator
    ) {
        List<MahjongTile> hand = new ArrayList<>(sourceHand);
        GbRoundSupport.removeTiles(hand, claimedTile, 2);
        List<GbMeldState> melds = new ArrayList<>(sourceMelds);
        melds.add(GbMeldState.pung(claimedTile, fromSeat, selfSeat));
        return this.bestClaimDiscard(hand, melds, ReactionResponses.PON, tingEvaluator);
    }

    private ReactionChoice evaluateOpenKong(
        MahjongTile claimedTile,
        SeatWind fromSeat,
        SeatWind selfSeat,
        List<MahjongTile> sourceHand,
        List<GbMeldState> sourceMelds,
        TingEvaluator tingEvaluator
    ) {
        List<MahjongTile> hand = new ArrayList<>(sourceHand);
        GbRoundSupport.removeTiles(hand, claimedTile, 3);
        List<GbMeldState> melds = new ArrayList<>(sourceMelds);
        melds.add(GbMeldState.openKong(claimedTile, fromSeat, selfSeat));
        long readyScore = readyScore(tingEvaluator.evaluate(hand, melds));
        return new ReactionChoice(ReactionResponses.MINKAN, readyScore, 0);
    }

    private ReactionChoice evaluateChow(
        MahjongTile claimedTile,
        SeatWind fromSeat,
        List<MahjongTile> sourceHand,
        List<GbMeldState> sourceMelds,
        Pair<top.ellan.mahjong.riichi.model.MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> pair,
        TingEvaluator tingEvaluator
    ) {
        List<MahjongTile> hand = new ArrayList<>(sourceHand);
        MahjongTile first = GbRoundSupport.fromRiichiTile(pair.getFirst());
        MahjongTile second = GbRoundSupport.fromRiichiTile(pair.getSecond());
        GbRoundSupport.removeTiles(hand, first, 1);
        GbRoundSupport.removeTiles(hand, second, 1);
        List<GbMeldState> melds = new ArrayList<>(sourceMelds);
        melds.add(GbMeldState.chow(claimedTile, first, second, fromSeat));
        return this.bestClaimDiscard(hand, melds, ReactionResponses.chii(pair), tingEvaluator);
    }

    private ReactionChoice bestClaimDiscard(
        List<MahjongTile> hand,
        List<GbMeldState> melds,
        ReactionResponse response,
        TingEvaluator tingEvaluator
    ) {
        DiscardChoice best = this.bestDiscardChoice(hand, melds, tingEvaluator);
        return best == null ? new ReactionChoice(response, 0, 0) : new ReactionChoice(response, best.readyScore(), best.discardPreference());
    }

    private long readyScoreForBestDiscard(
        List<MahjongTile> hand,
        List<GbMeldState> melds,
        TingEvaluator tingEvaluator
    ) {
        DiscardChoice best = this.bestDiscardChoice(hand, melds, tingEvaluator);
        return best == null ? 0 : best.readyScore();
    }

    private DiscardChoice bestDiscardChoice(
        List<MahjongTile> hand,
        List<GbMeldState> melds,
        TingEvaluator tingEvaluator
    ) {
        if (hand == null || hand.isEmpty()) {
            return null;
        }
        int bestIndex = -1;
        long bestReadyScore = 0;
        int bestDiscardPreference = 0;
        GbTingResponse[] tingMemo = new GbTingResponse[TILE_KIND_COUNT];
        MahjongTile previousDiscarded = null;
        int previousDiscardPreference = 0;
        List<MahjongTile> remaining = new ArrayList<>(hand.size() - 1);
        for (int i = 0; i < hand.size(); i++) {
            MahjongTile discarded = hand.get(i);
            int discardedOrdinal = discarded.ordinal();
            GbTingResponse ting = tingMemo[discardedOrdinal];
            boolean tingMemoHit = ting != null;
            if (ting == null) {
                remaining.clear();
                for (int j = 0; j < hand.size(); j++) {
                    if (j != i) {
                        remaining.add(hand.get(j));
                    }
                }
                ting = tingEvaluator.evaluate(remaining, melds);
                if (ting != null) {
                    tingMemo[discardedOrdinal] = ting;
                }
            }
            long candidateReadyScore = readyScore(ting);
            int candidateDiscardPreference = tingMemoHit && discarded == previousDiscarded
                ? previousDiscardPreference
                : discardPreference(hand, discarded);
            previousDiscarded = discarded;
            previousDiscardPreference = candidateDiscardPreference;
            if (bestIndex < 0
                || candidateReadyScore > bestReadyScore
                || (candidateReadyScore == bestReadyScore && candidateDiscardPreference > bestDiscardPreference)) {
                bestIndex = i;
                bestReadyScore = candidateReadyScore;
                bestDiscardPreference = candidateDiscardPreference;
            }
        }
        return bestIndex < 0 ? null : new DiscardChoice(bestIndex, bestReadyScore, bestDiscardPreference);
    }

    private BotState simulateKan(List<MahjongTile> sourceHand, List<GbMeldState> sourceMelds, MahjongTile target) {
        List<MahjongTile> hand = new ArrayList<>(sourceHand);
        List<GbMeldState> melds = new ArrayList<>(sourceMelds);
        if (GbRoundSupport.countMatchingTiles(hand, target) >= 4) {
            GbRoundSupport.removeTiles(hand, target, 4);
            melds.add(GbMeldState.ankan(target));
            return new BotState(List.copyOf(hand), List.copyOf(melds));
        }
        for (int i = 0; i < melds.size(); i++) {
            GbMeldState meld = melds.get(i);
            if (meld.type() == GbMeldType.PUNG
                && GbRoundSupport.sameKind(meld.baseTile(), target)
                && GbRoundSupport.countMatchingTiles(hand, target) >= 1) {
                GbRoundSupport.removeTiles(hand, target, 1);
                melds.set(i, meld.toAddedKong(target));
                return new BotState(List.copyOf(hand), List.copyOf(melds));
            }
        }
        return null;
    }

    long readyScore(GbTingResponse response) {
        if (response == null || !response.getValid() || response.getWaits().isEmpty()) {
            return 0;
        }
        long qualifiedWaits = 0;
        long bestFan = 0;
        long totalFan = 0;
        for (GbTingCandidate candidate : response.getWaits()) {
            int candidateFan = candidateTotalFan(candidate);
            if (candidateFan < this.minimumFan) {
                continue;
            }
            qualifiedWaits++;
            bestFan = Math.max(bestFan, candidateFan);
            totalFan += candidateFan;
        }
        if (qualifiedWaits == 0) {
            return 0;
        }
        return 1_000_000L + bestFan * 10_000L + qualifiedWaits * 100L + totalFan;
    }

    private static int candidateTotalFan(GbTingCandidate candidate) {
        if (candidate.getTotalFan() != null) {
            return candidate.getTotalFan();
        }
        int total = 0;
        for (GbFanEntry fan : candidate.getFans()) {
            total += fan.getFan() * fan.getCount();
        }
        return total;
    }

    static int discardPreference(List<MahjongTile> hand, MahjongTile tile) {
        int duplicates = 0;
        boolean hasPrev = false;
        boolean hasNext = false;
        boolean hasPrevPrev = false;
        boolean hasNextNext = false;
        for (MahjongTile candidate : hand) {
            if (candidate == tile) {
                duplicates++;
                continue;
            }
            if (GbRoundSupport.isHonor(candidate) || GbRoundSupport.isHonor(tile) || candidate.name().charAt(0) != tile.name().charAt(0)) {
                continue;
            }
            int delta = GbRoundSupport.tileNumber(candidate) - GbRoundSupport.tileNumber(tile);
            if (delta == -2) {
                hasPrevPrev = true;
            }
            if (delta == -1) {
                hasPrev = true;
            }
            if (delta == 1) {
                hasNext = true;
            }
            if (delta == 2) {
                hasNextNext = true;
            }
        }
        int score = 0;
        if (GbRoundSupport.isHonor(tile) || GbRoundSupport.tileNumber(tile) == 1 || GbRoundSupport.tileNumber(tile) == 9) {
            score += 3;
        }
        if (duplicates >= 2) {
            score -= 4;
        }
        if (hasPrev) {
            score -= 2;
        }
        if (hasNext) {
            score -= 2;
        }
        if (hasPrevPrev) {
            score -= 1;
        }
        if (hasNextNext) {
            score -= 1;
        }
        return score;
    }

    interface TingEvaluator {
        GbTingResponse evaluate(List<MahjongTile> hand, List<GbMeldState> melds);
    }

    private record BotState(List<MahjongTile> hand, List<GbMeldState> melds) {
    }

    private record DiscardChoice(int index, long readyScore, int discardPreference) implements Comparable<DiscardChoice> {
        @Override
        public int compareTo(DiscardChoice other) {
            int readyComparison = Long.compare(this.readyScore, other.readyScore);
            if (readyComparison != 0) {
                return readyComparison;
            }
            return Integer.compare(this.discardPreference, other.discardPreference);
        }
    }

    private record ReactionChoice(ReactionResponse response, long readyScore, int detailScore) implements Comparable<ReactionChoice> {
        @Override
        public int compareTo(ReactionChoice other) {
            int readyComparison = Long.compare(this.readyScore, other.readyScore);
            if (readyComparison != 0) {
                return readyComparison;
            }
            return Integer.compare(this.detailScore, other.detailScore);
        }
    }

    private record KanChoice(String tileName, long readyScore) implements Comparable<KanChoice> {
        @Override
        public int compareTo(KanChoice other) {
            return Long.compare(this.readyScore, other.readyScore);
        }
    }
}
