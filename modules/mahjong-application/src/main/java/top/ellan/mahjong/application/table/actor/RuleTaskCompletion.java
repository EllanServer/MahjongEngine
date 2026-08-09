package top.ellan.mahjong.application.table.actor;

import java.util.Optional;

/** Single-slot continuation from the fair rule pool back to the owning actor. */
record RuleTaskCompletion(
        long expectedRevision,
        Optional<TableActionEnvelope> envelope,
        Optional<AuthorityActionEnvelope> authorityEnvelope,
        Optional<ScheduledActionTrigger> scheduledTrigger,
        RuleComputation computed,
        Throwable failure) {
    RuleTaskCompletion {
        int sources = (envelope.isPresent() ? 1 : 0)
                + (authorityEnvelope.isPresent() ? 1 : 0)
                + (scheduledTrigger.isPresent() ? 1 : 0);
        if (sources > 1) {
            throw new IllegalArgumentException("A rule completion has more than one source");
        }
    }
}
