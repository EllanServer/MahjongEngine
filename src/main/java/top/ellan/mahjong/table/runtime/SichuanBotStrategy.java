package top.ellan.mahjong.table.runtime;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.table.action.PlayerActionPhase;
import top.ellan.mahjong.table.action.PlayerActionSnapshot;
import top.ellan.mahjong.table.action.PlayerActionSnapshotFactory;
import top.ellan.mahjong.table.core.MahjongTableSession;
import top.ellan.mahjong.table.core.SichuanSessionAccess;
import top.ellan.mahjong.runtime.PluginTask;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bot strategy tailored for Sichuan Mahjong (四川麻将).
 *
 * <p>Sichuan Mahjong differs from GB in several key aspects that affect bot play:
 * <ul>
 *   <li><b>Missing one suit (缺一门)</b> — a valid hand must be missing at least one
 *       of the three numbered suits (万/条/筒). The bot prioritises discarding tiles
 *       from the suit it intends to abandon.</li>
 *   <li><b>Blood battle (血战到底)</b> — after a player wins, the remaining players
 *       continue until only one is left. The controller already skips settled players,
 *       so the bot just needs to keep playing aggressively.</li>
 *   <li><b>No chii (不能吃)</b> — the controller already returns empty chii pairs for
 *       Sichuan, so the reaction logic only considers pon, kan, and ron.</li>
 *   <li><b>Ordinary final-four play</b> — the default T/TFMJ profile still lets a
 *       player pass a win, discard, pon, or kan near the end of the wall.</li>
 *   <li><b>Declared missing suit (定缺)</b> — tiles of the chosen suit must be
 *       discarded first and cannot remain in a winning hand. A naturally unfinished
 *       suit at exhaustive draw is handled as not-ready, not as a fixed hua-zhu fine.</li>
 * </ul>
 */
final class SichuanBotStrategy implements BotStrategy {
    private static final int MAX_SICHUAN_TURN_RETRY_ATTEMPTS = 8;
    private static final long PREPARATION_ACTION_DELAY_TICKS = 1L;
    private static final long REACTION_AND_TURN_DELAY_TICKS = 20L;

    @Override
    public void schedule(MahjongTableSession session) {
        if (!session.isStarted()) {
            return;
        }
        PlayerActionSnapshotFactory actionSnapshotFactory = new PlayerActionSnapshotFactory(session);
        for (UUID playerId : session.players()) {
            if (!session.isBot(playerId)) {
                continue;
            }
            PlayerActionSnapshot snapshot = actionSnapshotFactory.capture(playerId);
            if (snapshot.phase() == PlayerActionPhase.SICHUAN_DING_QUE) {
                final PluginTask[] holder = new PluginTask[1];
                holder[0] = session.plugin().scheduler().runRegionDelayed(
                    session.center(),
                    () -> this.safeHandleSichuanDingQue(session, playerId, holder[0]),
                    PREPARATION_ACTION_DELAY_TICKS
                );
                session.setBotTask(holder[0]);
                return;
            }
            if (snapshot.phase() == PlayerActionPhase.SICHUAN_EXCHANGE) {
                final PluginTask[] holder = new PluginTask[1];
                holder[0] = session.plugin().scheduler().runRegionDelayed(
                    session.center(),
                    () -> this.safeHandleSichuanExchange(session, playerId, holder[0]),
                    PREPARATION_ACTION_DELAY_TICKS
                );
                session.setBotTask(holder[0]);
                return;
            }
        }
        // Check for pending reactions first (pon, kan, ron — no chii in Sichuan).
        for (UUID playerId : session.players()) {
            if (!session.isBot(playerId) || session.availableReactions(playerId) == null) {
                continue;
            }
            final PluginTask[] holder = new PluginTask[1];
            holder[0] = session.plugin().scheduler().runRegionDelayed(
                session.center(),
                () -> this.safeHandleSichuanReaction(session, playerId, holder[0]),
                REACTION_AND_TURN_DELAY_TICKS
            );
            session.setBotTask(holder[0]);
            return;
        }
        // No pending reactions — check if the current player is a bot.
        UUID current = session.playerAt(session.currentSeat());
        if (current != null && session.isBot(current) && session.isCurrentPlayer(current)) {
            final PluginTask[] holder = new PluginTask[1];
            holder[0] = session.plugin().scheduler().runRegionDelayed(
                session.center(),
                () -> this.safeHandleSichuanTurn(session, current, 0, holder[0]),
                REACTION_AND_TURN_DELAY_TICKS
            );
            session.setBotTask(holder[0]);
        }
    }

    private void safeHandleSichuanReaction(MahjongTableSession session, UUID playerId, PluginTask currentTask) {
        try {
            this.handleSichuanReaction(session, playerId);
        } catch (RuntimeException exception) {
            session.plugin().getLogger().warning("Sichuan bot reaction failed for table " + session.id() + ", player " + playerId + ": " + exception.getMessage());
        } finally {
            session.clearBotTaskIfSame(currentTask);
        }
    }

