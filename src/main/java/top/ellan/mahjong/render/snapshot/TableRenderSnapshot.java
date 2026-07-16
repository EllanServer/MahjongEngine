package top.ellan.mahjong.render.snapshot;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.model.MahjongVariant;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

public record TableRenderSnapshot(
    long version,
    long cancellationNonce,
    String worldName,
    double centerX,
    double centerY,
    double centerZ,
    boolean started,
    boolean gameFinished,
    boolean roundStartInProgress,
    int remainingWallCount,
    int kanCount,
    int dicePoints,
    int breakDicePoints,
    int roundIndex,
    int honbaCount,
    SeatWind dealerSeat,
    SeatWind currentSeat,
    SeatWind openDoorSeat,
    String waitingDisplaySummary,
    String ruleDisplaySummary,
    String publicCenterText,
    UUID lastPublicDiscardPlayerId,
    top.ellan.mahjong.model.MahjongTile lastPublicDiscardTile,
    List<top.ellan.mahjong.model.MahjongTile> doraIndicators,
    MahjongVariant variant,
    EnumMap<SeatWind, TableSeatRenderSnapshot> seats
) {
    public TableRenderSnapshot {
        variant = variant == null ? MahjongVariant.RIICHI : variant;
    }

    public TableRenderSnapshot(
        long version,
        long cancellationNonce,
        String worldName,
        double centerX,
        double centerY,
        double centerZ,
        boolean started,
        boolean gameFinished,
        boolean roundStartInProgress,
        int remainingWallCount,
        int kanCount,
        int dicePoints,
        int breakDicePoints,
        int roundIndex,
        int honbaCount,
        SeatWind dealerSeat,
        SeatWind currentSeat,
        SeatWind openDoorSeat,
        String waitingDisplaySummary,
        String ruleDisplaySummary,
        String publicCenterText,
        UUID lastPublicDiscardPlayerId,
        top.ellan.mahjong.model.MahjongTile lastPublicDiscardTile,
        List<top.ellan.mahjong.model.MahjongTile> doraIndicators,
        EnumMap<SeatWind, TableSeatRenderSnapshot> seats
    ) {
        this(
            version,
            cancellationNonce,
            worldName,
            centerX,
            centerY,
            centerZ,
            started,
            gameFinished,
            roundStartInProgress,
            remainingWallCount,
            kanCount,
            dicePoints,
            breakDicePoints,
            roundIndex,
            honbaCount,
            dealerSeat,
            currentSeat,
            openDoorSeat,
            waitingDisplaySummary,
            ruleDisplaySummary,
            publicCenterText,
            lastPublicDiscardPlayerId,
            lastPublicDiscardTile,
            doraIndicators,
            MahjongVariant.RIICHI,
            seats
        );
    }

    public boolean usesDeadWall() {
        return this.variant == MahjongVariant.RIICHI;
    }

    public int wallCapacity() {
        return switch (this.variant) {
            case RIICHI -> 136;
            case GB -> 144;
            case SICHUAN -> 108;
        };
    }

    public int wallTilesPerSide() {
        return this.wallCapacity() / SeatWind.values().length;
    }

    public int breakDicePoints() {
        return this.breakDicePoints;
    }

    public TableSeatRenderSnapshot seat(SeatWind wind) {
        return this.seats.get(wind);
    }
}
