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
import top.ellan.mahjong.application.concurrent.Cancellable;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/**
 * Event-driven, one-timer-per-table human fallback policy.
 *
 * <p>Discard turns preserve the v1.5 60/30/15/10-second anti-idle ladder. Other decisions use the
 * former default 5+20-second budget. A timeout asks the rule pack's existing asynchronous
 * automation policy for exactly one action; it never scans server players or tables.
 */
final class HumanDecisionDeadlineController {
    private static final Duration FIRST_DISCARD = Duration.ofSeconds(60);
    private static final Duration SECOND_DISCARD = Duration.ofSeconds(30);
    private static final Duration THIRD_DISCARD = Duration.ofSeconds(15);
    private static final Duration REPEATED_DISCARD = Duration.ofSeconds(10);
    private static final Duration OTHER_DECISION = Duration.ofSeconds(25);

    private final TaskScheduler scheduler;
    private final TableActorInbox inbox;
    private final Runnable wakeActor;
    private final Map<PlayerId, Integer> automaticDiscards = new HashMap<>();
    private Cancellable task = () -> false;
    private Window current;

    HumanDecisionDeadlineController(
            TaskScheduler scheduler, TableActorInbox inbox, Runnable wakeActor) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.wakeActor = Objects.requireNonNull(wakeActor, "wakeActor");
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
        current = next;
        if (!enabled || next.decisions().isEmpty()) {
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
            }
        }
        return accepted;
    }

    void acceptedManual(PlayerId actor, RuleAction action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        if ("discard".equals(action.type())) {
            automaticDiscards.remove(actor);
        }
    }

    void pause() {
        task.cancel();
        task = () -> false;
        current = null;
        inbox.clearHumanDecisionTimeouts();
    }

    void clear() {
        pause();
        automaticDiscards.clear();
    }

    private Window window(
            RuleFrame frame, long revision, Set<PlayerId> automatedPlayers) {
        ArrayList<Decision> decisions = new ArrayList<>(4);
        for (Map.Entry<PlayerId, List<LegalAction>> entry : frame.legalActions().entrySet()) {
            if (entry.getValue().isEmpty() || automatedPlayers.contains(entry.getKey())) {
                continue;
            }
            boolean discardTurn = entry.getValue().stream()
                    .anyMatch(action -> "discard".equals(action.action().type()));
            decisions.add(
                    new Decision(
                            new HumanDecisionTimeout(entry.getKey(), discardTurn),
                            discardTurn ? discardDelay(entry.getKey()) : OTHER_DECISION));
        }
        decisions.sort(java.util.Comparator.comparing(decision -> decision.timeout().actor()));
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