    private void safeHandleSichuanTurn(MahjongTableSession session, UUID playerId, int retryAttempts, PluginTask currentTask) {
        try {
            this.handleSichuanTurn(session, playerId, retryAttempts);
        } catch (RuntimeException exception) {
            session.plugin().getLogger().warning("Sichuan bot turn failed for table " + session.id() + ", player " + playerId + ": " + exception.getMessage());
        } finally {
            session.clearBotTaskIfSame(currentTask);
        }
    }

    private void safeHandleSichuanExchange(MahjongTableSession session, UUID playerId, PluginTask currentTask) {
        try {
            this.handleSichuanExchange(session, playerId);
        } catch (RuntimeException exception) {
            session.plugin().getLogger().warning("Sichuan bot exchange failed for table " + session.id() + ", player " + playerId + ": " + exception.getMessage());
        } finally {
            session.clearBotTaskIfSame(currentTask);
        }
    }

    private void safeHandleSichuanDingQue(MahjongTableSession session, UUID playerId, PluginTask currentTask) {
        try {
            this.handleSichuanDingQue(session, playerId);
        } catch (RuntimeException exception) {
            session.plugin().getLogger().warning("Sichuan bot dingque failed for table " + session.id() + ", player " + playerId + ": " + exception.getMessage());
        } finally {
            session.clearBotTaskIfSame(currentTask);
        }
    }

    private void handleSichuanReaction(MahjongTableSession session, UUID playerId) {
        if (session.availableReactions(playerId) == null) {
            return;
        }
        // The GB controller's suggestedBotReaction already handles Sichuan correctly:
        // - Always ron if possible (blood battle means ron is always good)
        // - Evaluate pon/kan vs skip based on ting score
        // - No chii pairs in Sichuan mode
        session.react(playerId, session.gbSuggestedReaction(playerId));
    }

    private void handleSichuanExchange(MahjongTableSession session, UUID playerId) {
        if (!SichuanSessionAccess.isExchangePhase(session, playerId)) {
            return;
        }
        var hand = session.hand(playerId);
        if (hand.isEmpty()) {
            return;
        }
        Map<Character, java.util.List<Integer>> indicesBySuit = new HashMap<>();
        for (int i = 0; i < hand.size(); i++) {
            MahjongTile normalized = normalize(hand.get(i));
            if (normalized == null || normalized.isFlower()) {
                continue;
            }
            char suit = normalized.name().charAt(0);
            if (suit == 'M' || suit == 'P' || suit == 'S') {
                indicesBySuit.computeIfAbsent(suit, ignored -> new java.util.ArrayList<>()).add(i);
            }
        }
        char targetSuit = 'M';
        int smallest = Integer.MAX_VALUE;
        for (var entry : indicesBySuit.entrySet()) {
            int count = entry.getValue().size();
            if (count >= 3 && count < smallest) {
                smallest = count;
                targetSuit = entry.getKey();
            }
        }
        var candidates = indicesBySuit.getOrDefault(targetSuit, java.util.List.of());
        if (candidates.size() < 3) {
            return;
        }
        SichuanSessionAccess.submitExchangeSelection(session, playerId, candidates.subList(candidates.size() - 3, candidates.size()));
    }

    private void handleSichuanDingQue(MahjongTableSession session, UUID playerId) {
        if (!session.canChooseSichuanMissingSuit(playerId)) {
            return;
        }
        Map<Character, Integer> suitCounts = new HashMap<>();
        suitCounts.put('M', 0);
        suitCounts.put('P', 0);
        suitCounts.put('S', 0);
        for (MahjongTile tile : session.hand(playerId)) {
            MahjongTile normalized = normalize(tile);
            if (normalized == null || normalized.isFlower()) {
                continue;
            }
            char suit = normalized.name().charAt(0);
            if (suitCounts.containsKey(suit)) {
                suitCounts.merge(suit, 1, Integer::sum);
            }
        }
        char targetSuit = 'M';
        int minCount = Integer.MAX_VALUE;
        for (var entry : suitCounts.entrySet()) {
            if (entry.getValue() < minCount) {
                minCount = entry.getValue();
                targetSuit = entry.getKey();
            }
        }
        String suitToken = switch (targetSuit) {
            case 'P' -> "tong";
            case 'S' -> "suo";
            default -> "wan";
        };
        SichuanSessionAccess.chooseMissingSuit(session, playerId, suitToken);
    }

