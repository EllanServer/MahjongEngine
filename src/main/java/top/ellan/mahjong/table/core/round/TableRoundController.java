package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.RiichiDiscardSuggestion;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.riichi.model.OpeningDiceRoll;
import top.ellan.mahjong.riichi.model.ScoringStick;
import top.ellan.mahjong.model.MahjongVariant;
import java.util.List;
import java.util.UUID;

public interface TableRoundController {
    interface VariantVisitor<T> {
        T visitRiichi(RiichiTableRoundController controller);

        T visitGb(GbTableRoundController controller);

        /**
         * Visit a controller running the Sichuan variant. Sichuan reuses {@link GbTableRoundController}
         * for the round flow but has its own scoring rules; visitors that care about the distinction
         * should override this. The default delegates to {@link #visitGb(GbTableRoundController)} so
         * existing visitors that only handle GB still compile and behave the same.
         */
        default T visitSichuan(GbTableRoundController controller) {
            return this.visitGb(controller);
        }
    }

    <T> T accept(VariantVisitor<T> visitor);

    MahjongVariant variant();

    MahjongRule rule();

    boolean started();

    boolean gameFinished();

    void startRound();

    default void setPendingDiceRoll(OpeningDiceRoll diceRoll) {
    }

    boolean discard(UUID playerId, int tileIndex);

    default boolean declareRiichi(UUID playerId, int tileIndex) {
        return false;
    }

    default boolean declareTsumo(UUID playerId) {
        return false;
    }

    default boolean declareKyuushuKyuuhai(UUID playerId) {
        return false;
    }

    default boolean react(UUID playerId, ReactionResponse response) {
        return false;
    }

    default boolean declareKan(UUID playerId, String tileName) {
        return false;
    }

    default boolean declareFlower(UUID playerId, int tileIndex) {
        return false;
    }

    UUID playerAt(SeatWind wind);

    int points(UUID playerId);

    boolean isRiichi(UUID playerId);

    int dicePoints();

    default int dicePoints2() {
        return this.dicePoints();
    }

    int kanCount();

    int roundIndex();

    int honbaCount();

    SeatWind roundWind();

    SeatWind dealerSeat();

    SeatWind currentSeat();

    String currentPlayerDisplayName();

    List<MahjongTile> hand(UUID playerId);

    List<MahjongTile> discards(UUID playerId);

    List<MahjongTile> remainingWall();

    default int remainingWallCount() {
        return this.remainingWall().size();
    }

    List<MeldView> fuuro(UUID playerId);

    List<ScoringStick> scoringSticks(UUID playerId);

    List<MahjongTile> doraIndicators();

    List<MahjongTile> uraDoraIndicators();

    RoundResolution lastResolution();

    default ReactionOptions availableReactions(UUID playerId) {
        return null;
    }

    default boolean hasPendingReaction() {
        return false;
    }

    /** Returns whether this player still owes a response in the current reaction window. */
    default boolean isReactionPending(UUID playerId) {
        return this.hasPendingReaction() && this.availableReactions(playerId) != null;
    }

    default String pendingReactionFingerprint() {
        return "";
    }

    default String pendingReactionTileKey() {
        return "";
    }

    default boolean isCurrentPlayer(UUID playerId) {
        return false;
    }

    default boolean canSelectHandTile(UUID playerId, int tileIndex) {
        return false;
    }

    default boolean handleHandTileClick(UUID playerId, int tileIndex, boolean cancelSelection) {
        return false;
    }

    default List<Integer> selectedHandTileIndices(UUID playerId) {
        return List.of();
    }

    default boolean canDeclareRiichi(UUID playerId) {
        return false;
    }

    default boolean canDeclareKan(UUID playerId) {
        return false;
    }

    default boolean canDeclareConcealedKan(UUID playerId) {
        return false;
    }

    default boolean canDeclareAddedKan(UUID playerId) {
        return false;
    }

    default boolean canDeclareKyuushu(UUID playerId) {
        return false;
    }

    default boolean canDeclareTsumo(UUID playerId) {
        return false;
    }

    default boolean canDeclareFlower(UUID playerId) {
        return false;
    }

    default List<Integer> suggestedRiichiIndices(UUID playerId) {
        return List.of();
    }

    default List<Integer> suggestedFlowerIndices(UUID playerId) {
        return List.of();
    }

    default List<String> suggestedKanTiles(UUID playerId) {
        return List.of();
    }

    default List<String> suggestedConcealedKanTiles(UUID playerId) {
        return List.of();
    }

    default List<String> suggestedAddedKanTiles(UUID playerId) {
        return List.of();
    }

    default List<String> suggestedDiscardTiles(UUID playerId) {
        return List.of();
    }

    default List<RiichiDiscardSuggestion> suggestedDiscardSuggestions(UUID playerId) {
        return List.of();
    }
}
