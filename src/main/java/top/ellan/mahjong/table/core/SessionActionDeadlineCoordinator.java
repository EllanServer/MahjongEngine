package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.ReactionResponses;
import top.ellan.mahjong.riichi.model.MahjongRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tick-driven fallback for human action windows.
 *
 * <p>Turn discards use an anti-idle ladder of 60, 30, 15, then 10 seconds after consecutive
 * automatic discards; a manual discard restores 60 seconds. Other decisions receive the rule's base
 * thinking time, with extra time kept as a per-player pool shared by the whole hand. The normal UI
 * and bot schedulers remain the primary action path; this coordinator closes expired windows and
 * acts on the next table tick for an unattended (disconnected / leaving) seat.
 */
final class SessionActionDeadlineCoordinator {
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long DEFAULT_BASE_SECONDS = 5L;
    private static final long DEFAULT_EXTRA_SECONDS = 20L;
    private static final long FIRST_TURN_SECONDS = 60L;
    private static final long SECOND_TURN_AFTER_AUTO_DISCARD_SECONDS = 30L;
    private static final long THIRD_TURN_AFTER_AUTO_DISCARD_SECONDS = 15L;
    private static final long REPEATED_TURN_AFTER_AUTO_DISCARD_SECONDS = 10L;
    private static final char[] SICHUAN_SUITS = {'M', 'P', 'S'};

    private final MahjongTableSession session;
    private final LongSupplier currentTimeMillis;
    private final Map<UUID, Long> remainingExtraMillis = new HashMap<>();
    private final Map<UUID, ActorDeadline> actorDeadlines = new HashMap<>();
    private final Map<UUID, SuspendedDeadline> suspendedDeadlines = new HashMap<>();
    private final Set<UUID> suspendedActors = new HashSet<>();
    private final Map<UUID, Integer> consecutiveAutomaticDiscards = new HashMap<>();
    private final Set<UUID> automaticDiscardActors = new HashSet<>();
    private String armedFingerprint;

    SessionActionDeadlineCoordinator(MahjongTableSession session) {
        this(session, () -> System.nanoTime() / 1_000_000L);
    }

    SessionActionDeadlineCoordinator(MahjongTableSession session, LongSupplier currentTimeMillis) {
        this.session = session;
        this.currentTimeMillis = currentTimeMillis;
    }

    synchronized void beginRound() {
        // An overhead river view may legitimately span the short gap between hands.
        // Reset hand-scoped clocks and idle history without dropping the explicit
        // suspension; the first action window of the new hand must remain frozen
        // until the player returns with Shift.
        this.resetRoundState();
        long extraMillis = this.thinkingBudget().extraMillis();
        for (UUID playerId : this.session.players()) {
            this.remainingExtraMillis.put(playerId, extraMillis);
        }
        ActionWindow initialWindow = this.captureWindow();
        if (initialWindow != null) {
            this.armWindow(initialWindow, this.currentTimeMillis.getAsLong());
        }
    }

    synchronized void tick() {
        ActionWindow window = this.captureWindow();
        if (window == null) {
            this.reset();
            return;
        }

        long now = this.currentTimeMillis.getAsLong();
        if (!window.fingerprint().equals(this.armedFingerprint)) {
            this.armWindow(window, now);
        } else {
            this.ensureActorDeadlines(window, now);
        }

        for (UUID playerId : List.copyOf(window.actors())) {
            ActorDeadline deadline = this.actorDeadlines.get(playerId);
            if (deadline == null) {
                continue;
            }
            if (!this.session.isPlayerUnattended(playerId) && now < deadline.deadlineMillis()) {
                continue;
            }
            if (!this.performFallback(window.phase(), playerId)) {
                this.deferFailedFallback(window.phase(), playerId, now);
            }
            ActionWindow currentWindow = this.captureWindow();
            if (currentWindow == null || !currentWindow.fingerprint().equals(window.fingerprint())) {
                break;
            }
        }

        ActionWindow afterActions = this.captureWindow();
        if (afterActions == null) {
            this.reset();
            return;
        }
        if (!afterActions.fingerprint().equals(window.fingerprint())) {
            if (!afterActions.fingerprint().equals(this.armedFingerprint)) {
                this.armWindow(afterActions, now);
            }
        } else {
            this.ensureActorDeadlines(afterActions, now);
        }
    }

