package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.gb.jni.GbFanEntry;
import top.ellan.mahjong.gb.jni.GbScoreDelta;
import top.ellan.mahjong.gb.jni.GbTingCandidate;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import java.util.List;
import java.util.Map;
import java.util.Set;

interface SichuanRulesEngine {
    FanResult evaluateFan(
        List<MahjongTile> concealedHand,
        List<? extends GbNativeRequestFactory.MeldStateView> melds,
        MahjongTile winningTile,
        String winType,
        List<String> flags,
        boolean goldenSingleWait
    );

    List<MahjongTile> waitingTiles(List<MahjongTile> concealedHand, int fixedMeldCount);

    boolean isMissingOneSuit(List<MahjongTile> tiles);

    int scoreUnit(int fan);

    int bestReadyUnit(List<GbTingCandidate> waits);

    List<GbScoreDelta> winDeltas(SeatWind winnerSeat, SeatWind discarderSeat, String winType, int scoreUnit, List<SeatWind> activeOpponentSeats);

    List<GbScoreDelta> kanDeltas(SeatWind winnerSeat, List<SeatWind> payerSeats, int unit);

    List<GbScoreDelta> exhaustiveDrawDeltas(
        List<SeatWind> activeSeats,
        Set<SeatWind> readySeats,
        Map<SeatWind, Integer> readyUnits
    );

    record FanResult(boolean valid, int totalFan, List<GbFanEntry> fans, String error) {
    }
}
