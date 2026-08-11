package top.ellan.mahjong.plugin.runtime;

import top.ellan.mahjong.application.concurrent.FairRuleExecutor;

/** Bounded execution pools for player-driven rules and automated players. */
public final class RuleExecutionPools implements AutoCloseable {
    private final FairRuleExecutor rules;
    private final FairRuleExecutor automation;

    public RuleExecutionPools(int processors) {
        int availableProcessors = Math.max(1, processors);
        int ruleWorkers =
                Math.max(2, Math.min(Math.max(1, availableProcessors / 2), 8));
        int automationWorkers =
                Math.max(1, Math.min(Math.max(1, availableProcessors / 4), 2));
        rules = new FairRuleExecutor(ruleWorkers, 1_024, "mahjong-rule");
        automation =
                new FairRuleExecutor(automationWorkers, 1_024, "mahjong-automation");
    }

    public FairRuleExecutor rules() {
        return rules;
    }

    public FairRuleExecutor automation() {
        return automation;
    }

    @Override
    public void close() {
        automation.close();
        rules.close();
    }
}
