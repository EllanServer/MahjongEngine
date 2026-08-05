package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongVariant;

import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.gb.runtime.GbNativeRulesGateway;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.i18n.LocalizedMessages;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.scene.TableRenderer;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerHudSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerHudPresentationSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.table.action.PlayerActionSnapshotFactory;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.ReactionResponses;
import top.ellan.mahjong.riichi.RiichiPlayerState;
import top.ellan.mahjong.riichi.RiichiRoundEngine;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.riichi.model.MahjongSoulScoring;
import top.ellan.mahjong.riichi.model.ScoringStick;
import top.ellan.mahjong.table.core.round.GbTableRoundController;
import top.ellan.mahjong.table.core.round.GbRuleProfile;
import top.ellan.mahjong.riichi.model.OpeningDiceRoll;
import top.ellan.mahjong.table.core.round.RiichiTableRoundController;
import top.ellan.mahjong.table.core.round.TableRoundController;
import top.ellan.mahjong.table.presentation.TableDiceAnimationCoordinator;
import top.ellan.mahjong.table.presentation.TablePlayerFeedbackCoordinator;
import top.ellan.mahjong.table.presentation.TablePublicTextFactory;
import top.ellan.mahjong.table.presentation.TableStateSoundCoordinator;
import top.ellan.mahjong.table.presentation.TableViewerPresentationCoordinator;
import top.ellan.mahjong.table.presentation.TableViewerSnapshotFactory;
import top.ellan.mahjong.table.render.TableRegionDisplayCoordinator;
import top.ellan.mahjong.table.render.TableRegionFingerprintService;
import top.ellan.mahjong.table.render.TableRenderCoordinator;
import top.ellan.mahjong.table.render.TableRenderInspectCoordinator;
import top.ellan.mahjong.table.render.TableRenderSnapshotFactory;
import top.ellan.mahjong.table.runtime.BotActionScheduler;
import top.ellan.mahjong.runtime.PluginTask;
import top.ellan.mahjong.table.runtime.TableLifecycleCoordinator;
import top.ellan.mahjong.ui.SettlementUi;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class MahjongTableSession implements TableSessionMutator, TableMembershipPort, TableRenderStatePort, TableBotTaskPort, TableLifecyclePort {
    private final TableRuntimeServices plugin;
    private final String id;
    private final Location center;
    private final boolean persistentRoom;
    private final boolean botMatchRoom;
    private final TableParticipantRegistry participants = new TableParticipantRegistry();
    /**
     * Cache of fully rendered bot display names, keyed by locale tag + '|' + bot player id.
     * Bot names are deterministic per (locale, seat index) and only change on participant
     * mutations, so the expensive placeholder-driven i18n render is performed once per key
     * instead of once per render-snapshot capture (the capture path calls displayName for
     * every seated player on every tick).
     */
    private final Map<String, String> botDisplayNameCache = new HashMap<>();
    private final TableRenderer renderer = new TableRenderer();
    private final TableRenderSnapshotFactory renderSnapshotFactory = new TableRenderSnapshotFactory();
    private final TableRegionFingerprintService regionFingerprintService = new TableRegionFingerprintService();
    private final SessionRenderLayoutCache renderLayoutCache = new SessionRenderLayoutCache();
    private MahjongRule configuredRule;
    // These three fields are written on the table's region thread (during
    // startRound / completeRoundStartInternal / setRoundControllerInternal)
    // but read cross-thread by: the global tick timer's bot watchdog, the
    // async render precompute thread (via TableRegionFingerprintService),
    // the seat watchdog global timer (TableSeatCoordinator), the GameRoom
    // tick timer, and player entity threads (TableEventCoordinator). They
    // MUST be volatile so the write is published before subsequent renders
    // and the readers observe a consistent snapshot. Without volatile, the
    // JVM is free to reorder or cache these reads, leading to "bot scheduled
    // on a half-published roundController" and similar races that mirror the
    // botTask race fixed in 195b5aa. See the architecture review for the
    // full region-ownership contract.
    private volatile TableRoundController roundController;
    private volatile boolean roundStartInProgress;
    private top.ellan.mahjong.model.MahjongTile lastPublicDiscardTile;
    private UUID lastPublicDiscardPlayerId;
    private volatile PluginTask botTask;
    private final TableRenderCoordinator renderCoordinator;
    private final TableViewerPresentationCoordinator viewerPresentation;
    private final TableRegionDisplayCoordinator regionDisplayCoordinator;
    private final TableRenderInspectCoordinator renderInspectCoordinator;
    private final TableLifecycleCoordinator lifecycleCoordinator;
    private final TableViewerSnapshotFactory viewerSnapshotFactory;
    private final PlayerActionSnapshotFactory actionSnapshotFactory;
    private final TableDiceAnimationCoordinator diceAnimationCoordinator;
    private final TablePlayerFeedbackCoordinator playerFeedbackCoordinator;
    private final TableStateSoundCoordinator stateSoundCoordinator;
    private final TablePublicTextFactory publicTextFactory;
    private final SessionMessaging sessionMessaging;
    private final SessionViewerIndex viewerIndex;
    private final SessionRoundLifecycle roundLifecycle;
    private final TableSessionContext sessionContext;
    private final TableSessionMutator sessionMutator;
    private final SessionRuleCoordinator ruleCoordinator;
    private final SessionRoundActionCoordinator roundActionCoordinator;
    private final SessionHandSelectionCoordinator handSelectionCoordinator;
    private final SessionRoundFlowCoordinator roundFlowCoordinator;
    final SessionActionDeadlineCoordinator actionDeadlineCoordinator;
    private final SessionPublicActionCoordinator publicActionCoordinator;
    private final SessionViewerActionMenuCoordinator viewerActionMenuCoordinator = new SessionViewerActionMenuCoordinator();
    private final Set<UUID> unattendedPlayers = ConcurrentHashMap.newKeySet();
    // Written on the table's region thread when roundController is set/rotated
    // (resolveRiichiEngine + setRoundControllerInternal); read cross-thread by
    // RiichiBotStrategy.schedule via session.riichiEngine() on the same region
    // thread (post-195b5aa) AND by async render precompute via
    // TableRenderSnapshotFactory when capturing engine state. Volatile so the
    // async thread never observes a stale null pointer when the round has just
    // started on the region thread.
    private volatile RiichiRoundEngine riichiRoundEngine;
    private MahjongVariant configuredVariant;
    private UUID ownerId;

    public MahjongTableSession(TableRuntimeServices plugin, String id, Location center, boolean persistentRoom) {
        this(
            plugin,
            id,
            center,
            MahjongVariant.RIICHI,
            SessionRulePresetResolver.majsoulRule(MahjongRule.GameLength.TWO_WIND),
            persistentRoom,
            false,
            null
        );
    }

    public MahjongTableSession(
        TableRuntimeServices plugin,
        String id,
        Location center,
        MahjongVariant configuredVariant,
        MahjongRule configuredRule,
        boolean persistentRoom,
        boolean botMatchRoom
    ) {
        this(plugin, id, center, configuredVariant, configuredRule, persistentRoom, botMatchRoom, null);
    }

    public MahjongTableSession(
        TableRuntimeServices plugin,
        String id,
        Location center,
        MahjongVariant configuredVariant,
        MahjongRule configuredRule,
        boolean persistentRoom,
        boolean botMatchRoom,
        UUID ownerId
    ) {
        this.plugin = plugin;
        this.id = id;
        this.center = normalizedTableCenter(center);
        this.configuredVariant = configuredVariant == null ? MahjongVariant.RIICHI : configuredVariant;
        this.configuredRule = copyRule(configuredRule);
        this.persistentRoom = persistentRoom;
        this.botMatchRoom = botMatchRoom;
        this.ownerId = ownerId;
        this.sessionContext = this;
        this.sessionMutator = this;
        this.renderCoordinator = new TableRenderCoordinator(this.sessionMutator);
        this.viewerPresentation = new TableViewerPresentationCoordinator(this.sessionMutator);
        this.regionDisplayCoordinator = new TableRegionDisplayCoordinator(this.sessionContext, this.regionFingerprintService);
        this.renderInspectCoordinator = new TableRenderInspectCoordinator(this.sessionContext);
        this.lifecycleCoordinator = new TableLifecycleCoordinator(this.sessionMutator);
        this.viewerSnapshotFactory = new TableViewerSnapshotFactory(this.sessionMutator);
        this.actionSnapshotFactory = new PlayerActionSnapshotFactory(this.sessionMutator);
        this.diceAnimationCoordinator = new TableDiceAnimationCoordinator(this.sessionContext);
        this.playerFeedbackCoordinator = new TablePlayerFeedbackCoordinator(this.sessionMutator);
        this.stateSoundCoordinator = new TableStateSoundCoordinator(this.sessionContext);
        this.publicTextFactory = new TablePublicTextFactory(this.sessionContext);
        this.sessionMessaging = new SessionMessaging(this.sessionContext);
        this.viewerIndex = new SessionViewerIndex(this.sessionContext);
        this.roundLifecycle = new SessionRoundLifecycle();
        this.ruleCoordinator = new SessionRuleCoordinator(this.sessionMutator);
        this.roundActionCoordinator = new SessionRoundActionCoordinator(this.sessionMutator);
        this.handSelectionCoordinator = new SessionHandSelectionCoordinator(this.sessionMutator);
        this.roundFlowCoordinator = new SessionRoundFlowCoordinator(this.sessionMutator);
        this.actionDeadlineCoordinator = new SessionActionDeadlineCoordinator(this);
        this.publicActionCoordinator = new SessionPublicActionCoordinator(this);
    }

    public TableRuntimeServices plugin() {
        return this.plugin;
    }

    /**
     * Shared, stateless action-snapshot factory for this session. Bot strategies
     * and render paths capture action snapshots every tick, so the factory is
     * created once per session instead of per capture.
     */
    public PlayerActionSnapshotFactory actionSnapshotFactory() {
        return this.actionSnapshotFactory;
    }

    @Override
    public Plugin bukkitPlugin() {
        return this.plugin.bukkitPlugin();
    }

    @Override
    public CraftEngineService craftEngine() {
        return this.plugin.craftEngine();
    }

    @Override
    public PluginSettings settings() {
        return this.plugin.settings();
    }

    @Override
    public MessageService messages() {
        return this.plugin.messages();
    }

    public String id() {
        return this.id;
    }

    public boolean isPersistentRoom() {
        return this.persistentRoom;
    }

    public boolean isBotMatchRoom() {
        return this.botMatchRoom;
    }

    public Location center() {
        return this.center.clone();
    }

    public Location seatAnchorLocation(SeatWind wind) {
        return wind == null ? this.center() : this.renderer.seatAnchorLocation(this, wind);
    }

    public float seatFacingYaw(SeatWind wind) {
        return wind == null ? 0.0F : this.renderer.seatFacingYaw(wind);
    }

    public MahjongRule configuredRuleSnapshot() {
        return copyRule(this.configuredRule);
    }

    public MahjongVariant configuredVariant() {
        return this.configuredVariant;
    }

    public boolean addPlayer(Player player) {
        for (SeatWind wind : SeatWind.values()) {
            if (this.playerAt(wind) == null) {
                return this.addPlayer(player, wind);
            }
        }
        return false;
    }

    public boolean addPlayer(Player player, SeatWind wind) {
        boolean added = this.participants.addPlayer(player.getUniqueId(), wind);
        if (added) {
            this.unattendedPlayers.remove(player.getUniqueId());
            this.assignOwnerIfAbsent(player.getUniqueId());
        }
        return added;
    }

    public boolean addSpectator(Player player) {
        return this.participants.addSpectator(player.getUniqueId(), this.currentRule().getSpectate());
    }

    public boolean removeSpectator(UUID playerId) {
        this.regionDisplayCoordinator.discardViewerClientOverlay(playerId);
        this.viewerPresentation.hideHud(playerId);
        this.viewerActionMenuCoordinator.clear(playerId);
        boolean removed = this.participants.removeSpectator(playerId);
        if (removed) {
            DisplayInteractionRayRegistry.clearViewer(playerId, this.id);
        }
        return removed;
    }

    public boolean addBot() {
        if (this.isStarted() || this.roundStartInProgress || this.size() >= 4) {
            return false;
        }
        UUID botId = this.participants.createNextBotId(this.id);
        if (!this.participants.addBot(botId)) {
            return false;
        }
        this.render();
        this.roundFlowCoordinator.maybeStartRoundIfReady();
        return true;
    }

    public boolean replaceBotWithPlayer(Player player, SeatWind wind) {
        if (player == null || wind == null || this.isStarted() || this.roundStartInProgress) {
            return false;
        }
        UUID botId = this.playerAt(wind);
        if (botId == null || !this.isBot(botId)) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (!this.participants.replaceBotWithPlayer(playerId, wind)) {
            return false;
        }
        this.unattendedPlayers.remove(playerId);
        this.assignOwnerIfAbsent(playerId);
        this.playerFeedbackCoordinator.clearPlayerState(botId);
        this.handSelectionCoordinator.clearPlayer(botId);
        this.invalidateBotDisplayNameCache(botId);
        return true;
    }

    public boolean removeBot() {
        if (this.isStarted() || this.roundStartInProgress) {
            return false;
        }
        UUID playerId = this.participants.removeLastBot();
        if (playerId == null) {
            return false;
        }
        this.playerFeedbackCoordinator.clearPlayerState(playerId);
        this.handSelectionCoordinator.clearPlayer(playerId);
        this.invalidateBotDisplayNameCache(playerId);
        this.render();
        return true;
    }

    public boolean removePlayer(UUID playerId) {
        if (this.roundStartInProgress || (this.roundController != null && this.roundController.started() && !this.roundController.gameFinished())) {
            return false;
        }
        this.viewerPresentation.hideHud(playerId);
        this.viewerActionMenuCoordinator.clear(playerId);
        this.playerFeedbackCoordinator.clearPlayerState(playerId);
        this.handSelectionCoordinator.clearPlayer(playerId);
        boolean removed = this.participants.removePlayer(playerId);
        this.unattendedPlayers.remove(playerId);
        if (removed) {
            this.invalidateBotDisplayNameCache(playerId);
            DisplayInteractionRayRegistry.clearViewer(playerId, this.id);
        }
        if (removed && Objects.equals(this.ownerId, playerId)) {
            this.reassignOwnerFromSeats();
        }
        return removed;
    }

    public boolean contains(UUID playerId) {
        return this.participants.contains(playerId);
    }

    public boolean isEmpty() {
        return this.participants.isEmpty();
    }

    public int size() {
        return this.participants.size();
    }

    public int botCount() {
        return this.participants.botCount();
    }

    public int spectatorCount() {
        return this.participants.spectatorCount();
    }

    public List<UUID> players() {
        return this.participants.players();
    }

    public Set<UUID> spectators() {
        return this.participants.spectators();
    }

    public UUID owner() {
        return this.ownerId == null ? this.participants.firstHumanPlayer() : this.ownerId;
    }

    public boolean isOwner(UUID playerId) {
        return playerId != null && playerId.equals(this.owner());
    }

    public void setOwner(UUID ownerId) {
        this.ownerId = ownerId;
        this.persistRoomMetadataIfNeededInternal();
    }

    private void assignOwnerIfAbsent(UUID playerId) {
        if (this.ownerId != null || playerId == null || this.participants.isBot(playerId)) {
            return;
        }
        this.ownerId = playerId;
        this.persistRoomMetadataIfNeededInternal();
    }

    private void reassignOwnerFromSeats() {
        this.ownerId = this.participants.firstHumanPlayer();
        this.persistRoomMetadataIfNeededInternal();
    }

    public SeatWind seatOf(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        // Fast path: when no round is active (or the round is finished), the
        // participants' seatByPlayer map is the single source of truth and we
        // can do an O(1) HashMap.get. This is the common case for lobby
        // renders, viewer overlays, and command tab-completion.
        //
        // When a round IS active, the engine's seats vector is the source of
        // truth (it is snapshotted at round start and never mutated mid-round).
        // In production, participants.seatByPlayer is kept in sync with the
        // engine seats at round boundaries (addPlayer → startRound → engine
        // snapshot), so the fast path would return the same answer. We still
        // fall through to the O(4) reverse lookup via playerAt(wind) for
        // active rounds to preserve the exact pre-T4 semantics in any edge
        // case where the two views diverge (e.g. a test that mocks an
        // inconsistent engine). Performance-wise, the active-round path runs
        // at most 4 playerAt calls and only on user interaction (not on the
        // render hot path).
        //
        // Cross-thread contract: callers on the table's region thread see
        // consistent state. Cross-thread callers (player entity thread in
        // TableEventCoordinator) read best-effort — HashMap.get is not
        // synchronized but does not throw, and the worst case is a stale
        // value the caller treats as "not seated". See T1/T5 comments.
        TableRoundController controller = this.roundController;
        if (controller == null || !controller.started() || controller.gameFinished()) {
            return this.participants.seatOf(playerId);
        }
        for (SeatWind wind : SeatWind.values()) {
            if (java.util.Objects.equals(this.playerAt(wind), playerId)) {
                return wind;
            }
        }
        return null;
    }

    public boolean isReady(UUID playerId) {
        return this.participants.isReady(playerId);
    }

    public int readyCount() {
        return this.participants.readyCount();
    }

    public boolean isQueuedToLeave(UUID playerId) {
        return this.participants.isQueuedToLeave(playerId);
    }

    /** Marks a retained human seat for safe tick-driven actions while its player is absent. */
    public void setPlayerUnattended(UUID playerId, boolean unattended) {
        if (playerId == null || !this.contains(playerId) || this.isBot(playerId)) {
            return;
        }
        boolean changed = unattended
            ? this.unattendedPlayers.add(playerId)
            : this.unattendedPlayers.remove(playerId);
        if (changed) {
            this.viewerPresentation.markDirty();
        }
    }

    public boolean isPlayerUnattended(UUID playerId) {
        return playerId != null && this.unattendedPlayers.contains(playerId);
    }

    public ReadyResult toggleReady(UUID playerId) {
        if (playerId == null || !this.contains(playerId) || this.isBot(playerId) || this.isStarted() || this.roundStartInProgress) {
            return ReadyResult.BLOCKED;
        }

        boolean nowReady = this.participants.toggleReady(playerId);

        if (nowReady && this.roundFlowCoordinator.maybeStartRoundIfReady()) {
            return ReadyResult.STARTED;
        }

        this.render();
        return nowReady ? ReadyResult.READY : ReadyResult.UNREADY;
    }

    public boolean queueLeaveAfterRound(UUID playerId) {
        if (playerId == null || !this.contains(playerId) || !this.isStarted()) {
            return false;
        }
        this.participants.queueLeave(playerId);
        return true;
    }

    public void startRound() {
        this.roundFlowCoordinator.startRound();
    }

    public boolean discard(UUID playerId, int tileIndex) {
        boolean result = this.roundActionCoordinator.discard(playerId, tileIndex);
        if (result) {
            this.actionDeadlineCoordinator.recordDiscard(playerId);
        }
        return result;
    }

    public boolean declareRiichi(UUID playerId, int tileIndex) {
        boolean result = this.roundActionCoordinator.declareRiichi(playerId, tileIndex);
        if (result) {
            this.actionDeadlineCoordinator.recordDiscard(playerId);
        }
        return result;
    }

    public boolean declareTsumo(UUID playerId) {
        boolean result = this.roundActionCoordinator.declareTsumo(playerId);
        if (result) {
            this.actionDeadlineCoordinator.recordAction(playerId);
        }
        return result;
    }

    public boolean declareKyuushuKyuuhai(UUID playerId) {
        boolean result = this.roundActionCoordinator.declareKyuushuKyuuhai(playerId);
        if (result) {
            this.actionDeadlineCoordinator.recordAction(playerId);
        }
        return result;
    }

    public boolean react(UUID playerId, ReactionResponse response) {
        boolean result = this.roundActionCoordinator.react(playerId, response);
        if (result) {
            this.actionDeadlineCoordinator.recordAction(playerId);
        }
        return result;
    }

    public boolean declareKan(UUID playerId, String tileName) {
        boolean result = this.roundActionCoordinator.declareKan(playerId, tileName);
        if (result) {
            this.actionDeadlineCoordinator.recordAction(playerId);
        }
        return result;
    }

    public boolean declareFlower(UUID playerId, int tileIndex) {
        boolean result = this.roundActionCoordinator.declareFlower(playerId, tileIndex);
        if (result) {
            this.actionDeadlineCoordinator.recordAction(playerId);
        }
        return result;
    }

    public void render() {
        this.renderCoordinator.render();
    }

    public void clearDisplays() {
        this.renderCoordinator.clearDisplays();
        this.diceAnimationCoordinator.clear();
    }

    public boolean applyRenderPrecompute(TableRenderPrecomputeResult result) {
        boolean deferred = this.regionDisplayCoordinator.applyRenderPrecompute(result);
        this.viewerPresentation.flushIfNeeded();
        return deferred;
    }

    public void inspectRender(Player viewer) {
        this.renderInspectCoordinator.inspectRender(viewer);
    }

    public void shutdown() {
        // Synchronously remove display entities before delegating to the
        // lifecycle coordinator. During plugin disable the Bukkit scheduler
        // cancels all pending tasks, so the coordinator's scheduled removal
        // would never execute and entities would leak into saved chunk data.
        this.regionDisplayCoordinator.shutdown();
        this.lifecycleCoordinator.shutdown();
    }

    public void forceEndMatch() {
        this.lifecycleCoordinator.forceEndMatch();
    }

    public void resetForServerStartup() {
        this.lifecycleCoordinator.resetForServerStartup();
    }

    public void cancelNextRoundCountdown() {
        this.roundLifecycle.cancelNextRoundCountdown();
    }

    public void shutdownRenderFlow() {
        this.renderCoordinator.shutdown();
    }

    public void shutdownViewerPresentation() {
        this.viewerPresentation.shutdown();
    }

    public void resetViewerPresentationForLifecycleChange() {
        this.viewerPresentation.resetForLifecycleChange();
    }

    public void clearFeedbackTracking() {
        this.handSelectionCoordinator.clearAll();
    }

    public void clearRoundTrackingState() {
        this.actionDeadlineCoordinator.clear();
        this.roundStartInProgress = false;
        this.diceAnimationCoordinator.clear();
        this.clearLastPublicDiscardInternal();
        this.clearLastPublicActionInternal();
        this.playerFeedbackCoordinator.resetState();
        this.stateSoundCoordinator.reset();
        this.viewerActionMenuCoordinator.clearAll();
    }

    public void invalidateRenderFingerprints() {
        this.regionDisplayCoordinator.invalidateFingerprints();
    }

    public void clearReadyPlayersForLifecycle() {
        this.participants.clearReadyPlayers();
    }

    public void clearLeaveQueueForLifecycle() {
        this.participants.clearLeaveQueue();
    }

    public void clearSpectatorsForLifecycle() {
        this.participants.clearSpectators();
    }

    public void clearBotNamesForLifecycle() {
        this.participants.clearBotNames();
        this.botDisplayNameCache.clear();
    }

    public void clearSeatAssignmentsForLifecycle() {
        this.unattendedPlayers.clear();
        this.participants.clearSeats();
        if (this.ownerId != null && this.isBot(this.ownerId)) {
            this.ownerId = null;
        }
    }

    public void clearEngineForLifecycle() {
        this.setRoundControllerInternal(null);
    }

    public void resetBotCounterForLifecycle() {
        this.participants.resetBotCounter();
    }

    public void resetReadyStateForNextRound() {
        this.participants.readyBotsOnly();
    }

    public void promptPlayersToReady() {
        for (UUID playerId : this.participants.seatIds()) {
            if (playerId == null || this.isBot(playerId) || this.isQueuedToLeave(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                continue;
            }
            this.plugin.messages().send(player, "command.ready_prompt");
        }
    }

    public SeatWind currentSeat() {
        if (this.roundController == null || !this.roundController.started()) {
            return SeatWind.EAST;
        }
        return this.roundController.currentSeat();
    }

    public UUID playerAt(SeatWind wind) {
        if (this.roundController != null && this.roundController.started() && !this.roundController.gameFinished()) {
            UUID startedSeat = this.roundController.playerAt(wind);
            if (startedSeat != null) {
                return startedSeat;
            }
        }
        return this.participants.playerAt(wind);
    }

    public String playerName(SeatWind wind) {
        return this.displayName(this.playerAt(wind));
    }

    public String displayName(UUID playerId) {
        return this.displayName(playerId, this.publicLocale());
    }

    public String displayName(UUID playerId, Locale locale) {
        if (playerId == null) {
            return this.plugin.messages().plain(locale, "common.empty");
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            return player.getName();
        }
        if (this.participants.isBot(playerId)) {
            return this.botDisplayName(playerId, locale);
        }
        RiichiRoundEngine engine = this.riichiEngineOrNull();
        if (engine != null) {
            RiichiPlayerState riichiPlayer = engine.seatPlayer(playerId.toString());
            if (riichiPlayer != null) {
                String name = riichiPlayer.getDisplayName();
                if (name != null) {
                    return name;
                }
            }
        }
        return this.plugin.messages().plain(locale, "common.offline");
    }

    public boolean isBot(UUID playerId) {
        return this.participants.isBot(playerId);
    }

    public int points(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        if (this.roundController != null) {
            for (SeatWind wind : SeatWind.values()) {
                if (Objects.equals(this.roundController.playerAt(wind), playerId)) {
                    // Zero and negative scores are legal in the Chinese
                    // variants, so the value itself cannot be used as a
                    // sentinel for "player not present in this controller".
                    return this.roundController.points(playerId);
                }
            }
        }
        return this.contains(playerId) ? this.configuredRule.getStartingPoints() : 0;
    }

    public boolean isRiichi(UUID playerId) {
        return playerId != null && this.roundController != null && this.roundController.isRiichi(playerId);
    }

    public int dicePoints() {
        return this.intFromRoundController(TableRoundController::dicePoints);
    }

    public int kanCount() {
        return this.intFromRoundController(TableRoundController::kanCount);
    }

    public int roundIndex() {
        return this.intFromRoundController(TableRoundController::roundIndex);
    }

    public SeatWind openDoorSeat() {
        return this.fromRoundController(
            controller -> SeatWind.fromIndex(Math.floorMod(
                controller.dicePoints() - 1 + controller.dealerSeat().index(),
                SeatWind.values().length
            )),
            SeatWind.EAST
        );
    }

    @Override
    public int breakDicePoints() {
        return this.intFromRoundController(TableRoundController::dicePoints2);
    }

    public int honbaCount() {
        return this.intFromRoundController(TableRoundController::honbaCount);
    }

    public SeatWind roundWind() {
        return this.fromRoundController(TableRoundController::roundWind, SeatWind.EAST);
    }

    public SeatWind dealerSeat() {
        return this.fromRoundController(TableRoundController::dealerSeat, SeatWind.EAST);
    }

    public String waitingSummary() {
        return this.waitingSummary(this.publicLocale());
    }

    public String waitingSummary(Locale locale) {
        return this.publicTextFactory.waitingSummary(locale);
    }

    public String waitingDisplaySummary() {
        return this.waitingDisplaySummary(this.publicLocale());
    }

    public String waitingDisplaySummary(Locale locale) {
        return this.publicTextFactory.waitingDisplaySummary(locale);
    }

    public String ruleDisplaySummary() {
        return this.ruleDisplaySummary(this.publicLocale());
    }

    public String ruleDisplaySummary(Locale locale) {
        return this.publicTextFactory.ruleDisplaySummary(locale);
    }

    public String ruleSummary() {
        return this.ruleSummary(this.publicLocale());
    }

    public String ruleSummary(Locale locale) {
        return this.publicTextFactory.ruleSummary(locale);
    }

    public boolean setRuleOption(String key, String rawValue) {
        return this.ruleCoordinator.setRuleOption(key, rawValue);
    }

    public List<String> ruleKeys() {
        return this.ruleCoordinator.ruleKeys();
    }

    public List<String> ruleValues(String key) {
        return this.ruleCoordinator.ruleValues(key);
    }

    public List<top.ellan.mahjong.model.MahjongTile> hand(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::hand, List.of());
    }

    public List<top.ellan.mahjong.model.MahjongTile> discards(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::discards, List.of());
    }

    public int riichiDiscardIndex(UUID playerId) {
        if (playerId == null) {
            return -1;
        }
        RiichiRoundEngine engine = this.riichiEngineOrNull();
        if (engine == null) {
            return -1;
        }
        RiichiPlayerState player = engine.seatPlayer(playerId.toString());
        if (player == null || player.getRiichiSengenTile() == null) {
            return -1;
        }
        return resolveRiichiDiscardIndex(player);
    }

    private static int resolveRiichiDiscardIndex(RiichiPlayerState player) {
        int displayIndex = player.getDiscardedTilesForDisplay().indexOf(player.getRiichiSengenTile());
        if (displayIndex >= 0) {
            return displayIndex;
        }

        int declaredIndex = player.getDiscardedTiles().indexOf(player.getRiichiSengenTile());
        if (declaredIndex < 0) {
            return -1;
        }

        for (int i = declaredIndex + 1; i < player.getDiscardedTiles().size(); i++) {
            int shiftedDisplayIndex = player.getDiscardedTilesForDisplay().indexOf(player.getDiscardedTiles().get(i));
            if (shiftedDisplayIndex >= 0) {
                return shiftedDisplayIndex;
            }
        }
        return -1;
    }

    public List<top.ellan.mahjong.model.MahjongTile> remainingWall() {
        return this.fromRoundController(TableRoundController::remainingWall, List.of());
    }

    public int remainingWallCount() {
        return this.intFromRoundController(TableRoundController::remainingWallCount);
    }

    public List<MeldView> fuuro(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::fuuro, List.of());
    }

    public List<ScoringStick> scoringSticks(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::scoringSticks, List.of());
    }

    public int riichiPoolCount() {
        RiichiRoundEngine engine = this.riichiEngineOrNull();
        if (engine == null) {
            return 0;
        }
        return engine.getSeats().stream().mapToInt(RiichiPlayerState::getRiichiStickAmount).sum();
    }

    public List<ScoringStick> cornerSticks(SeatWind wind) {
        // Honba sticks live on the dealer's corner. For non-dealer seats the
        // result is always the empty list; for the dealer it is N copies of
        // ScoringStick.P100 where N = honbaCount. Both branches return an
        // immutable List (List.of() / Collections.nCopies) so callers must
        // never mutate the result. This avoids the previous per-call pattern
        // of new ArrayList() + N add() + List.copyOf(), which ran 4x per
        // render pass via stickLayoutCount + captureSeatSnapshot.
        if (this.dealerSeat() != wind) {
            return List.of();
        }
        int honba = this.honbaCount();
        if (honba <= 0) {
            return List.of();
        }
        return java.util.Collections.nCopies(honba, ScoringStick.P100);
    }

    public int stickLayoutCount(SeatWind wind) {
        return this.cornerSticks(wind).size();
    }

    public Player onlinePlayer(UUID playerId) {
        return Bukkit.getPlayer(playerId);
    }

    public boolean isSpectator(UUID playerId) {
        return this.participants.spectatorIds().contains(playerId);
    }

    public List<Player> viewers() {
        return this.viewerIndex.viewers();
    }

    public List<UUID> viewerIdsExcluding(UUID excludedPlayerId) {
        return this.viewerIndex.viewerIdsExcluding(excludedPlayerId);
    }

    public String roundDisplay() {
        return this.roundDisplay(this.publicLocale());
    }

    public String roundDisplay(Locale locale) {
        return this.publicTextFactory.roundDisplay(locale);
    }

    public String dealerName() {
        return this.dealerName(this.publicLocale());
    }

    public String dealerName(Locale locale) {
        return this.publicTextFactory.dealerName(locale);
    }

    public List<top.ellan.mahjong.model.MahjongTile> doraIndicators() {
        return this.fromRoundController(TableRoundController::doraIndicators, List.of());
    }

    public List<top.ellan.mahjong.model.MahjongTile> uraDoraIndicators() {
        return this.fromRoundController(TableRoundController::uraDoraIndicators, List.of());
    }

    public top.ellan.mahjong.riichi.RoundResolution lastResolution() {
        return this.fromRoundController(TableRoundController::lastResolution, null);
    }

    public boolean openSettlementUi(Player player) {
        if (this.lastResolution() == null) {
            return false;
        }
        SettlementUi.open(player, this);
        return true;
    }

    public Component stateSummary(Player player) {
        return this.viewerSnapshotFactory.createStateSummary(player);
    }

    public Component viewerOverlay(Player viewer) {
        return this.viewerSnapshotFactory.createViewerOverlay(viewer);
    }

    public Component spectatorSeatOverlay(Player viewer, SeatWind wind) {
        return this.spectatorSeatOverlay(this.plugin.messages().resolveLocale(viewer), wind);
    }

    private Component spectatorSeatOverlay(Locale locale, SeatWind wind) {
        return this.sessionMessaging.spectatorSeatOverlay(locale, wind);
    }

    public boolean isStarted() {
        return this.roundController != null && this.roundController.started();
    }

    public boolean isRoundStartInProgress() {
        return this.roundStartInProgress;
    }

    public Optional<RiichiRoundEngine> riichiEngine() {
        return Optional.ofNullable(this.riichiRoundEngine);
    }

    private RiichiRoundEngine riichiEngineOrNull() {
        return this.riichiRoundEngine;
    }

    public boolean hasRoundController() {
        return this.roundController != null;
    }

    public boolean isRoundFinished() {
        return this.roundController != null && this.roundController.gameFinished();
    }

    public String currentTurnDisplayName() {
        return this.fromRoundController(TableRoundController::currentPlayerDisplayName, "");
    }

    public top.ellan.mahjong.riichi.ReactionOptions availableReactions(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::availableReactions, null);
    }

    public boolean hasPendingReaction() {
        return this.roundController != null && this.roundController.hasPendingReaction();
    }

    public boolean isReactionPending(UUID playerId) {
        return playerId != null && this.roundController != null && this.roundController.isReactionPending(playerId);
    }

    public String pendingReactionFingerprint() {
        return this.fromRoundController(TableRoundController::pendingReactionFingerprint, "");
    }

    public String pendingReactionTileKey() {
        return this.fromRoundController(TableRoundController::pendingReactionTileKey, "");
    }

    public boolean canDeclareRiichi(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::canDeclareRiichi);
    }

    public boolean canDeclareKan(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::canDeclareKan);
    }

    public boolean canDeclareConcealedKan(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::canDeclareConcealedKan);
    }

    public boolean canDeclareAddedKan(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::canDeclareAddedKan);
    }

    public boolean canDeclareKyuushu(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::canDeclareKyuushu);
    }

    public boolean canDeclareTsumo(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::canDeclareTsumo);
    }

    public boolean canDeclareFlower(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::canDeclareFlower);
    }

    public boolean canChooseSichuanMissingSuit(UUID playerId) { return this.fromGbController(playerId, GbTableRoundController::canChooseSichuanMissingSuit, false); }

    public boolean isSichuanExchangePhase(UUID playerId) { return this.fromGbController(playerId, GbTableRoundController::isSichuanExchangePhase, false); }

    public boolean chooseSichuanMissingSuit(UUID playerId, String suitToken) {
        boolean result = this.fromGbController(playerId, (controller, actorId) -> controller.chooseSichuanMissingSuit(actorId, suitToken), false);
        if (!result) { return false; }
        this.actionDeadlineCoordinator.recordAction(playerId);
        this.clearSelectedHandTilesInternal();
        this.render();
        return true;
    }

    public boolean submitSichuanExchangeSelection(UUID playerId, List<Integer> tileIndices) {
        boolean result = this.fromGbController(playerId, (controller, actorId) -> controller.submitSichuanExchangeSelection(actorId, tileIndices), false);
        if (!result) { return false; }
        this.actionDeadlineCoordinator.recordAction(playerId);
        this.clearSelectedHandTilesInternal();
        this.render();
        return true;
    }

    public List<Integer> suggestedRiichiIndices(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::suggestedRiichiIndices, List.of());
    }

    public List<Integer> suggestedFlowerIndices(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::suggestedFlowerIndices, List.of());
    }

    public List<String> suggestedKanTiles(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::suggestedKanTiles, List.of());
    }

    public List<String> suggestedConcealedKanTiles(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::suggestedConcealedKanTiles, List.of());
    }

    public List<String> suggestedAddedKanTiles(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::suggestedAddedKanTiles, List.of());
    }

    public List<String> suggestedDiscardTiles(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::suggestedDiscardTiles, List.of());
    }

    public List<top.ellan.mahjong.riichi.RiichiDiscardSuggestion> suggestedDiscardSuggestions(UUID playerId) {
        return this.fromRoundController(playerId, TableRoundController::suggestedDiscardSuggestions, List.of());
    }

    public boolean canSelectHandTile(UUID playerId, int tileIndex) {
        return this.canSelectHandTileInternal(playerId, tileIndex);
    }

    public top.ellan.mahjong.gb.jni.GbTingResponse gbTingOptions(UUID playerId) {
        return this.fromGbController(
            playerId,
            GbTableRoundController::tingOptions,
            new top.ellan.mahjong.gb.jni.GbTingResponse(false, List.of(), "GB round is inactive.")
        );
    }

    public boolean gbCanWinByTsumo(UUID playerId) {
        return this.fromGbController(playerId, GbTableRoundController::canWinByTsumo, false);
    }

    public int gbSuggestedDiscardIndex(UUID playerId) {
        return this.fromGbController(playerId, GbTableRoundController::suggestedBotDiscardIndex, -1);
    }

    public ReactionResponse gbSuggestedReaction(UUID playerId) {
        return this.fromGbController(playerId, GbTableRoundController::suggestedBotReaction, ReactionResponses.SKIP);
    }

    public String gbSuggestedKanTile(UUID playerId) {
        return this.fromGbController(playerId, GbTableRoundController::suggestedBotKanTile, null);
    }

    public synchronized void setBotTask(PluginTask botTask) {
        PluginTask old = this.botTask;
        if (old != null && old != botTask) {
            old.cancel();
        }
        this.botTask = botTask;
    }

    /**
     * Atomically clear the bot task only if it still references the expected
     * task. This is used by bot callbacks to avoid clobbering a new task that
     * was set by the render cycle during execution.
     */
    public synchronized void clearBotTaskIfSame(PluginTask expected) {
        if (this.botTask == expected) {
            this.botTask = null;
        }
    }

    /**
     * Whether a bot turn / reaction task is currently armed. Used by the
     * tick-driven watchdog in MahjongTableManager so it can re-schedule a
     * bot if and only if the regular render flush path failed to do so.
     */
    public boolean hasArmedBotTask() {
        PluginTask current = this.botTask;
        return current != null && !current.isCancelled();
    }

    public synchronized void cancelBotTask() {
        if (this.botTask != null) {
            this.botTask.cancel();
            this.botTask = null;
        }
    }

    public void tick() {
        this.actionDeadlineCoordinator.tick();
        this.roundFlowCoordinator.tick();
    }

    public long actionDeadlineSecondsRemaining(UUID playerId) {
        return this.actionDeadlineCoordinator.secondsRemaining(playerId);
    }

    private MahjongRule currentRule() {
        return this.roundController == null ? this.configuredRule : this.roundController.rule();
    }

    private int intFromRoundController(ToIntFunction<TableRoundController> extractor) {
        return this.roundController == null ? 0 : extractor.applyAsInt(this.roundController);
    }

    private <T> T fromRoundController(Function<TableRoundController, T> extractor, T fallback) {
        return this.roundController == null ? fallback : extractor.apply(this.roundController);
    }

    private <T> T fromRoundController(UUID playerId, BiFunction<TableRoundController, UUID, T> extractor, T fallback) {
        return playerId == null || this.roundController == null ? fallback : extractor.apply(this.roundController, playerId);
    }

    private boolean fromRoundController(UUID playerId, BiPredicate<TableRoundController, UUID> extractor) {
        return playerId != null && this.roundController != null && extractor.test(this.roundController, playerId);
    }

    private <T> T fromGbController(UUID playerId, BiFunction<GbTableRoundController, UUID, T> extractor, T fallback) {
        if (!(this.roundController instanceof GbTableRoundController gbController) || playerId == null) {
            return fallback;
        }
        return extractor.apply(gbController, playerId);
    }

    private TableRoundController createRoundController() {
        EnumMap<SeatWind, UUID> seats = new EnumMap<>(SeatWind.class);
        Map<UUID, String> displayNames = new HashMap<>();
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = this.participants.playerAt(wind);
            if (playerId == null) {
                throw new IllegalStateException("A table needs exactly 4 occupied seats");
            }
            seats.put(wind, playerId);
            displayNames.put(playerId, this.displayName(playerId));
        }
        if (this.currentVariant() != MahjongVariant.RIICHI) {
            return new GbTableRoundController(
                this.copyRule(),
                seats,
                displayNames,
                new GbNativeRulesGateway(),
                GbRuleProfile.forVariant(this.currentVariant())
            );
        }
        List<RiichiPlayerState> players = new ArrayList<>(SeatWind.values().length);
        for (SeatWind wind : SeatWind.values()) {
            UUID playerId = seats.get(wind);
            players.add(new RiichiPlayerState(displayNames.get(playerId), playerId.toString(), !this.isBot(playerId)));
        }
        return new RiichiTableRoundController(new RiichiRoundEngine(players, this.copyRule()));
    }

    public MahjongVariant currentVariant() {
        return this.configuredVariant;
    }

    public boolean applyRulePreset(String rawValue) {
        return this.ruleCoordinator.applyRulePreset(rawValue);
    }

    public boolean clickHandTile(UUID playerId, int tileIndex, boolean cancelSelection) {
        boolean wasSichuanExchangePending = this.isSichuanExchangePhase(playerId);
        boolean result = this.handSelectionCoordinator.clickHandTile(playerId, tileIndex, cancelSelection);
        if (result && wasSichuanExchangePending && !this.isSichuanExchangePhase(playerId)) {
            this.actionDeadlineCoordinator.recordAction(playerId);
        }
        return result;
    }

    public int selectedHandTileIndex(UUID playerId) { List<Integer> selected = this.selectedHandTileIndices(playerId); return selected.isEmpty() ? -1 : selected.get(0); }

    @Override
    public List<Integer> selectedHandTileIndices(UUID playerId) {
        List<Integer> controllerSelected = this.fromRoundController(playerId, TableRoundController::selectedHandTileIndices, List.of());
        return controllerSelected.isEmpty() ? this.handSelectionCoordinator.selectedHandTileIndices(playerId) : controllerSelected;
    }

    public void refreshSelectedHandTileViewInternal(UUID playerId) {
        SeatWind wind = this.seatOf(playerId);
        if (wind == null) { return; }
        TableSeatRenderSnapshot seat = this.renderSnapshotFactory.createPrivateHandSeat(this, wind);
        if (seat == null || seat.playerId() == null) { return; }
        TableRenderLayout.SeatLayoutPlan seatPlan = TableRenderLayout.precomputePrivateHandOnly(
            this.center.getX(),
            this.center.getY(),
            this.center.getZ(),
            seat
        );
        if (seatPlan == null) { return; }
        this.regionDisplayCoordinator.refreshPrivateHandRegions(seat, seatPlan);
    }

    public boolean canSelectHandTileInternal(UUID playerId, int tileIndex) { return this.contains(playerId) && tileIndex >= 0 && this.roundController != null && this.roundController.canSelectHandTile(playerId, tileIndex); }

    @Override
    public boolean handleHandTileClickInternal(UUID playerId, int tileIndex, boolean cancelSelection) { return this.contains(playerId) && tileIndex >= 0 && this.roundController != null && this.roundController.handleHandTileClick(playerId, tileIndex, cancelSelection); }

    public top.ellan.mahjong.model.MahjongTile handTileAtInternal(UUID playerId, int tileIndex) {
        List<top.ellan.mahjong.model.MahjongTile> hand = this.hand(playerId);
        if (tileIndex < 0 || tileIndex >= hand.size()) {
            return null;
        }
        return hand.get(tileIndex);
    }

    public void rememberPublicDiscardInternal(UUID playerId, top.ellan.mahjong.model.MahjongTile discardedTile) {
        if (discardedTile == null) {
            return;
        }
        this.lastPublicDiscardPlayerId = playerId;
        this.lastPublicDiscardTile = discardedTile;
    }

    public void clearLastPublicDiscardInternal() {
        this.lastPublicDiscardPlayerId = null;
        this.lastPublicDiscardTile = null;
    }

    public void rememberPublicActionInternal(UUID playerId, String actionKey) {
        this.publicActionCoordinator.remember(playerId, actionKey);
    }

    public void rememberPublicActionInternal(UUID playerId, String actionKey, List<top.ellan.mahjong.model.MahjongTile> tiles) {
        this.publicActionCoordinator.remember(playerId, actionKey, tiles);
    }

    public void rememberPublicActionsInternal(List<UUID> playerIds, String actionKey) {
        this.publicActionCoordinator.remember(playerIds, actionKey);
    }

    public void clearLastPublicActionInternal() {
        this.publicActionCoordinator.clear();
    }

    public void clearSelectedHandTilesInternal() {
        this.handSelectionCoordinator.clearAll();
    }

    public String viewerActionMenuState(UUID viewerId) {
        return this.viewerActionMenuCoordinator.state(viewerId);
    }

    public void setViewerActionMenuState(UUID viewerId, String menuState) {
        if (viewerId == null) {
            return;
        }
        if (menuState == null || menuState.isBlank()) {
            this.clearViewerActionMenuState(viewerId);
            return;
        }
        if (this.viewerActionMenuCoordinator.set(viewerId, menuState)) {
            this.viewerPresentation.markViewerActionsDirty(viewerId);
        }
    }

    public void clearViewerActionMenuState(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        if (this.viewerActionMenuCoordinator.clear(viewerId)) {
            this.viewerPresentation.markViewerActionsDirty(viewerId);
        }
    }

    public void playReactionSoundInternal(ReactionResponse response) {
        this.stateSoundCoordinator.playReactionSound(response);
    }

    public void playDiscardSoundInternal() {
        this.stateSoundCoordinator.playDiscardSound();
    }

    public void playRiichiSoundInternal() {
        this.stateSoundCoordinator.playRiichiSound();
    }

    public void persistRoomMetadataIfNeededInternal() {
        if (this.persistentRoom && this.plugin.tableManager() != null) {
            this.plugin.tableManager().persistTables();
        }
    }

    public MahjongRule configuredRuleInternal() {
        return this.configuredRule;
    }

    public void setConfiguredRuleInternal(MahjongRule configuredRule) {
        this.configuredRule = copyRule(configuredRule);
    }

    public void setConfiguredVariantInternal(MahjongVariant configuredVariant) {
        this.configuredVariant = configuredVariant == null ? MahjongVariant.RIICHI : configuredVariant;
    }

    public TableRoundController roundControllerInternal() {
        return this.roundController;
    }

    public boolean seatAssignmentsMatchControllerInternal(TableRoundController controller) {
        if (controller == null) {
            return false;
        }
        for (SeatWind wind : SeatWind.values()) {
            if (!Objects.equals(this.participants.playerAt(wind), controller.playerAt(wind))) {
                return false;
            }
        }
        return true;
    }

    public void setRoundControllerInternal(TableRoundController roundController) {
        this.actionDeadlineCoordinator.clear();
        this.roundController = roundController;
        this.riichiRoundEngine = resolveRiichiEngine(roundController);
    }

    public TableRoundController createRoundControllerInternal() {
        return this.createRoundController();
    }

    public void setRoundStartInProgressInternal(boolean roundStartInProgress) {
        this.roundStartInProgress = roundStartInProgress;
    }

    public void resetRoundPresentationForStartInternal() {
        this.playerFeedbackCoordinator.resetForRoundStart();
        this.stateSoundCoordinator.resetForRoundStart();
    }

    public boolean shouldAnimateOpeningDiceInternal() {
        return this.diceAnimationCoordinator.shouldAnimate();
    }

    public void startOpeningDiceAnimationInternal(OpeningDiceRoll diceRoll, Runnable completion) {
        this.diceAnimationCoordinator.start(diceRoll, completion);
    }

    public void completeRoundStartInternal() {
        if (this.roundController == null) {
            this.roundStartInProgress = false;
            return;
        }
        this.roundController.startRound();
        this.playerFeedbackCoordinator.onRoundStarted();
        this.roundStartInProgress = false;
        this.actionDeadlineCoordinator.beginRound();
        this.render();
        if (this.plugin.tableManager() != null && !this.plugin.settings().tableFreeMoveDuringRound()) {
            this.plugin.tableManager().startSeatWatchdog(this, 60L);
        }
    }

    public void playRoundStartSoundInternal() {
        this.stateSoundCoordinator.playRoundStartSound();
    }

    public void restoreDisplaysIfNeededInternal() {
        this.renderCoordinator.restoreDisplaysIfNeeded();
    }

    public void flushViewerPresentationIfNeededInternal() {
        this.viewerPresentation.flushIfNeeded();
    }

    public void flushViewerActionsNow(UUID viewerId) {
        this.viewerPresentation.flushViewerActionsNow(viewerId);
    }

    public boolean hasQueuedLeavesInternal() {
        return this.participants.hasQueuedLeaves();
    }

    public Set<UUID> queuedLeavePlayersInternal() {
        return this.participants.queuedLeavePlayers();
    }

    public void removeQueuedLeavesInternal(List<UUID> removedPlayerIds) {
        this.participants.removeQueuedLeaves(removedPlayerIds);
    }

    public void finalizeDeferredLeaves(Map<UUID, SeatWind> removed) {
        if (this.plugin.tableManager() != null) {
            this.plugin.tableManager().finalizeDeferredLeaves(this, removed);
        }
    }

    private MahjongRule copyRule() {
        return copyRule(this.configuredRule);
    }

    private static MahjongRule copyRule(MahjongRule rule) {
        return new MahjongRule(
            rule.getLength(),
            rule.getThinkingTime(),
            rule.getStartingPoints(),
            rule.getMinPointsToWin(),
            rule.getMinimumHan(),
            rule.getSpectate(),
            rule.getRedFive(),
            rule.getOpenTanyao(),
            false,
            rule.getRonMode(),
            rule.getRiichiProfile()
        );
    }

    private static RiichiRoundEngine resolveRiichiEngine(TableRoundController roundController) {
        if (roundController == null) {
            return null;
        }
        return roundController.accept(new TableRoundController.VariantVisitor<>() {
            @Override
            public RiichiRoundEngine visitRiichi(RiichiTableRoundController controller) {
                return controller.roundEngine();
            }

            @Override
            public RiichiRoundEngine visitGb(GbTableRoundController controller) {
                return null;
            }
        });
    }

    public List<TableFinalStanding> finalStandings() {
        if (this.roundController == null || !this.roundController.gameFinished()) {
            return List.of();
        }
        RiichiRoundEngine riichiEngine = this.riichiEngineOrNull();
        if (this.currentVariant() != MahjongVariant.RIICHI || riichiEngine == null) {
            List<TableFinalStanding> standings = new ArrayList<>();
            for (UUID playerId : this.players()) {
                standings.add(new TableFinalStanding(
                    playerId,
                    this.displayName(playerId),
                    0,
                    this.points(playerId),
                    0.0D,
                    this.isBot(playerId)
                ));
            }
            standings.sort((left, right) -> {
                int scoreCompare = Integer.compare(right.points(), left.points());
                if (scoreCompare != 0) {
                    return scoreCompare;
                }
                return Integer.compare(this.players().indexOf(left.playerId()), this.players().indexOf(right.playerId()));
            });
            List<TableFinalStanding> ranked = new ArrayList<>(standings.size());
            for (int i = 0; i < standings.size(); i++) {
                TableFinalStanding standing = standings.get(i);
                ranked.add(new TableFinalStanding(standing.playerId(), standing.displayName(), i + 1, standing.points(), standing.gameScore(), standing.bot()));
            }
            return List.copyOf(ranked);
        }
        List<RiichiPlayerState> ranked = new ArrayList<>(riichiEngine.placementOrder());

        List<TableFinalStanding> standings = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            RiichiPlayerState player = ranked.get(i);
            double gameScore = MahjongSoulScoring.gameScore(player.getPoints(), i + 1);
            UUID playerId = UUID.fromString(player.getUuid());
            standings.add(new TableFinalStanding(playerId, player.getDisplayName(), i + 1, player.getPoints(), gameScore, this.isBot(playerId)));
        }
        return List.copyOf(standings);
    }

    public TableViewerOverlaySnapshot captureViewerOverlaySnapshot(Player viewer) {
        return this.viewerSnapshotFactory.captureViewerOverlaySnapshot(viewer);
    }

    public TableViewerHudPresentationSnapshot captureViewerHudPresentationSnapshot(Locale locale, UUID viewerId) {
        return this.viewerSnapshotFactory.captureViewerHudPresentationSnapshot(locale, viewerId);
    }

    public TableViewerHudSnapshot captureViewerHudSnapshot(Locale locale, UUID viewerId) {
        return this.viewerSnapshotFactory.captureViewerHudSnapshot(locale, viewerId);
    }

    public void updateViewerOverlayRegion(TableViewerOverlaySnapshot snapshot) {
        this.regionDisplayCoordinator.updateViewerOverlayRegion(snapshot);
    }

    public void updateViewerActionRegions(TableViewerOverlaySnapshot snapshot) {
        this.regionDisplayCoordinator.updateViewerActionRegions(snapshot);
    }

    public List<String> viewerOverlayRegionKeys() {
        return this.regionDisplayCoordinator.regionKeysWithPrefix("viewer-");
    }

    public void removeManagedRegionDisplays(String regionKey) {
        this.regionDisplayCoordinator.removeManagedRegionDisplays(regionKey);
    }

    TableRegionDisplayCoordinator regionDisplaysInternal() {
        return this.regionDisplayCoordinator;
    }

    public List<UUID> seatIds() {
        return this.participants.seatIds();
    }

    public String viewerMembershipSignatureFor(UUID excludedPlayerId) {
        return this.viewerIndex.viewerMembershipSignatureFor(excludedPlayerId);
    }

    public void scheduleNextRoundCountdownInternal() {
        this.roundLifecycle.scheduleNextRoundCountdown();
    }

    public boolean hasNextRoundCountdownInternal() {
        return this.roundLifecycle.hasNextRoundCountdown();
    }

    public void cancelNextRoundCountdownInternal() {
        this.cancelNextRoundCountdown();
    }

    public long nextRoundSecondsRemainingInternal() {
        return this.roundLifecycle.nextRoundSecondsRemaining();
    }

    public long nextRoundSecondsRemainingValue() {
        return this.roundLifecycle.nextRoundSecondsRemaining();
    }

    public void prepareRenderRequest() {
        this.cancelBotTask();
        this.handSelectionCoordinator.pruneSelectedHandTiles();
        this.viewerPresentation.markDirty();
    }

    public void completeRenderFlush() {
        this.playerFeedbackCoordinator.sync();
        this.viewerPresentation.flushIfNeeded();
        this.stateSoundCoordinator.syncStateSounds();
        BotActionScheduler.schedule(this);
    }

    public TableRenderSnapshot captureRenderSnapshot(long version, long cancellationNonce) {
        return this.renderSnapshotFactory.create(this, version, cancellationNonce);
    }

    public TableRenderPrecomputeResult precomputeRender(TableRenderSnapshot snapshot) {
        TableRenderLayout.LayoutPlan layout = this.renderLayoutCache.precompute(snapshot);
        return new TableRenderPrecomputeResult(
            snapshot,
            this.regionFingerprintService.precomputeRegionFingerprints(this, snapshot),
            layout
        );
    }

    public boolean isCenteredInChunk(Chunk chunk) {
        if (chunk == null) {
            return false;
        }
        World world = this.center.getWorld();
        return world != null
            && world.equals(chunk.getWorld())
            && Math.floorDiv(this.center.getBlockX(), 16) == chunk.getX()
            && Math.floorDiv(this.center.getBlockZ(), 16) == chunk.getZ();
    }

    public void clearRenderDisplays() {
        this.regionDisplayCoordinator.clearRenderDisplays();
    }

    public boolean hasRegionDisplays() {
        return this.regionDisplayCoordinator.hasRegionDisplays();
    }

    public boolean isCenterChunkLoaded() {
        World world = this.center.getWorld();
        return world != null
            && world.isChunkLoaded(Math.floorDiv(this.center.getBlockX(), 16), Math.floorDiv(this.center.getBlockZ(), 16));
    }

    public boolean hasStaleDisplayRegions() {
        return this.regionDisplayCoordinator.hasStaleDisplayRegions();
    }

    private static DelimitedFingerprintBuilder fingerprintBuilder(int capacity) {
        return DelimitedFingerprintBuilder.create(capacity);
    }

    public Locale publicLocale() {
        return LocalizedMessages.DEFAULT_LOCALE;
    }

    public TableRenderer renderer() {
        return this.renderer;
    }

    public String seatDisplayName(SeatWind wind, Locale locale) {
        return this.publicTextFactory.seatDisplayName(wind, locale);
    }

    public String publicSeatStatus(SeatWind wind) {
        return this.publicTextFactory.publicSeatStatus(wind);
    }

    public String publicCenterText() {
        return this.publicTextFactory.publicCenterText();
    }

    public String publicLastActionSummary(Locale locale) {
        return this.publicActionCoordinator.summary(locale);
    }

    public top.ellan.mahjong.model.MahjongTile lastPublicDiscardTile() {
        return this.lastPublicDiscardTile;
    }

    public UUID lastPublicDiscardPlayerId() {
        return this.lastPublicDiscardPlayerId;
    }

    public UUID lastPublicDiscardPlayerIdValue() {
        return this.lastPublicDiscardPlayerId;
    }

    private String botDisplayName(UUID playerId, Locale locale) {
        String cacheKey = locale.toLanguageTag() + '|' + playerId;
        String cached = this.botDisplayNameCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String raw = this.participants.botDisplayNameSource(playerId);
        String rendered;
        if (raw == null) {
            rendered = this.plugin.messages().plain(locale, "common.unknown");
        } else {
            int suffix = this.participants.seatIndexOf(playerId) + 1;
            rendered = this.plugin.messages().plain(
                locale,
                "table.bot_name",
                Map.of("index", this.plugin.messages().formatNumber(locale, "index", Math.max(1, suffix)))
            );
        }
        this.botDisplayNameCache.put(cacheKey, rendered);
        return rendered;
    }

    private void invalidateBotDisplayNameCache(UUID playerId) {
        if (playerId == null) {
            this.botDisplayNameCache.clear();
            return;
        }
        this.botDisplayNameCache.entrySet().removeIf(entry -> entry.getKey().endsWith('|' + playerId.toString()));
    }

    public String tileLabelForDisplay(Locale locale, String tileName) {
        String key = "tile." + tileName.toLowerCase(Locale.ROOT);
        return this.plugin.messages().contains(locale, key) ? this.plugin.messages().plain(locale, key) : tileName.toLowerCase(Locale.ROOT);
    }

    private static Location normalizedTableCenter(Location source) {
        Location normalized = source.clone();
        normalized.setYaw(0.0F);
        normalized.setPitch(0.0F);
        return normalized;
    }

    private SeatWind firstEmptySeat() {
        return this.participants.firstEmptySeat();
    }

    public enum ReadyResult {
        READY,
        UNREADY,
        STARTED,
        BLOCKED
    }

}
