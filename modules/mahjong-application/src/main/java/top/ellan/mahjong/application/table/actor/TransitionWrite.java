package top.ellan.mahjong.application.table.actor;

import top.ellan.mahjong.application.persistence.OutboxHealth;

/** Persistence offer result returned synchronously to the single-writer actor. */
record TransitionWrite(
        TransitionWriteStatus status,
        OutboxHealth health,
        long resultingSequence,
        String failureCode) {
    boolean accepted() {
        return status == TransitionWriteStatus.ACCEPTED;
    }
}
