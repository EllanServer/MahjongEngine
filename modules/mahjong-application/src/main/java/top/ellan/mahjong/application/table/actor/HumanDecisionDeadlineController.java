package top.ellan.mahjong.application.table.actor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.feedback.HumanDecisionWarning;
import top.ellan.mahjong.application.feedback.HumanDecisionWarningPort;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/**
 * Event-driven, one-timer-per-table human fallback policy.
 *
 * <p>Discard turns use the 1.5.0 anti-idle ladder of 60, 30, 15 then 10 seconds after consecutive
 * automatic discards; a manual discard restores 60 seconds. Every other decision gets the 1.5.0
 * budget of a 5-second base plus whatever is left of a 20-second extra pool that is shared by all of
 * one player's non-discard decisions within a hand: answering inside the base costs nothing, while a
 * slow answer burns pool time and leaves later decisions with the bare base. A timeout asks the rule
 * pack's existing asynchronous automation policy for exactly one action; it never scans server
 * players or tables.
 *
 * <p>A second bounded timer fires {@code WARNING_LEAD} before the deadline so the seat is told it is
 * about to be played automatically, restoring the 1.5.0 action-bar countdown. It only publishes to a
 * non-blocking port and touches no controller state.
 */
final class HumanDecisionDeadlineController {
    private static final Duration FIRST_DISCARD = Duration.ofSeconds(60);
    private static final Duration SECOND_DISCARD = Duration.ofSeconds(30);
    private static final Duration THIRD_DISCARD = Duration.ofSeconds(15);
    private static final Duration REPEATED_DISCARD = Duration.ofSeconds(10);
    private static final Duration BASE_DECISION = Duration.ofSeconds(5);
    private static final Duration EXTRA_POOL = Duration.ofSeconds(20);
    private static final Duration WARNING_LEAD = Duration.ofSeconds(5);

    private final TaskScheduler scheduler;
    private final TableActorInbox inbox;
    private final Runnable wakeActor;
    private final HumanDecisionWarningPort warnings;
    private final LongSupplier nanoTime;
    private final Map<PlayerId, Integer> automaticDiscards = new HashMap<>();
    private final Map<PlayerId, Long> remainingExtraNanos = new HashMap<>();
    private final Map<PlayerId, Long> decisionStartedAtNanos = new HashMap<>();
    private Cancellable task = () -> false;
    private Cancellable warningTask = () -> false;
    private Window current;

    HumanDecisionDeadlineController(
            TaskScheduler scheduler, TableActorInbox inbox, Runnable wakeActor) {
        this(scheduler, inbox, wakeActor, HumanDecisionWarningPort.NONE, System::nanoTime);
    }

    HumanDecisionDeadlineController(
            TaskScheduler scheduler,
            TableActorInbox inbox,
            Runnable wakeActor,
            LongSupplier nanoTime) {
        this(scheduler, inbox, wakeActor, HumanDecisionWarningPort.NONE, nanoTime);
    }

    HumanDecisionDeadlineController(
            TaskScheduler scheduler,
            TableActorInbox inbox,
            Runnable wakeActor,
            HumanDecisionWarningPort warnings) {
        this(scheduler, inbox, wakeActor, warnings, System::nanoTime);
    }

    HumanDecisionDeadlineController(
            TaskScheduler scheduler,
            TableActorInbox inbox,
            Runnable wakeActor,
            HumanDecisionWarningPort warnings,
            LongSupplier nanoTime) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.wakeActor = Objects.requireNonNull(wakeActor, "wakeActor");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    Optional<String> install(
            RuleFrame frame,
            long revision,
            Set<PlayerId> automatedPlayers,
            boolean enabled) {
        Objects.requireNonNull(frame, "frame");
        automatedPlayers = Set.copyOf(
                Objects.requireNonNull(automatedPlayers, "automatedPlayers"));
        Window next = window(frame, revision, automatedPlayers);
        if (enabled && next.equals(current)) {
            return Optional.empty();
        }
        pause();
        if (!enabled) {
            return Optional.empty();
        }
        current = next;
        if (next.decisions().isEmpty()) {
            return Optional.empty();
        }
        try {
            task = scheduler.schedule(
                    () -> {
                        inbox.offerHumanDecisionTimeout(
                                next.revision(), next.expiringDecisions());
                        wakeActor.run();
                    },
                    next.delay());
            scheduleWarning(next);
            return Optional.empty();
        } catch (RejectedExecutionException failure) {
            current = null;
            return Optional.of("human-deadline-capacity");
        }
    }

    List<HumanDecisionTimeout> accept(HumanDecisionTimeoutTrigger trigger, long revision) {
        Objects.requireNonNull(trigger, "trigger");
        if (current == null
                || trigger.expectedRevision() != revision
                || current.revision() != revision
                || !current.expiringDecisions().equals(trigger.decisions())) {
            return List.of();
        }
        List<HumanDecisionTimeout> accepted = trigger.decisions();
        current = null;
        task = () -> false;
        for (HumanDecisionTimeout decision : accepted) {
            if (decision.discardTurn()) {
                automaticDiscards.compute(
                        decision.actor(),
                        (ignored, count) -> count == null ? 1 : Math.min(3, count + 1));
                decisionStartedAtNanos.remove(decision.actor());
            } else {
                // The window ran to its full base-plus-pool length, so the pool is spent.
                decisionStartedAtNanos.remove(decision.actor());
                remainingExtraNanos.put(decision.actor(), 0L);
            }
        }
        return accepted;
    }

