package top.ellan.mahjong.application.table.actor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private static final Duration PLAYER_TIMEOUT = Duration.ofMillis(750);
    private static final Duration AUTOMATION_TIMEOUT = Duration.ofSeconds(1);

    private final FairRuleExecutor rules;
    private final FairRuleExecutor automation;
    private final RuleId ruleId;
    private final RuleComputationEngine computations;

    TableRuleTaskLauncher(
            FairRuleExecutor rules,
            FairRuleExecutor automation,
            RulePackProvider provider,
            List<TableParticipant> participants,
            TableActorConfig limits,
            RuleId ruleId) {
        this.rules = rules;
        this.automation = automation;
        this.ruleId = ruleId;
        computations = new RuleComputationEngine(provider, participants, limits);
    }

    void frame(
            RuleState state,
            long expectedRevision,
            List<PlayerId> automatedPlayers,
            Consumer<RuleTaskCompletion> completion) {
        List<PlayerId> automationSnapshot = List.copyOf(automatedPlayers);
        submit(
                        !automationSnapshot.isEmpty(),
                        ruleId,
                        () -> computations.frameOnly(state, expectedRevision, automationSnapshot))
                .whenComplete((computed, failure) -> completion.accept(new RuleTaskCompletion(
                        expectedRevision,
                        Optional.empty(),
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
            List<PlayerId> automatedPlayers,
            Optional<TableActionEnvelope> envelope,
            Optional<AuthorityActionEnvelope> authorityEnvelope,
            Optional<ScheduledActionTrigger> scheduledTrigger,
            Consumer<RuleTaskCompletion> completion) {
        List<PlayerId> automationSnapshot = List.copyOf(automatedPlayers);
        boolean automatedExecution = scheduledTrigger.isPresent()
                && automationSnapshot.contains(actor);
        submit(
                        automatedExecution,
                        ruleId,
                        () -> computations.transition(
                                state,
                                actor,
                                action,
                                expectedRevision,
                                startingSequence,
                                nextAcceptedAction,
                                automationSnapshot))
                .whenComplete((computed, failure) -> completion.accept(new RuleTaskCompletion(
                        expectedRevision,
                        envelope,
                        authorityEnvelope,
                        scheduledTrigger,
                        computed,
                        failure)));
    }

    private FairRuleExecutor executorFor(boolean automatedExecution) {
        return automatedExecution ? automation : rules;
    }

    private <T> CompletableFuture<T> submit(
            boolean automatedExecution, RuleId id, Supplier<T> task) {
        Duration timeout = automatedExecution ? AUTOMATION_TIMEOUT : PLAYER_TIMEOUT;
        return executorFor(automatedExecution).submit(id, timeout, task);
    }
}
