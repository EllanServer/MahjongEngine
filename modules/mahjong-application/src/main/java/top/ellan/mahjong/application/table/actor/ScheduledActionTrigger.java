package top.ellan.mahjong.application.table.actor;

import top.ellan.mahjong.spi.ScheduledRuleAction;

/** Timer callback returned to the actor without touching rule state on the timer thread. */
record ScheduledActionTrigger(
        long ingressOrder, long expectedRevision, ScheduledRuleAction scheduledAction)
        implements TableIngress {}