    void acceptedManual(PlayerId actor, RuleAction action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        if ("discard".equals(action.type())) {
            automaticDiscards.remove(actor);
            decisionStartedAtNanos.remove(actor);
            return;
        }
        consumeExtra(actor, nanoTime.getAsLong());
    }

    void pause() {
        task.cancel();
        task = () -> false;
        warningTask.cancel();
        warningTask = () -> false;
        current = null;
        inbox.clearHumanDecisionTimeouts();
    }

    /**
     * Tells the seats that are about to expire how long they have left. A rejected warning is
     * swallowed: losing the notice must never disturb the decision window itself.
     */
    private void scheduleWarning(Window next) {
        Duration lead = next.delay().minus(WARNING_LEAD);
        if (lead.isZero() || lead.isNegative()) {
            return;
        }
        List<HumanDecisionTimeout> expiring = next.expiringDecisions();
        int remainingSeconds = (int) WARNING_LEAD.toSeconds();
        try {
            warningTask = scheduler.schedule(
                    () -> {
                        for (HumanDecisionTimeout decision : expiring) {
                            warnings.publish(
                                    new HumanDecisionWarning(
                                            decision.actor(),
                                            decision.discardTurn(),
                                            remainingSeconds));
                        }
                    },
                    lead);
        } catch (RejectedExecutionException ignored) {
            warningTask = () -> false;
        }
    }

    void clear() {
        pause();
        automaticDiscards.clear();
        remainingExtraNanos.clear();
        decisionStartedAtNanos.clear();
    }

    /** Charges a player's shared pool for the time they spent beyond the base allowance. */
    private void consumeExtra(PlayerId actor, long now) {
        Long startedAt = decisionStartedAtNanos.remove(actor);
        if (startedAt == null) {
            return;
        }
        long elapsed = Math.max(0L, now - startedAt);
        long beyondBase = Math.max(0L, elapsed - BASE_DECISION.toNanos());
        long remaining = remainingExtra(actor);
        remainingExtraNanos.put(actor, Math.max(0L, remaining - Math.min(beyondBase, remaining)));
    }

    private long remainingExtra(PlayerId actor) {
        return remainingExtraNanos.getOrDefault(actor, EXTRA_POOL.toNanos());
    }

    private Window window(
            RuleFrame frame, long revision, Set<PlayerId> automatedPlayers) {
        long now = nanoTime.getAsLong();
        ArrayList<Decision> decisions = new ArrayList<>(4);
        for (Map.Entry<PlayerId, List<LegalAction>> entry : frame.legalActions().entrySet()) {
            if (entry.getValue().isEmpty() || automatedPlayers.contains(entry.getKey())) {
                continue;
            }
            PlayerId actor = entry.getKey();
            boolean discardTurn = entry.getValue().stream()
                    .anyMatch(action -> "discard".equals(action.action().type()));
            Duration delay;
            if (discardTurn) {
                delay = discardDelay(actor);
            } else {
                // The clock starts when the decision is first offered and survives re-arming.
                decisionStartedAtNanos.putIfAbsent(actor, now);
                delay = BASE_DECISION.plusNanos(remainingExtra(actor));
            }
            decisions.add(new Decision(new HumanDecisionTimeout(actor, discardTurn), delay));
        }
        decisions.sort(java.util.Comparator.comparing(decision -> decision.timeout().actor()));
        chargeDepartedActors(decisions, now);
        if (decisions.isEmpty()) {
            return new Window(revision, List.of(), Duration.ZERO, List.of());
        }
        Duration earliest = decisions.stream()
                .map(Decision::delay)
                .min(Duration::compareTo)
                .orElseThrow();
        List<HumanDecisionTimeout> expiring = decisions.stream()
                .filter(decision -> decision.delay().equals(earliest))
                .map(Decision::timeout)
                .toList();
        return new Window(revision, List.copyOf(decisions), earliest, expiring);
    }

    /** A window someone else resolved still charges the pool for the time already spent. */
    private void chargeDepartedActors(List<Decision> decisions, long now) {
        if (decisionStartedAtNanos.isEmpty()) {
            return;
        }
        Set<PlayerId> stillDeciding = new java.util.HashSet<>(decisions.size());
        for (Decision decision : decisions) {
            if (!decision.timeout().discardTurn()) {
                stillDeciding.add(decision.timeout().actor());
            }
        }
        for (PlayerId departed : List.copyOf(decisionStartedAtNanos.keySet())) {
            if (!stillDeciding.contains(departed)) {
                consumeExtra(departed, now);
            }
        }
    }

    private Duration discardDelay(PlayerId actor) {
        return switch (automaticDiscards.getOrDefault(actor, 0)) {
            case 0 -> FIRST_DISCARD;
            case 1 -> SECOND_DISCARD;
            case 2 -> THIRD_DISCARD;
            default -> REPEATED_DISCARD;
        };
    }

    private record Decision(HumanDecisionTimeout timeout, Duration delay) {
        private Decision {
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(delay, "delay");
        }
    }

    private record Window(
            long revision,
            List<Decision> decisions,
            Duration delay,
            List<HumanDecisionTimeout> expiringDecisions) {
        private Window {
            decisions = List.copyOf(decisions);
            Objects.requireNonNull(delay, "delay");
            expiringDecisions = List.copyOf(expiringDecisions);
        }
    }
}