    /** Records an accepted action at its actual completion time so the shared extra pool is exact. */
    synchronized void recordAction(UUID playerId) {
        long now = this.currentTimeMillis.getAsLong();
        ActorDeadline deadline = this.actorDeadlines.remove(playerId);
        if (deadline != null) {
            this.consumeExtra(playerId, deadline, now);
        }
        SuspendedDeadline suspended = this.suspendedDeadlines.remove(playerId);
        if (suspended != null) {
            this.consumeExtra(playerId, suspended.deadline(), suspended.suspendedAtMillis());
        }
        ActionWindow currentWindow = this.captureWindow();
        if (currentWindow == null) {
            this.reset();
        } else if (!currentWindow.fingerprint().equals(this.armedFingerprint)) {
            this.armWindow(currentWindow, now);
        } else {
            this.ensureActorDeadlines(currentWindow, now);
        }
    }

    /** Records a discard and restores the player's next turn to 60 seconds when it was manual. */
    synchronized void recordDiscard(UUID playerId) {
        if (!this.automaticDiscardActors.contains(playerId)) {
            this.consecutiveAutomaticDiscards.remove(playerId);
        }
        this.recordAction(playerId);
    }

    synchronized long secondsRemaining(UUID playerId) {
        if (playerId == null) {
            return 0L;
        }
        ActorDeadline deadline = this.actorDeadlines.get(playerId);
        if (deadline != null) {
            return secondsFromMillis(deadline.deadlineMillis() - this.currentTimeMillis.getAsLong());
        }
        SuspendedDeadline suspended = this.suspendedDeadlines.get(playerId);
        return suspended == null
            ? 0L
            : secondsFromMillis(suspended.deadline().deadlineMillis() - suspended.suspendedAtMillis());
    }

    /** Freezes this player's current and subsequent action windows until explicitly resumed. */
    synchronized void suspend(UUID playerId) {
        if (playerId == null || !this.suspendedActors.add(playerId)) {
            return;
        }
        ActionWindow window = this.captureWindow();
        if (window == null) {
            return;
        }
        long now = this.currentTimeMillis.getAsLong();
        if (!window.fingerprint().equals(this.armedFingerprint)) {
            this.armWindow(window, now);
        } else {
            this.ensureActorDeadlines(window, now);
        }
    }

    /** Restores the frozen action with exactly the time that remained on entry. */
    synchronized void resume(UUID playerId) {
        if (playerId == null || !this.suspendedActors.remove(playerId)) {
            return;
        }
        long now = this.currentTimeMillis.getAsLong();
        SuspendedDeadline suspended = this.suspendedDeadlines.remove(playerId);
        ActionWindow window = this.captureWindow();
        if (window == null) {
            return;
        }
        if (!window.fingerprint().equals(this.armedFingerprint)) {
            if (suspended != null) {
                this.consumeExtra(playerId, suspended.deadline(), suspended.suspendedAtMillis());
            }
            this.armWindow(window, now);
            return;
        }
        if (suspended != null
            && suspended.fingerprint().equals(window.fingerprint())
            && window.actors().contains(playerId)) {
            ActorDeadline frozen = suspended.deadline();
            long elapsedBeforeSuspension = Math.max(0L, suspended.suspendedAtMillis() - frozen.startedAtMillis());
            long remainingMillis = Math.max(0L, frozen.deadlineMillis() - suspended.suspendedAtMillis());
            this.actorDeadlines.put(
                playerId,
                new ActorDeadline(
                    Math.max(0L, now - elapsedBeforeSuspension),
                    frozen.baseMillis(),
                    frozen.extraAtStartMillis(),
                    saturatedAdd(now, remainingMillis)
                )
            );
        } else {
            if (suspended != null) {
                this.consumeExtra(playerId, suspended.deadline(), suspended.suspendedAtMillis());
            }
            this.ensureActorDeadlines(window, now);
        }
    }

    /** Drops a frozen action without restoring it, for disconnect/removal/table teardown. */
    synchronized void discardSuspension(UUID playerId) {
        if (playerId == null) {
            return;
        }
        this.suspendedActors.remove(playerId);
        this.suspendedDeadlines.remove(playerId);
    }

    synchronized void reset() {
        this.armedFingerprint = null;
        this.actorDeadlines.clear();
        this.suspendedDeadlines.clear();
    }

    synchronized void clear() {
        this.resetRoundState();
        this.suspendedActors.clear();
    }

    private void resetRoundState() {
        this.reset();
        this.remainingExtraMillis.clear();
        this.consecutiveAutomaticDiscards.clear();
        this.automaticDiscardActors.clear();
    }

