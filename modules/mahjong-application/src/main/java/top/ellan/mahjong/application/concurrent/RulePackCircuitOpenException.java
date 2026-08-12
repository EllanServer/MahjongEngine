package top.ellan.mahjong.application.concurrent;

import java.util.concurrent.RejectedExecutionException;
import top.ellan.mahjong.spi.RuleId;

/** Raised when a repeatedly failing or over-budget rule pack is isolated from further work. */
public final class RulePackCircuitOpenException extends RejectedExecutionException {
    private static final long serialVersionUID = 1L;

    public RulePackCircuitOpenException(RuleId ruleId) {
        super("rule-pack circuit is open: " + ruleId);
    }

    RulePackCircuitOpenException(RuleId ruleId, Throwable cause) {
        super("rule-pack circuit opened: " + ruleId, cause);
    }
}
