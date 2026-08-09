package top.ellan.mahjong.domain.table;

/** Core-owned lifecycle; rule-specific phase remains inside RuleState. */
public enum TableLifecycle {
    LOBBY,
    STARTING,
    ACTIVE,
    PAUSED_PERSISTENCE,
    BLOCKED_RULE_PACK,
    NEEDS_ADMIN_REVIEW,
    FINISHED,
    CLOSED;

    public boolean acceptsRuleActions() {
        return this == ACTIVE;
    }

    public boolean terminal() {
        return this == FINISHED || this == CLOSED;
    }
}