    private void armWindow(ActionWindow window, long now) {
        // Production actions report their exact completion through recordAction. Settling any
        // leftovers here also makes the coordinator robust to a controller transition initiated by
        // an integration that bypassed the normal session action wrappers.
        for (Map.Entry<UUID, ActorDeadline> entry : List.copyOf(this.actorDeadlines.entrySet())) {
            this.consumeExtra(entry.getKey(), entry.getValue(), now);
        }
        for (Map.Entry<UUID, SuspendedDeadline> entry : List.copyOf(this.suspendedDeadlines.entrySet())) {
            SuspendedDeadline suspended = entry.getValue();
            this.consumeExtra(entry.getKey(), suspended.deadline(), suspended.suspendedAtMillis());
        }
        this.actorDeadlines.clear();
        this.suspendedDeadlines.clear();
        this.armedFingerprint = window.fingerprint();
        this.ensureActorDeadlines(window, now);
    }

    private void ensureActorDeadlines(ActionWindow window, long now) {
        for (Map.Entry<UUID, ActorDeadline> entry : List.copyOf(this.actorDeadlines.entrySet())) {
            if (!window.actors().contains(entry.getKey())) {
                this.actorDeadlines.remove(entry.getKey());
                this.consumeExtra(entry.getKey(), entry.getValue(), now);
            }
        }
        for (UUID playerId : window.actors()) {
            ThinkingBudget budget = this.actionBudget(window.phase(), playerId);
            long extraMillis = window.phase() == ActionPhase.TURN
                ? 0L
                : this.remainingExtraMillis.computeIfAbsent(playerId, ignored -> budget.extraMillis());
            if (this.suspendedActors.contains(playerId)) {
                ActorDeadline active = this.actorDeadlines.remove(playerId);
                this.suspendedDeadlines.compute(
                    playerId,
                    (ignored, current) -> current != null && current.fingerprint().equals(window.fingerprint())
                        ? current
                        : new SuspendedDeadline(
                            window.fingerprint(),
                            active == null
                                ? new ActorDeadline(
                                    now,
                                    budget.baseMillis(),
                                    extraMillis,
                                    saturatedAdd(now, saturatedAdd(budget.baseMillis(), extraMillis))
                                )
                                : active,
                            now
                        )
                );
                continue;
            }
            this.actorDeadlines.computeIfAbsent(
                playerId,
                ignored -> new ActorDeadline(
                    now,
                    budget.baseMillis(),
                    extraMillis,
                    saturatedAdd(now, saturatedAdd(budget.baseMillis(), extraMillis))
                )
            );
        }
    }

    private void consumeExtra(UUID playerId, ActorDeadline deadline, long completedAtMillis) {
        long elapsed = Math.max(0L, completedAtMillis - deadline.startedAtMillis());
        long extraUsed = Math.min(deadline.extraAtStartMillis(), Math.max(0L, elapsed - deadline.baseMillis()));
        long currentRemaining = this.remainingExtraMillis.getOrDefault(playerId, deadline.extraAtStartMillis());
        this.remainingExtraMillis.put(playerId, Math.max(0L, currentRemaining - extraUsed));
    }

    private void deferFailedFallback(ActionPhase phase, UUID playerId, long now) {
        ActorDeadline previous = this.actorDeadlines.remove(playerId);
        if (previous != null) {
            this.consumeExtra(playerId, previous, now);
        }
        ThinkingBudget budget = this.actionBudget(phase, playerId);
        long extraMillis = phase == ActionPhase.TURN
            ? 0L
            : this.remainingExtraMillis.getOrDefault(playerId, budget.extraMillis());
        this.actorDeadlines.put(
            playerId,
            new ActorDeadline(
                now,
                budget.baseMillis(),
                extraMillis,
                saturatedAdd(now, saturatedAdd(budget.baseMillis(), extraMillis))
            )
        );
    }

    private ThinkingBudget thinkingBudget() {
        MahjongRule rule = this.session.configuredRuleSnapshot();
        MahjongRule.ThinkingTime thinkingTime = rule == null ? null : rule.getThinkingTime();
        long baseSeconds = thinkingTime == null ? DEFAULT_BASE_SECONDS : Math.max(1L, thinkingTime.getBase());
        long extraSeconds = thinkingTime == null ? DEFAULT_EXTRA_SECONDS : Math.max(0L, thinkingTime.getExtra());
        return new ThinkingBudget(baseSeconds * MILLIS_PER_SECOND, extraSeconds * MILLIS_PER_SECOND);
    }

