package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.riichi.model.ScoringStick;
import java.util.List;

/** Single-entry, immutable layout cache owned by one table session. */
final class SessionRenderLayoutCache {
    private volatile Entry cached;

    TableRenderLayout.LayoutPlan precompute(TableRenderSnapshot snapshot) {
        LayoutKey key = LayoutKey.from(snapshot);
        Entry current = this.cached;
        if (current != null && current.key().equals(key)) {
            return current.layout();
        }
        TableRenderLayout.LayoutPlan layout = current == null
            ? TableRenderLayout.precompute(snapshot)
            : TableRenderLayout.precompute(snapshot, current.snapshot(), current.layout());
        this.cached = new Entry(key, snapshot, layout);
        return layout;
    }

    void clear() {
        this.cached = null;
    }

    private record Entry(LayoutKey key, TableRenderSnapshot snapshot, TableRenderLayout.LayoutPlan layout) {
    }

    private record LayoutKey(
        double centerX,
        double centerY,
        double centerZ,
        boolean started,
        int remainingWallCount,
        int kanCount,
        int dicePoints,
        int breakDicePoints,
        SeatWind dealerSeat,
        SeatWind openDoorSeat,
        List<MahjongTile> doraIndicators,
        MahjongVariant variant,
        SeatLayoutKey east,
        SeatLayoutKey south,
        SeatLayoutKey west,
        SeatLayoutKey north
    ) {
        private static LayoutKey from(TableRenderSnapshot snapshot) {
            return new LayoutKey(
                snapshot.centerX(),
                snapshot.centerY(),
                snapshot.centerZ(),
                snapshot.started(),
                snapshot.remainingWallCount(),
                snapshot.kanCount(),
                snapshot.dicePoints(),
                snapshot.breakDicePoints(),
                snapshot.dealerSeat(),
                snapshot.openDoorSeat(),
                snapshot.doraIndicators(),
                snapshot.variant(),
                SeatLayoutKey.from(snapshot.seat(SeatWind.EAST)),
                SeatLayoutKey.from(snapshot.seat(SeatWind.SOUTH)),
                SeatLayoutKey.from(snapshot.seat(SeatWind.WEST)),
                SeatLayoutKey.from(snapshot.seat(SeatWind.NORTH))
            );
        }
    }

    private record SeatLayoutKey(
        SeatWind wind,
        boolean occupied,
        int handSize,
        List<Integer> selectedHandTileIndices,
        int riichiDiscardIndex,
        int stickLayoutCount,
        List<MahjongTile> discards,
        List<MeldView> melds,
        List<ScoringStick> cornerSticks,
        boolean riichi
    ) {
        private static SeatLayoutKey from(TableSeatRenderSnapshot seat) {
            if (seat.playerId() == null) {
                return new SeatLayoutKey(
                    seat.wind(), false, 0, List.of(), -1, 0, List.of(), List.of(), List.of(), false
                );
            }
            return new SeatLayoutKey(
                seat.wind(),
                true,
                seat.hand().size(),
                seat.selectedHandTileIndices(),
                seat.riichiDiscardIndex(),
                seat.stickLayoutCount(),
                seat.discards(),
                seat.melds(),
                seat.cornerSticks(),
                seat.riichi()
            );
        }
    }
}