    private void handleSichuanTurn(MahjongTableSession session, UUID playerId, int retryAttempts) {
        if (!session.isStarted() || session.hasPendingReaction() || !session.isCurrentPlayer(playerId)) {
            return;
        }
        // 1. Declare tsumo if possible.
        if (session.gbCanWinByTsumo(playerId) && session.declareTsumo(playerId)) {
            return;
        }
        // 2. Declare kan if suggested (Sichuan has immediate kan scoring).
        String kanTile = session.gbSuggestedKanTile(playerId);
        if (kanTile != null && session.declareKan(playerId, kanTile)) {
            return;
        }
        // 3. Discard a tile — use Sichuan-aware discard selection.
        var hand = session.hand(playerId);
        if (hand.isEmpty()) {
            return;
        }
        int discardIndex = this.chooseSichuanDiscardIndex(session, playerId);
        if (!session.canSelectHandTile(playerId, discardIndex)) {
            discardIndex = findSelectableDiscardIndex(session, playerId, hand.size());
        }
        if (discardIndex < 0) {
            this.scheduleSichuanTurnRetry(session, playerId, retryAttempts, "no-selectable-discard");
            return;
        }
        if (!session.discard(playerId, discardIndex)
            && session.isStarted()
            && !session.hasPendingReaction()
            && session.isCurrentPlayer(playerId)) {
            this.scheduleSichuanTurnRetry(session, playerId, retryAttempts, "discard-failed");
        }
    }

    /**
     * Choose the best tile to discard for a Sichuan Mahjong bot.
     *
     * <p>The strategy is:
     * <ol>
     *   <li>If the hand already satisfies "missing one suit", use the GB engine's
     *       suggested discard (which evaluates ting and is Sichuan-aware).</li>
     *   <li>If the hand still has all three suits, prioritise discarding from the
     *       suit with the fewest tiles to complete the declared missing-suit
     *       obligation and preserve a chance to become ready.</li>
     * </ol>
     */
    private int chooseSichuanDiscardIndex(MahjongTableSession session, UUID playerId) {
        var hand = session.hand(playerId);
        if (hand.isEmpty()) {
            return -1;
        }
        // Check if the hand is already missing one suit.
        Set<Character> suits = suitSet(hand);
        if (suits.size() <= 2) {
            // Already missing one suit — delegate to the engine's suggestion.
            return session.gbSuggestedDiscardIndex(playerId);
        }
        // Still has all three suits — find the suit with the fewest tiles and
        // discard from it to commit to a missing suit as early as possible.
        Map<Character, Integer> suitCounts = new HashMap<>();
        for (MahjongTile tile : hand) {
            MahjongTile normalized = normalize(tile);
            if (normalized == null || normalized.isFlower()) {
                continue;
            }
            char suit = normalized.name().charAt(0);
            if (suit == 'M' || suit == 'P' || suit == 'S') {
                suitCounts.merge(suit, 1, Integer::sum);
            }
        }
        if (suitCounts.size() < 3) {
            // Fewer than 3 suits present (some are honor/flower tiles) — use engine suggestion.
            return session.gbSuggestedDiscardIndex(playerId);
        }
        // Find the suit with the minimum count.
        char minSuit = 'M';
        int minCount = Integer.MAX_VALUE;
        for (var entry : suitCounts.entrySet()) {
            if (entry.getValue() < minCount) {
                minCount = entry.getValue();
                minSuit = entry.getKey();
            }
        }
        // Find the last tile in hand belonging to the minimum-count suit.
        // Prefer discarding from the end of the hand (least useful).
        for (int i = hand.size() - 1; i >= 0; i--) {
            MahjongTile normalized = normalize(hand.get(i));
            if (normalized != null && normalized.name().charAt(0) == minSuit) {
                return i;
            }
        }
        // Fallback to engine suggestion.
        return session.gbSuggestedDiscardIndex(playerId);
    }

    private void scheduleSichuanTurnRetry(MahjongTableSession session, UUID playerId, int retryAttempts, String reason) {
        int nextRetry = retryAttempts + 1;
        if (nextRetry > MAX_SICHUAN_TURN_RETRY_ATTEMPTS) {
            session.plugin().getLogger().warning(
                "Sichuan bot turn retry exhausted for table " + session.id()
                    + ", player " + playerId
                    + ", reason=" + reason
                    + ", maxRetries=" + MAX_SICHUAN_TURN_RETRY_ATTEMPTS
            );
            return;
        }
        final PluginTask[] holder = new PluginTask[1];
        holder[0] = session.plugin().scheduler().runRegionDelayed(
            session.center(),
            () -> this.safeHandleSichuanTurn(session, playerId, nextRetry, holder[0]),
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

    private static Set<Character> suitSet(java.util.List<MahjongTile> tiles) {
        Set<Character> suits = new HashSet<>();
        for (MahjongTile tile : tiles) {
            MahjongTile normalized = normalize(tile);
            if (normalized == null || normalized.isFlower()) {
                continue;
            }
            char suit = normalized.name().charAt(0);
            if (suit == 'M' || suit == 'P' || suit == 'S') {
                suits.add(suit);
            }
        }
        return suits;
    }

    private static MahjongTile normalize(MahjongTile tile) {
        if (tile == null) {
            return null;
        }
        return switch (tile) {
            case M5_RED -> MahjongTile.M5;
            case P5_RED -> MahjongTile.P5;
            case S5_RED -> MahjongTile.S5;
            default -> tile;
        };
    }
}