    private ThinkingBudget actionBudget(ActionPhase phase, UUID playerId) {
        if (phase != ActionPhase.TURN) {
            return this.thinkingBudget();
        }
        int automaticDiscards = this.consecutiveAutomaticDiscards.getOrDefault(playerId, 0);
        long seconds = switch (automaticDiscards) {
            case 0 -> FIRST_TURN_SECONDS;
            case 1 -> SECOND_TURN_AFTER_AUTO_DISCARD_SECONDS;
            case 2 -> THIRD_TURN_AFTER_AUTO_DISCARD_SECONDS;
            default -> REPEATED_TURN_AFTER_AUTO_DISCARD_SECONDS;
        };
        return new ThinkingBudget(seconds * MILLIS_PER_SECOND, 0L);
    }

    private ActionWindow captureWindow() {
        if (!this.session.isStarted() || this.session.isRoundStartInProgress()) {
            return null;
        }
        if (this.session.hasPendingReaction()) {
            return new ActionWindow(
                ActionPhase.REACTION,
                "reaction:"
                    + this.session.roundIndex()
                    + ':'
                    + this.session.currentSeat()
                    + ':'
                    + this.session.remainingWallCount()
                    + ':'
                    + this.session.pendingReactionTileKey()
                    + ':'
                    + this.session.lastPublicDiscardPlayerIdValue(),
                this.pendingReactionPlayers()
            );
        }
        List<UUID> exchangePlayers = this.pendingSichuanExchangePlayers();
        if (!exchangePlayers.isEmpty()) {
            return new ActionWindow(
                ActionPhase.SICHUAN_EXCHANGE,
                "sichuan-exchange:" + this.session.roundIndex() + ':' + this.session.remainingWallCount(),
                exchangePlayers
            );
        }
        List<UUID> dingQuePlayers = this.pendingSichuanDingQuePlayers();
        if (!dingQuePlayers.isEmpty()) {
            return new ActionWindow(
                ActionPhase.SICHUAN_DING_QUE,
                "sichuan-ding-que:" + this.session.roundIndex() + ':' + this.session.remainingWallCount(),
                dingQuePlayers
            );
        }
        if (this.session.currentSeat() == null) {
            return null;
        }
        UUID currentPlayer = this.session.playerAt(this.session.currentSeat());
        if (currentPlayer == null) {
            return null;
        }
        return new ActionWindow(
            ActionPhase.TURN,
            "turn:"
                + this.session.roundIndex()
                + ':'
                + currentPlayer
                + ':'
                + this.session.remainingWallCount()
                + ':'
                + this.session.hand(currentPlayer).size()
                + ':'
                + this.session.discards(currentPlayer).size()
                + ':'
                + this.session.kanCount(),
            List.of(currentPlayer)
        );
    }

    private List<UUID> pendingReactionPlayers() {
        List<UUID> pending = new ArrayList<>();
        for (UUID playerId : this.session.players()) {
            if (this.session.isReactionPending(playerId)) {
                pending.add(playerId);
            }
        }
        return List.copyOf(pending);
    }

    private List<UUID> pendingSichuanExchangePlayers() {
        if (this.session.currentVariant() != MahjongVariant.SICHUAN) {
            return List.of();
        }
        List<UUID> pending = new ArrayList<>();
        for (UUID playerId : this.session.players()) {
            if (this.session.isSichuanExchangePhase(playerId)) {
                pending.add(playerId);
            }
        }
        return List.copyOf(pending);
    }

    private List<UUID> pendingSichuanDingQuePlayers() {
        if (this.session.currentVariant() != MahjongVariant.SICHUAN) {
            return List.of();
        }
        List<UUID> pending = new ArrayList<>();
        for (UUID playerId : this.session.players()) {
            if (this.session.canChooseSichuanMissingSuit(playerId)) {
                pending.add(playerId);
            }
        }
        return List.copyOf(pending);
    }

    private boolean performFallback(ActionPhase phase, UUID playerId) {
        return switch (phase) {
            case REACTION -> this.skipReaction(playerId);
            case TURN -> this.discardSafely(playerId);
            case SICHUAN_EXCHANGE -> !this.session.isSichuanExchangePhase(playerId) || this.submitDefaultSichuanExchange(playerId);
            case SICHUAN_DING_QUE -> !this.session.canChooseSichuanMissingSuit(playerId) || this.chooseDefaultSichuanMissingSuit(playerId);
        };
    }

