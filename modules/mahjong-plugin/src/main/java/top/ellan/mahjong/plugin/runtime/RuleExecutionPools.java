package top.ellan.mahjong.plugin.runtime;

import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.spi.RuleId;

/** Bounded execution pools for player-driven rules and automated players. */
public final class RuleExecutionPools implements AutoCloseable {
    private final FairRuleExecutor rules;
    private final FairRuleExecutor automation;

    public RuleExecutionPools(int processors) {
        int availableProcessors = Math.max(1, processors);
        int workerBudget = Math.max(1, availableProcessors - 1);
        int automationWorkers =
                Math.max(1, Math.min(Math.max(1, workerBudget / 4), 2));
        int ruleWorkers =
                Math.max(1, Math.min(Math.max(1, workerBudget - automationWorkers), 8));
        rules = new FairRuleExecutor(ruleWorkers, 1_024, 256, "mahjong-rule");
        automation =
                new FairRuleExecutor(automationWorkers, 512, 128, "mahjong-automation");
    }

    public FairRuleExecutor rules() {
        return rules;
    }

    public FairRuleExecutor automation() {
        return automation;
    }

    public void resetCircuit(RuleId ruleId) {
        rules.resetCircuit(ruleId);
        automation.resetCircuit(ruleId);
    }

    public void renewWorkers() {
        rules.renewWorkers();
        automation.renewWorkers();
    }

    @Override
    public void close() {
        automation.close();
        rules.close();
    }
}
