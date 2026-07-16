package top.ellan.mahjong.table.runtime;

import top.ellan.mahjong.table.action.PlayerActionId;
import top.ellan.mahjong.table.action.PlayerActionPhase;
import top.ellan.mahjong.table.action.PlayerActionSnapshot;
import top.ellan.mahjong.table.action.PlayerActionSnapshotFactory;
import top.ellan.mahjong.table.core.MahjongTableSession;
import top.ellan.mahjong.runtime.PluginTask;
import java.util.Objects;
import java.util.UUID;

final class GbBotStrategy implements BotStrategy {
    private static final int MAX_GB_TURN_RETRY_ATTEMPTS = 8;

    @Override
    public void schedule(MahjongTableSession session) {
        if (!session.isStarted()) {
            return;
        }
        PlayerActionSnapshotFactory actionSnapshots = new PlayerActionSnapshotFactory(session);
        for (UUID playerId : session.players()) {
            if (!session.isBot(playerId) || actionSnapshots.capture(playerId).phase() != PlayerActionPhase.REACTION) {
                continue;
            }
            final PluginTask[] holder = new PluginTask[1];
            holder[0] = session.plugin().scheduler().runRegionDelayed(
                session.center(),
                () -> this.safeHandleGbReaction(session, playerId, holder[0]),
                20L
            );
            session.setBotTask(holder[0]);
            return;
        }
        UUID current = session.playerAt(session.currentSeat());
        if (current != null && session.isBot(current) && actionSnapshots.capture(current).phase() == PlayerActionPhase.TURN) {
            final PluginTask[] holder = new PluginTask[1];
            holder[0] = session.plugin().scheduler().runRegionDelayed(
                session.center(),
                () -> this.safeHandleGbTurn(session, current, 0, holder[0]),
                20L
            );
            session.setBotTask(holder[0]);
        }
    }

    private void safeHandleGbReaction(MahjongTableSession session, UUID playerId, PluginTask currentTask) {
        try {
            this.handleGbReaction(session, playerId);
        } catch (RuntimeException exception) {
            session.plugin().getLogger().warning("GB bot reaction failed for table " + session.id() + ", player " + playerId + ": " + exception.getMessage());
        } finally {
            session.clearBotTaskIfSame(currentTask);
        }
    }

    private void safeHandleGbTurn(MahjongTableSession session, UUID playerId, int retryAttempts, PluginTask currentTask) {
        try {
            this.handleGbTurn(session, playerId, retryAttempts);
        } catch (RuntimeException exception) {
            session.plugin().getLogger().warning("GB bot turn failed for table " + session.id() + ", player " + playerId + ": " + exception.getMessage());
        } finally {
            // If handleGbTurn scheduled a retry, botTask now points to the retry
            // task (not currentTask), so clearBotTaskIfSame is a no-op — correct.
            // If the render cycle set a new task, same thing — no-op, correct.
            // If nothing replaced the task, we clear it so the watchdog can re-schedule.
            session.clearBotTaskIfSame(currentTask);
        }
    }

    private void handleGbReaction(MahjongTableSession session, UUID playerId) {
        PlayerActionSnapshot snapshot = new PlayerActionSnapshotFactory(session).capture(playerId);
        if (snapshot.phase() != PlayerActionPhase.REACTION) {
            return;
        }
        session.react(playerId, session.gbSuggestedReaction(playerId));
    }

    private void handleGbTurn(MahjongTableSession session, UUID playerId, int retryAttempts) {
        if (!session.isStarted() || session.hasPendingReaction() || !Objects.equals(session.playerAt(session.currentSeat()), playerId)) {
            return;
        }
        PlayerActionSnapshot snapshot = new PlayerActionSnapshotFactory(session).capture(playerId);
        if (snapshot.phase() != PlayerActionPhase.TURN) {
            return;
        }
        if (this.hasAction(snapshot, PlayerActionId.TSUMO) && session.gbCanWinByTsumo(playerId) && session.declareTsumo(playerId)) {
            return;
        }
        if (this.hasFlowerAction(snapshot)) {
            var flowerIndices = session.suggestedFlowerIndices(playerId);
            if (!flowerIndices.isEmpty() && session.declareFlower(playerId, flowerIndices.get(0))) {
                return;
            }
        }
        String kanTile = session.gbSuggestedKanTile(playerId);
        if (kanTile != null && this.hasKanAction(snapshot) && session.declareKan(playerId, kanTile)) {
            return;
        }
        var hand = session.hand(playerId);
        if (hand.isEmpty()) {
            return;
        }
        int discardIndex = session.gbSuggestedDiscardIndex(playerId);
        if (!session.canSelectHandTile(playerId, discardIndex)) {
            discardIndex = findSelectableDiscardIndex(session, playerId, hand.size());
        }
        if (discardIndex < 0) {
            this.scheduleGbTurnRetry(session, playerId, retryAttempts, "no-selectable-discard");
            return;
        }
        if (!session.discard(playerId, discardIndex)
            && session.isStarted()
            && !session.hasPendingReaction()
            && Objects.equals(session.playerAt(session.currentSeat()), playerId)) {
            this.scheduleGbTurnRetry(session, playerId, retryAttempts, "discard-failed");
        }
    }

    private boolean hasKanAction(PlayerActionSnapshot snapshot) {
        return this.hasAction(snapshot, PlayerActionId.ANKAN)
            || this.hasAction(snapshot, PlayerActionId.KAKAN)
            || this.hasAction(snapshot, PlayerActionId.MENU_TURN_KAN);
    }

    private boolean hasFlowerAction(PlayerActionSnapshot snapshot) {
        return this.hasAction(snapshot, PlayerActionId.FLOWER)
            || this.hasAction(snapshot, PlayerActionId.MENU_TURN_FLOWER);
    }

    private boolean hasAction(PlayerActionSnapshot snapshot, PlayerActionId actionId) {
        return snapshot.actions().stream().anyMatch(action -> action.actionId() == actionId);
    }

    private void scheduleGbTurnRetry(MahjongTableSession session, UUID playerId, int retryAttempts, String reason) {
        int nextRetry = retryAttempts + 1;
        if (nextRetry > MAX_GB_TURN_RETRY_ATTEMPTS) {
            session.plugin().getLogger().warning(
                "GB bot turn retry exhausted for table " + session.id()
                    + ", player " + playerId
                    + ", reason=" + reason
                    + ", maxRetries=" + MAX_GB_TURN_RETRY_ATTEMPTS
            );
            return;
        }
        final PluginTask[] holder = new PluginTask[1];
        holder[0] = session.plugin().scheduler().runRegionDelayed(
            session.center(),
            () -> this.safeHandleGbTurn(session, playerId, nextRetry, holder[0]),
            10L
        );
        session.setBotTask(holder[0]);
    }

    private static int findSelectableDiscardIndex(MahjongTableSession session, UUID playerId, int handSize) {
        for (int i = handSize - 1; i >= 0; i--) {
            if (session.canSelectHandTile(playerId, i)) {
                return i;
            }
        }
        return -1;
    }
}