    private boolean skipReaction(UUID playerId) {
        if (!this.session.isReactionPending(playerId)) {
            return true;
        }
        ReactionOptions options = this.session.availableReactions(playerId);
        if (options == null) {
            return false;
        }
        return this.session.react(playerId, ReactionResponses.SKIP);
    }

    private boolean discardSafely(UUID playerId) {
        int previousAutomaticDiscards = this.consecutiveAutomaticDiscards.getOrDefault(playerId, 0);
        this.consecutiveAutomaticDiscards.put(playerId, Math.min(3, previousAutomaticDiscards + 1));
        this.automaticDiscardActors.add(playerId);
        boolean discarded = false;
        try {
            List<MahjongTile> hand = this.session.hand(playerId);
            for (int tileIndex = hand.size() - 1; tileIndex >= 0; tileIndex--) {
                // Riichi limits a declared-riichi player to the drawn tile. Sichuan similarly limits a
                // player with ding-que tiles to that suit, so this scan gives tsumo-giri / missing-suit
                // priority without declaring a win or kan on the player's behalf.
                if (this.session.canSelectHandTile(playerId, tileIndex) && this.session.discard(playerId, tileIndex)) {
                    discarded = true;
                    return true;
                }
            }
            return false;
        } finally {
            this.automaticDiscardActors.remove(playerId);
            if (!discarded) {
                if (previousAutomaticDiscards == 0) {
                    this.consecutiveAutomaticDiscards.remove(playerId);
                } else {
                    this.consecutiveAutomaticDiscards.put(playerId, previousAutomaticDiscards);
                }
            }
        }
    }

    private boolean submitDefaultSichuanExchange(UUID playerId) {
        List<MahjongTile> hand = this.session.hand(playerId);
        Map<Character, List<Integer>> indicesBySuit = new LinkedHashMap<>();
        for (char suit : SICHUAN_SUITS) {
            indicesBySuit.put(suit, new ArrayList<>());
        }
        for (int index = 0; index < hand.size(); index++) {
            char suit = suitOf(hand.get(index));
            List<Integer> indices = indicesBySuit.get(suit);
            if (indices != null) {
                indices.add(index);
            }
        }
        List<Integer> selected = null;
        int selectedSuitCount = Integer.MAX_VALUE;
        for (char suit : SICHUAN_SUITS) {
            List<Integer> candidates = indicesBySuit.get(suit);
            if (candidates.size() >= 3 && candidates.size() < selectedSuitCount) {
                selected = candidates.subList(0, 3);
                selectedSuitCount = candidates.size();
            }
        }
        return selected != null && this.session.submitSichuanExchangeSelection(playerId, List.copyOf(selected));
    }

    private boolean chooseDefaultSichuanMissingSuit(UUID playerId) {
        int[] counts = new int[SICHUAN_SUITS.length];
        for (MahjongTile tile : this.session.hand(playerId)) {
            char suit = suitOf(tile);
            for (int index = 0; index < SICHUAN_SUITS.length; index++) {
                if (SICHUAN_SUITS[index] == suit) {
                    counts[index]++;
                    break;
                }
            }
        }
        int selected = 0;
        for (int index = 1; index < counts.length; index++) {
            if (counts[index] < counts[selected]) {
                selected = index;
            }
        }
        String token = switch (SICHUAN_SUITS[selected]) {
            case 'P' -> "tong";
            case 'S' -> "suo";
            default -> "wan";
        };
        return this.session.chooseSichuanMissingSuit(playerId, token);
    }

    private static char suitOf(MahjongTile tile) {
        if (tile == null || tile.isFlower()) {
            return '\0';
        }
        String name = tile.name();
        return name.isEmpty() ? '\0' : name.charAt(0);
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long secondsFromMillis(long remainingMillis) {
        return remainingMillis <= 0L ? 0L : ((remainingMillis - 1L) / MILLIS_PER_SECOND) + 1L;
    }

    private enum ActionPhase {
        REACTION,
        TURN,
        SICHUAN_EXCHANGE,
        SICHUAN_DING_QUE
    }

    private record ThinkingBudget(long baseMillis, long extraMillis) {
    }

    private record ActorDeadline(
        long startedAtMillis,
        long baseMillis,
        long extraAtStartMillis,
        long deadlineMillis
    ) {
    }

    private record SuspendedDeadline(
        String fingerprint,
        ActorDeadline deadline,
        long suspendedAtMillis
    ) {
    }

    private record ActionWindow(ActionPhase phase, String fingerprint, List<UUID> actors) {
    }
}
