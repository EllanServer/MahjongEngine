package top.ellan.mahjong.table.presentation;

import top.ellan.mahjong.table.core.TableSessionMutator;
import top.ellan.mahjong.table.core.TableFinalStanding;
import top.ellan.mahjong.rank.PlayerRankStorage;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class TablePlayerFeedbackCoordinator {
    private final TableSessionMutator session;
    private String lastSettlementFingerprint = "";
    private final Map<PersistenceKey, CompletableFuture<Void>> pendingSettlementPersistence = new HashMap<>();
    private final Map<PersistenceKey, CompletableFuture<Void>> pendingRankPersistence = new HashMap<>();
    private final Set<PersistenceKey> confirmedSettlementPersistence = new HashSet<>();
    private final Set<PersistenceKey> confirmedRankPersistence = new HashSet<>();
    private final Set<PersistenceKey> failedSettlementPersistence = new HashSet<>();
    private final Set<PersistenceKey> failedRankPersistence = new HashSet<>();
    private long persistenceEpoch;

    public TablePlayerFeedbackCoordinator(TableSessionMutator session) {
        this.session = session;
    }

    public void sync() {
        if (!this.session.hasRoundController()) {
            this.resetState();
            return;
        }

        String settlementFingerprint = Objects.toString(this.session.lastResolution(), "");
        this.persistSettlementIfNeeded(settlementFingerprint);
        this.persistRankIfNeeded(settlementFingerprint);
        boolean settlementChanged = SettlementFeedbackGate.isNewSettlement(settlementFingerprint, this.lastSettlementFingerprint);
        if (settlementChanged) {
            this.lastSettlementFingerprint = settlementFingerprint;
        }
        this.syncSettlementFeedback(settlementFingerprint, settlementChanged);
    }

    public void clearPlayerState(UUID playerId) {
        // Routine decisions are owned by the viewer prompt/action regions, whose
        // lifecycle is keyed directly by viewer ID. There is no secondary chat or
        // action-bar state to clear here.
    }

    public void resetForRoundStart() {
        String currentSettlementFingerprint = this.currentSettlementFingerprint();
        this.persistSettlementIfNeeded(currentSettlementFingerprint);
        this.persistRankIfNeeded(currentSettlementFingerprint);
        this.lastSettlementFingerprint = currentSettlementFingerprint;
    }

    /**
     * Arms settlement processing for the round that has just started.
     *
     * <p>{@link #resetForRoundStart()} deliberately remembers the previous
     * resolution while the opening-dice animation is running, because the
     * controller has not cleared that resolution yet. Once {@code startRound}
     * has actually run, the old resolution is gone and equal-valued future
     * resolutions must be treated as a new hand rather than as duplicate
     * render feedback.</p>
     */
    public synchronized void onRoundStarted() {
        this.persistenceEpoch++;
        this.confirmedSettlementPersistence.removeIf(key -> key.epoch() < this.persistenceEpoch);
        this.confirmedRankPersistence.removeIf(key -> key.epoch() < this.persistenceEpoch);
        this.failedSettlementPersistence.removeIf(key -> key.epoch() < this.persistenceEpoch);
        this.failedRankPersistence.removeIf(key -> key.epoch() < this.persistenceEpoch);
        this.lastSettlementFingerprint = "";
    }

    public void resetState() {
        this.lastSettlementFingerprint = "";
        this.session.cancelNextRoundCountdown();
    }

    private String currentSettlementFingerprint() {
        return Objects.toString(this.session.lastResolution(), "");
    }

    private synchronized void persistSettlementIfNeeded(String settlementFingerprint) {
        PersistenceKey key = new PersistenceKey(this.persistenceEpoch, settlementFingerprint);
        if (settlementFingerprint.isBlank()
            || this.confirmedSettlementPersistence.contains(key)
            || this.pendingSettlementPersistence.containsKey(key)
            || this.failedSettlementPersistence.contains(key)
            || this.session.plugin().database() == null) {
            return;
        }
        CompletableFuture<Void> pending = this.session.plugin().database()
            .persistRoundResultAsync(this.session, this.session.lastResolution());
        this.pendingSettlementPersistence.put(key, pending);
        pending.whenComplete((ignored, failure) -> this.completeSettlementPersistence(key, failure));
    }

    private synchronized void persistRankIfNeeded(String settlementFingerprint) {
        PersistenceKey key = new PersistenceKey(this.persistenceEpoch, settlementFingerprint);
        PlayerRankStorage storage = this.session.plugin().playerRankStorage();
        if (settlementFingerprint.isBlank()
            || this.confirmedRankPersistence.contains(key)
            || this.pendingRankPersistence.containsKey(key)
            || this.failedRankPersistence.contains(key)
            || storage == null
            || !storage.rankingEnabled()) {
            return;
        }
        List<TableFinalStanding> standings = this.session.finalStandings();
        if (standings.isEmpty()) {
            return;
        }
        String operationId = this.session.id() + ':' + key.epoch() + ':' + settlementFingerprint;
        CompletableFuture<Void> pending = storage.persistMatchRanksAsync(
            operationId,
            this.session.id(),
            this.session.currentVariant(),
            this.session.configuredRuleSnapshot().getLength(),
            standings
        );
        this.pendingRankPersistence.put(key, pending);
        pending.whenComplete((ignored, failure) -> this.completeRankPersistence(key, failure));
    }

    private synchronized void completeSettlementPersistence(PersistenceKey key, Throwable failure) {
        this.pendingSettlementPersistence.remove(key);
        if (failure == null) {
            this.confirmedSettlementPersistence.add(key);
        } else {
            this.failedSettlementPersistence.add(key);
        }
    }

    private synchronized void completeRankPersistence(PersistenceKey key, Throwable failure) {
        this.pendingRankPersistence.remove(key);
        if (failure == null) {
            this.confirmedRankPersistence.add(key);
        } else {
            this.failedRankPersistence.add(key);
        }
    }

    private void syncSettlementFeedback(String settlementFingerprint, boolean settlementChanged) {
        if (settlementFingerprint.isBlank() || !settlementChanged) {
            return;
        }
        this.session.plugin().tableManager().overheadViews().closeTable(this.session.id());
        this.session.resetReadyStateForNextRound();
        this.session.render();
        this.openSettlementForPlayers();
        this.session.cancelNextRoundCountdown();
        this.session.promptPlayersToReady();
    }

    private void openSettlementForPlayers() {
        for (UUID playerId : this.session.seatIds()) {
            if (playerId == null || this.session.isBot(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                continue;
            }
            this.session.openSettlementUi(player);
        }
    }

    private record PersistenceKey(long epoch, String fingerprint) {
    }

}
