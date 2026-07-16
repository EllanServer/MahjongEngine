package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableViewerHudPresentationSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.riichi.model.OpeningDiceRoll;
import top.ellan.mahjong.table.core.round.TableRoundController;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

public interface TableSessionMutator extends TableSessionContext {
    long actionDeadlineSecondsRemaining(UUID playerId);

    TableViewerHudPresentationSnapshot captureViewerHudPresentationSnapshot(Locale locale, UUID viewerId);

    void render();

    boolean discard(UUID playerId, int tileIndex);

    boolean removePlayer(UUID playerId);

    void prepareRenderRequest();

    boolean applyRenderPrecompute(TableRenderPrecomputeResult result);

    void completeRenderFlush();

    void clearRenderDisplays();

    void invalidateRenderFingerprints();

    void removeManagedRegionDisplays(String regionKey);

    void updateViewerOverlayRegion(TableViewerOverlaySnapshot snapshot);

    void updateViewerActionRegions(TableViewerOverlaySnapshot snapshot);

    void shutdownRenderFlow();

    void shutdownViewerPresentation();

    void resetViewerPresentationForLifecycleChange();

    void clearFeedbackTracking();

    void clearRoundTrackingState();

    void clearReadyPlayersForLifecycle();

    void clearLeaveQueueForLifecycle();

    void clearSpectatorsForLifecycle();

    void clearBotNamesForLifecycle();

    void clearSeatAssignmentsForLifecycle();

    void clearEngineForLifecycle();

    void resetBotCounterForLifecycle();

    void resetReadyStateForNextRound();

    void promptPlayersToReady();

    void cancelBotTask();

    void cancelNextRoundCountdown();

    boolean openSettlementUi(Player player);

    void clearViewerActionMenuState(UUID viewerId);

    void refreshSelectedHandTileViewInternal(UUID playerId);

    boolean handleHandTileClickInternal(UUID playerId, int tileIndex, boolean cancelSelection);

    void clearSelectedHandTilesInternal();

    void clearLastPublicDiscardInternal();

    void clearLastPublicActionInternal();

    void rememberPublicDiscardInternal(UUID playerId, MahjongTile discardedTile);

    void rememberPublicActionInternal(UUID playerId, String actionKey);

    void rememberPublicActionsInternal(List<UUID> playerIds, String actionKey);

    void playReactionSoundInternal(ReactionResponse response);

    void playDiscardSoundInternal();

    void playRiichiSoundInternal();

    void persistRoomMetadataIfNeededInternal();

    void setConfiguredRuleInternal(MahjongRule configuredRule);

    void setConfiguredVariantInternal(MahjongVariant configuredVariant);

    void setRoundControllerInternal(TableRoundController roundController);

    TableRoundController createRoundControllerInternal();

    void setRoundStartInProgressInternal(boolean roundStartInProgress);

    void resetRoundPresentationForStartInternal();

    void startOpeningDiceAnimationInternal(OpeningDiceRoll diceRoll, Runnable completion);

    void completeRoundStartInternal();

    void playRoundStartSoundInternal();

    void restoreDisplaysIfNeededInternal();

    void flushViewerPresentationIfNeededInternal();

    void removeQueuedLeavesInternal(List<UUID> removedPlayerIds);

    void finalizeDeferredLeaves(Map<UUID, SeatWind> removed);

    void scheduleNextRoundCountdownInternal();

    void cancelNextRoundCountdownInternal();
}
