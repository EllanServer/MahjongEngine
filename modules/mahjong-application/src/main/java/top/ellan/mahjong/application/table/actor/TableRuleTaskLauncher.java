package top.ellan.mahjong.application.table.actor;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;

/** Launches serialized pure-provider work on the bounded fair rule pool. */
final class TableRuleTaskLauncher {
    private final FairRuleExecutor executor;
    private final RuleId ruleId;
    private final RuleComputationEngine computations;

    TableRuleTaskLauncher(
            FairRuleExecutor executor,
            RulePackProvider provider,
            List<TableParticipant> participants,
            TableActorConfig limits,
            RuleId ruleId) {
        this.executor = executor;
        this.ruleId = ruleId;
        computations = new RuleComputationEngine(provider, participants, limits);
    }

    void frame(
            RuleState state,
            long expectedRevision,
            Consumer<RuleTaskCompletion> completion) {
        executor.submit(ruleId, () -> computations.frameOnly(state, expectedRevision))
                .whenComplete((computed, failure) -> completion.accept(new RuleTaskCompletion(
                        expectedRevision,
                        Optional.empty(),
                        Optional.empty(),
                        computed,
                        failure)));
    }

    void transition(
            RuleState state,
            PlayerId actor,
            RuleAction action,
            long expectedRevision,
            long startingSequence,
            long nextAcceptedAction,
            Optional<TableActionEnvelope> envelope,
            Optional<ScheduledActionTrigger> scheduledTrigger,
            Consumer<RuleTaskCompletion> completion) {
        executor.submit(
                        ruleId,
                        () -> computations.transition(
                                state,
                                actor,
                                action,
                                expectedRevision,
                                startingSequence,
                                nextAcceptedAction))
                .whenComplete((computed, failure) -> completion.accept(new RuleTaskCompletion(
                        expectedRevision,
                        envelope,
                        scheduledTrigger,
                        computed,
                        failure)));
    }
}
