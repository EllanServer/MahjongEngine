package top.ellan.mahjong.application.table.actor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import top.ellan.mahjong.application.persistence.OutboxHealth;

/**
 * Bounded external mailbox plus coalescing internal signal slots.
 *
 * <p>Rule and persistence continuations cannot be displaced by player input, while repeated health
 * updates collapse to the latest value without creating an unbounded queue.</p>
 */
final class TableActorInbox {
    private final ArrayBlockingQueue<TableActionEnvelope> actions;
    private final AtomicReference<RuleTaskCompletion> ruleCompletion = new AtomicReference<>();
    private final AtomicReference<OutboxHealth> outboxHealth = new AtomicReference<>();
    private final AtomicBoolean initializeRequested = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean duplicateRuleCompletion = new AtomicBoolean();

    TableActorInbox(int actionCapacity) {
        actions = new ArrayBlockingQueue<>(actionCapacity);
    }

    boolean offer(TableActionEnvelope action) {
        return actions.offer(action);
    }

    TableActionEnvelope pollAction() {
        return actions.poll();
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
                || !actions.isEmpty();
    }
}
