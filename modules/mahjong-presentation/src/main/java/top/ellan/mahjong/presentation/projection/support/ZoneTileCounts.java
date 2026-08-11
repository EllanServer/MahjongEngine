package top.ellan.mahjong.presentation.projection.support;

import java.util.List;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;

/** Primitive per-zone counts used by layout projection without maps or hot-path allocation. */
public final class ZoneTileCounts {
    private static final int OWNERLESS = 4;
    private static final int OWNER_BUCKETS = 5;

    private final int[][] counts =
            new int[RuleViewZone.values().length][OWNER_BUCKETS];

    private ZoneTileCounts() {}

    public static ZoneTileCounts from(List<RuleViewTile> tiles) {
        ZoneTileCounts result = new ZoneTileCounts();
        for (RuleViewTile tile : tiles) {
            result.counts[tile.zone().ordinal()][ownerBucket(tile)]++;
        }
        return result;
    }

    public int count(RuleViewTile tile) {
        return counts[tile.zone().ordinal()][ownerBucket(tile)];
    }

    private static int ownerBucket(RuleViewTile tile) {
        if (tile.zone() == RuleViewZone.WIN_CLAIM || tile.zone() == RuleViewZone.AUXILIARY) {
            return OWNERLESS;
        }
        return tile.owner().isPresent() ? tile.owner().get().value() : OWNERLESS;
    }
}
