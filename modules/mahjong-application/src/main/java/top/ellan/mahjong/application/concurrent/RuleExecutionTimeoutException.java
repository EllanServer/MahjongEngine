package top.ellan.mahjong.application.concurrent;

import top.ellan.mahjong.spi.RuleId;

/** Indicates that one provider invocation exceeded its host-owned deadline. */
public final class RuleExecutionTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RuleExecutionTimeoutException(RuleId ruleId) {
        super("rule execution timed out: " + ruleId);
    }
}
