package top.ellan.mahjong.application.concurrent;

import java.util.ArrayDeque;

/** Mutable queue state guarded by its owning {@link FairRuleExecutor}. */
final class RulePackWorkQueue {
    private final ArrayDeque<FairRuleExecutor.ScheduledOperation<?>> operations =
            new ArrayDeque<>();
    private int activeWorkers;
    private int consecutiveFailures;
    private boolean ready;
    private boolean circuitOpen;

    ArrayDeque<FairRuleExecutor.ScheduledOperation<?>> operations() {
        return operations;
    }

    int activeWorkers() {
        return activeWorkers;
    }

    void activeWorkers(int value) {
        activeWorkers = value;
    }

    int consecutiveFailures() {
        return consecutiveFailures;
    }

    void consecutiveFailures(int value) {
        consecutiveFailures = value;
    }

    boolean ready() {
        return ready;
    }

    void ready(boolean value) {
        ready = value;
    }

    boolean circuitOpen() {
        return circuitOpen;
    }

    void circuitOpen(boolean value) {
        circuitOpen = value;
    }

    boolean idle() {
        return operations.isEmpty() && activeWorkers == 0;
    }
}
