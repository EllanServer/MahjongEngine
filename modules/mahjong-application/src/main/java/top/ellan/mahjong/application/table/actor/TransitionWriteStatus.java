package top.ellan.mahjong.application.table.actor;

/** Outcome of offering one accepted rule transition to its per-table outbox. */
enum TransitionWriteStatus {
    ACCEPTED,
    CAPACITY_REJECTED,
    FAILED
}
