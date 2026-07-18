package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.model.MahjongTile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SichuanHuEvaluator {
    private static final MahjongTile[] SICHUAN_TILES = {
        MahjongTile.M1, MahjongTile.M2, MahjongTile.M3, MahjongTile.M4, MahjongTile.M5, MahjongTile.M6, MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
        MahjongTile.P1, MahjongTile.P2, MahjongTile.P3, MahjongTile.P4, MahjongTile.P5, MahjongTile.P6, MahjongTile.P7, MahjongTile.P8, MahjongTile.P9,
        MahjongTile.S1, MahjongTile.S2, MahjongTile.S3, MahjongTile.S4, MahjongTile.S5, MahjongTile.S6, MahjongTile.S7, MahjongTile.S8, MahjongTile.S9
    };
    private static final int[] TILE_INDEX_BY_ORDINAL = createTileIndexLookup();

    private SichuanHuEvaluator() {
    }

    static boolean canWin(List<MahjongTile> concealedHand, MahjongTile winningTile, int fixedMeldCount) {
        int requiredMelds = requiredMelds(concealedHand, fixedMeldCount);
        if (requiredMelds < 0) {
            return false;
        }
        int[] counts = buildCounts(concealedHand);
        int winningIndex = tileIndex(winningTile);
        if (counts == null || winningIndex < 0 || counts[winningIndex] >= 4) {
            return false;
        }
        counts[winningIndex]++;
        return isWinningCounts(counts, requiredMelds, fixedMeldCount == 0);
    }

    static Result evaluate(List<MahjongTile> concealedHand, MahjongTile winningTile, int fixedMeldCount) {
        int requiredMelds = requiredMelds(concealedHand, fixedMeldCount);
        if (requiredMelds < 0) {
            return Result.invalid();
        }
        int[] counts = buildCounts(concealedHand);
        int winningIndex = tileIndex(winningTile);
        if (counts == null || winningIndex < 0 || counts[winningIndex] >= 4) {
            return Result.invalid();
        }
        counts[winningIndex]++;
        if (fixedMeldCount == 0 && isSevenPairs(counts)) {
            return new Result(true, true, List.of());
        }
        List<MeldShape> bestShape = null;
        int bestSequenceCount = Integer.MAX_VALUE;
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] < 2) {
                continue;
            }
            counts[index] -= 2;
            List<MeldShape> melds = new ArrayList<>();
            if (collectMelds(counts, requiredMelds, melds)) {
                counts[index] += 2;
                int sequenceCount = sequenceCount(melds);
                if (sequenceCount < bestSequenceCount) {
                    bestSequenceCount = sequenceCount;
                    bestShape = List.copyOf(melds);
                }
                continue;
            }
            counts[index] += 2;
        }
        return bestShape == null ? Result.invalid() : new Result(true, false, bestShape);
    }

    static List<MahjongTile> waitingTiles(List<MahjongTile> concealedHand, int fixedMeldCount) {
        int requiredMelds = requiredMelds(concealedHand, fixedMeldCount);
        if (requiredMelds < 0) {
            return List.of();
        }
        int[] counts = buildCounts(concealedHand);
        if (counts == null) {
            return List.of();
        }
        List<MahjongTile> waits = new ArrayList<>();
        for (int index = 0; index < SICHUAN_TILES.length; index++) {
            if (counts[index] >= 4) {
                continue;
            }
            counts[index]++;
            if (isWinningCounts(counts, requiredMelds, fixedMeldCount == 0)) {
                waits.add(SICHUAN_TILES[index]);
            }
            counts[index]--;
        }
        return List.copyOf(waits);
    }

    private static int requiredMelds(List<MahjongTile> concealedHand, int fixedMeldCount) {
        if (concealedHand == null) {
            return -1;
        }
        int requiredMelds = 4 - fixedMeldCount;
        if (requiredMelds < 0 || concealedHand.size() + 1 != requiredMelds * 3 + 2) {
            return -1;
        }
        return requiredMelds;
    }

    private static int[] buildCounts(List<MahjongTile> tiles) {
        int[] counts = new int[SICHUAN_TILES.length];
        for (MahjongTile tile : tiles) {
            MahjongTile base = normalize(tile);
            int index = tileIndex(base);
            if (index < 0) {
                return null;
            }
            counts[index]++;
            if (counts[index] > 4) {
                return null;
            }
        }
        return counts;
    }

    private static MahjongTile normalize(MahjongTile tile) {
        if (tile == null) {
            return MahjongTile.UNKNOWN;
        }
        if (!tile.isRedFive()) {
            return tile;
        }
        return switch (tile) {
            case M5_RED -> MahjongTile.M5;
            case P5_RED -> MahjongTile.P5;
            case S5_RED -> MahjongTile.S5;
            default -> tile;
        };
    }

    private static int tileIndex(MahjongTile tile) {
        return tile == null ? -1 : TILE_INDEX_BY_ORDINAL[tile.ordinal()];
    }

    private static int[] createTileIndexLookup() {
        int[] indexes = new int[MahjongTile.values().length];
        Arrays.fill(indexes, -1);
        for (int index = 0; index < SICHUAN_TILES.length; index++) {
            indexes[SICHUAN_TILES[index].ordinal()] = index;
        }
        indexes[MahjongTile.M5_RED.ordinal()] = indexes[MahjongTile.M5.ordinal()];
        indexes[MahjongTile.P5_RED.ordinal()] = indexes[MahjongTile.P5.ordinal()];
        indexes[MahjongTile.S5_RED.ordinal()] = indexes[MahjongTile.S5.ordinal()];
        return indexes;
    }

    private static boolean isSevenPairs(int[] counts) {
        int pairs = 0;
        for (int count : counts) {
            if (count == 0) {
                continue;
            }
            if (count != 2 && count != 4) {
                return false;
            }
            pairs += count / 2;
        }
        return pairs == 7;
    }

    private static boolean isWinningCounts(int[] counts, int requiredMelds, boolean allowSevenPairs) {
        if (allowSevenPairs && isSevenPairs(counts)) {
            return true;
        }
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] < 2) {
                continue;
            }
            counts[index] -= 2;
            boolean winning = canFormMelds(counts, requiredMelds);
            counts[index] += 2;
            if (winning) {
                return true;
            }
        }
        return false;
    }

    private static boolean canFormMelds(int[] counts, int requiredMelds) {
        if (requiredMelds == 0) {
            for (int count : counts) {
                if (count != 0) {
                    return false;
                }
            }
            return true;
        }
        int first = firstNonZero(counts);
        if (first < 0) {
            return false;
        }
        if (counts[first] >= 3) {
            counts[first] -= 3;
            if (canFormMelds(counts, requiredMelds - 1)) {
                counts[first] += 3;
                return true;
            }
            counts[first] += 3;
        }
        int suitBase = (first / 9) * 9;
        int rank = first - suitBase;
        if (rank <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
            counts[first]--;
            counts[first + 1]--;
            counts[first + 2]--;
            if (canFormMelds(counts, requiredMelds - 1)) {
                counts[first]++;
                counts[first + 1]++;
                counts[first + 2]++;
                return true;
            }
            counts[first]++;
            counts[first + 1]++;
            counts[first + 2]++;
        }
        return false;
    }

    private static boolean collectMelds(int[] counts, int requiredMelds, List<MeldShape> melds) {
        if (requiredMelds == 0) {
            for (int count : counts) {
                if (count != 0) {
                    return false;
                }
            }
            return true;
        }
        int first = firstNonZero(counts);
        if (first < 0) {
            return false;
        }
        if (counts[first] >= 3) {
            counts[first] -= 3;
            melds.add(MeldShape.TRIPLET);
            if (collectMelds(counts, requiredMelds - 1, melds)) {
                counts[first] += 3;
                return true;
            }
            melds.remove(melds.size() - 1);
            counts[first] += 3;
        }
        int suitBase = (first / 9) * 9;
        int rank = first - suitBase;
        if (rank <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
            counts[first]--;
            counts[first + 1]--;
            counts[first + 2]--;
            melds.add(MeldShape.SEQUENCE);
            if (collectMelds(counts, requiredMelds - 1, melds)) {
                counts[first]++;
                counts[first + 1]++;
                counts[first + 2]++;
                return true;
            }
            melds.remove(melds.size() - 1);
            counts[first]++;
            counts[first + 1]++;
            counts[first + 2]++;
        }
        return false;
    }

    private static int sequenceCount(List<MeldShape> shapes) {
        int count = 0;
        for (MeldShape shape : shapes) {
            if (shape == MeldShape.SEQUENCE) {
                count++;
            }
        }
        return count;
    }

    private static int firstNonZero(int[] counts) {
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] > 0) {
                return index;
            }
        }
        return -1;
    }

    enum MeldShape {
        SEQUENCE,
        TRIPLET
    }

    record Result(boolean valid, boolean sevenPairs, List<MeldShape> concealedMelds) {
        Result {
            concealedMelds = concealedMelds == null ? List.of() : List.copyOf(concealedMelds);
        }

        static Result invalid() {
            return new Result(false, false, List.of());
        }
    }
}
