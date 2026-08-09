package top.ellan.mahjong.application.table.actor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ScheduledRuleAction;

/**
 * Bounded external mailbox plus coalescing internal signal slots.
 *
 * <p>Rule and persistence continuations cannot be displaced by player input, while repeated health
 * updates collapse to the latest value without creating an unbounded queue.</p>
 */
final class TableActorInbox {
    private static final int SCHEDULED_TRIGGER_CAPACITY = 4;

    private final ArrayBlockingQueue<TableActionEnvelope> actions;
    private final ArrayBlockingQueue<ScheduledActionTrigger> scheduledTriggers =
            new ArrayBlockingQueue<>(SCHEDULED_TRIGGER_CAPACITY);
    private final AtomicLong ingressOrder = new AtomicLong();
    private final AtomicReference<RuleTaskCompletion> ruleCompletion = new AtomicReference<>();
    private final AtomicReference<OutboxHealth> outboxHealth = new AtomicReference<>();
    private final AtomicBoolean initializeRequested = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean duplicateRuleCompletion = new AtomicBoolean();
    private final AtomicBoolean scheduledTriggerOverflow = new AtomicBoolean();

    TableActorInbox(int actionCapacity) {
        actions = new ArrayBlockingQueue<>(actionCapacity);
    }

    boolean offerAction(
            PlayerId actor,
            ActionToken token,
            java.util.concurrent.CompletableFuture<TableActionResult> response) {
        return actions.offer(
                new TableActionEnvelope(ingressOrder.getAndIncrement(), actor, token, response));
    }

    boolean offerScheduled(long expectedRevision, ScheduledRuleAction scheduledAction) {
        boolean accepted = scheduledTriggers.offer(new ScheduledActionTrigger(
                ingressOrder.getAndIncrement(), expectedRevision, scheduledAction));
        if (!accepted) {
            scheduledTriggerOverflow.set(true);
            requestClose();
        }
        return accepted;
    }

    TableIngress pollIngress() {
        TableActionEnvelope action = actions.peek();
        ScheduledActionTrigger scheduled = scheduledTriggers.peek();
        if (action == null) {
            return scheduledTriggers.poll();
        }
        if (scheduled == null || action.ingressOrder() <= scheduled.ingressOrder()) {
            return actions.poll();
        }
        return scheduledTriggers.poll();
    }

    TableActionEnvelope pollActionForClose() {
        return actions.poll();
    }

    void clearScheduledTriggers() {
        scheduledTriggers.clear();
    }

    void requestInitialize() {
        initializeRequested.set(true);
    }

    boolean takeInitializeRequest() {
        return initializeRequested.getAndSet(false);
    }

    void requestClose() {
        closeRequested.set(true);
    }

    boolean takeCloseRequest() {
        return closeRequested.getAndSet(false);
    }

    void completeRule(RuleTaskCompletion completion) {
        if (!ruleCompletion.compareAndSet(null, completion)) {
            duplicateRuleCompletion.set(true);
            requestClose();
        }
    }

    RuleTaskCompletion takeRuleCompletion() {
        return ruleCompletion.getAndSet(null);
    }

    boolean duplicateRuleCompletion() {
        return duplicateRuleCompletion.get();
    }

    boolean scheduledTriggerOverflow() {
        return scheduledTriggerOverflow.get();
    }

    void updateOutbox(OutboxHealth health) {
        outboxHealth.set(health);
    }

    OutboxHealth takeOutboxHealth() {
        return outboxHealth.getAndSet(null);
    }

    int actionCount() {
        return actions.size();
    }

    boolean hasPendingWork() {
        return closeRequested.get()
                || initializeRequested.get()
                || ruleCompletion.get() != null
                || outboxHealth.get() != null
                || !actions.isEmpty()
                || !scheduledTriggers.isEmpty();
    }
}
