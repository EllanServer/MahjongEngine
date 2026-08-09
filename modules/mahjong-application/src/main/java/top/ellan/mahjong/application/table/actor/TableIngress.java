package top.ellan.mahjong.application.table.actor;

/** Arrival-ordered external input or revision-bound scheduled trigger. */
sealed interface TableIngress
        permits TableActionEnvelope, ScheduledActionTrigger, AutomationControlEnvelope {
    long ingressOrder();
}
