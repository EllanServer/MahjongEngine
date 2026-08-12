package top.ellan.mahjong.application.table.actor;

import java.util.List;

/** Single per-table timer callback carrying at most the four actors in this decision window. */
record HumanDecisionTimeoutTrigger(
        long ingressOrder, long expectedRevision, List<HumanDecisionTimeout> decisions)
        implements TableIngress {
    HumanDecisionTimeoutTrigger {
        decisions = List.copyOf(decisions);
        if (expectedRevision < 0 || decisions.isEmpty() || decisions.size() > 4) {
            throw new IllegalArgumentException("Invalid human decision timeout trigger");
        }
    }
}
