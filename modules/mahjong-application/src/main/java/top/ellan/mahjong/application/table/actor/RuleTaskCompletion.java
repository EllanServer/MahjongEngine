package top.ellan.mahjong.application.table.actor;

import java.util.Optional;

/** Single-slot continuation from the fair rule pool back to the owning actor. */
record RuleTaskCompletion(
        long expectedRevision,
        Optional<TableActionEnvelope> envelope,
        RuleComputation computed,
        Throwable failure) {}
