package top.ellan.mahjong.application;

/** Non-blocking ingress outcome. */
public enum TableActionCode {
    ACCEPTED_MEMORY,
    REJECTED_BY_RULES,
    STALE_TOKEN,
    WRONG_ACTOR,
    TABLE_PAUSED,
    TABLE_BLOCKED,
    TABLE_FINISHED,
    RULE_BUSY,
    MAILBOX_FULL,
    RULE_POOL_SATURATED,
    RULE_PACK_FAILURE,
    TABLE_CLOSED
}
