package top.ellan.mahjong.table.core;

import top.ellan.mahjong.gb.jni.GbTingResponse;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.render.scene.TableRenderer;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerHudSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.RiichiDiscardSuggestion;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.riichi.model.ScoringStick;
import top.ellan.mahjong.table.core.round.TableRoundController;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public interface TableSessionContext extends TableRenderSubject {
    TableRuntimeServices plugin();

    @Override
    default ServerScheduler scheduler() {
        return this.plugin().scheduler();
    }

    @Override
    Plugin bukkitPlugin();

    TableRenderer renderer();

    int size();

    int readyCount();

    int botCount();

    int spectatorCount();

    int riichiPoolCount();

    boolean isReady(UUID playerId);

    boolean isBot(UUID playerId);

    boolean isBotMatchRoom();

    boolean hasRoundController();

    boolean hasPendingReaction();

    /** Uses the round controller's phase-aware notion of which player may act now. */
    default boolean isCurrentPlayer(UUID playerId) {
        TableRoundController controller = this.roundControllerInternal();
        return playerId != null && controller != null && controller.isCurrentPlayer(playerId);
    }

    List<UUID> seatIds();

    Set<UUID> spectators();

    Player onlinePlayer(UUID playerId);

    String roundDisplay(Locale locale);

    String roundDisplay();

    String dealerName(Locale locale);

    String dealerName();

    String waitingDisplaySummary(Locale locale);

    String ruleDisplaySummary(Locale locale);

    String seatDisplayName(SeatWind wind, Locale locale);

    String publicLastActionSummary(Locale locale);

    String tileLabelForDisplay(Locale locale, String tileName);

    String currentTurnDisplayName();

    String pendingReactionFingerprint();

    String pendingReactionTileKey();

    String viewerActionMenuState(UUID viewerId);

    MahjongRule configuredRuleSnapshot();

    MahjongRule configuredRuleInternal();

    SeatWind roundWind();

    RoundResolution lastResolution();

    UUID lastPublicDiscardPlayerId();

    ReactionOptions availableReactions(UUID playerId);

    boolean canDeclareRiichi(UUID playerId);

    boolean canDeclareConcealedKan(UUID playerId);

    boolean canDeclareAddedKan(UUID playerId);

    boolean canDeclareKyuushu(UUID playerId);

    boolean canDeclareTsumo(UUID playerId);

    boolean canDeclareFlower(UUID playerId);

    boolean canChooseSichuanMissingSuit(UUID playerId);

    boolean isSichuanExchangePhase(UUID playerId);

    boolean canSelectHandTileInternal(UUID playerId, int tileIndex);

    MahjongTile handTileAtInternal(UUID playerId, int tileIndex);

    List<Integer> suggestedRiichiIndices(UUID playerId);

    List<Integer> suggestedFlowerIndices(UUID playerId);

    List<String> suggestedConcealedKanTiles(UUID playerId);

    List<String> suggestedAddedKanTiles(UUID playerId);

    List<RiichiDiscardSuggestion> suggestedDiscardSuggestions(UUID playerId);

    GbTingResponse gbTingOptions(UUID playerId);

    List<TableFinalStanding> finalStandings();

    List<MahjongTile> uraDoraIndicators();

    TableRoundController roundControllerInternal();

    boolean seatAssignmentsMatchControllerInternal(TableRoundController controller);

    boolean shouldAnimateOpeningDiceInternal();

    boolean hasQueuedLeavesInternal();

    Set<UUID> queuedLeavePlayersInternal();

    boolean hasNextRoundCountdownInternal();

    long nextRoundSecondsRemainingInternal();

    long nextRoundSecondsRemainingValue();

    TableRenderSnapshot captureRenderSnapshot(long version, long cancellationNonce);

    TableRenderPrecomputeResult precomputeRender(TableRenderSnapshot snapshot);

    TableViewerOverlaySnapshot captureViewerOverlaySnapshot(Player viewer);

    TableViewerHudSnapshot captureViewerHudSnapshot(Locale locale, UUID viewerId);

    List<String> viewerOverlayRegionKeys();

    boolean hasRegionDisplays();

    boolean hasStaleDisplayRegions();

    boolean isCenterChunkLoaded();
}
