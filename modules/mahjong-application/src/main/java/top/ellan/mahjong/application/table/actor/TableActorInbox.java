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
    private static final int AUTOMATION_CONTROL_CAPACITY = 8;
    private static final int AUTHORITY_ACTION_CAPACITY = 8;

    private final ArrayBlockingQueue<TableActionEnvelope> actions;
    private final ArrayBlockingQueue<ScheduledActionTrigger> scheduledTriggers =
            new ArrayBlockingQueue<>(SCHEDULED_TRIGGER_CAPACITY);
    private final ArrayBlockingQueue<AutomationControlEnvelope> automationControls =
            new ArrayBlockingQueue<>(AUTOMATION_CONTROL_CAPACITY);
    private final ArrayBlockingQueue<AuthorityActionEnvelope> authorityActions =
            new ArrayBlockingQueue<>(AUTHORITY_ACTION_CAPACITY);
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

    boolean offerAutomation(
            PlayerId playerId,
            boolean enabled,
            java.util.concurrent.CompletableFuture<TableActionResult> response) {
        return automationControls.offer(new AutomationControlEnvelope(
                ingressOrder.getAndIncrement(), playerId, enabled, response));
    }

    boolean offerAuthority(
            PlayerId authority,
            long expectedRevision,
            top.ellan.mahjong.spi.RuleAction action,
            java.util.concurrent.CompletableFuture<TableActionResult> response) {
        return authorityActions.offer(new AuthorityActionEnvelope(
                ingressOrder.getAndIncrement(), authority, expectedRevision, action, response));
    }

    TableIngress pollIngress() {
        TableActionEnvelope action = actions.peek();
        ScheduledActionTrigger scheduled = scheduledTriggers.peek();
        AutomationControlEnvelope automation = automationControls.peek();
        AuthorityActionEnvelope authority = authorityActions.peek();
        long actionOrder = action == null ? Long.MAX_VALUE : action.ingressOrder();
        long scheduledOrder = scheduled == null ? Long.MAX_VALUE : scheduled.ingressOrder();
        long automationOrder = automation == null ? Long.MAX_VALUE : automation.ingressOrder();
        long authorityOrder = authority == null ? Long.MAX_VALUE : authority.ingressOrder();
        if (actionOrder <= scheduledOrder
                && actionOrder <= automationOrder
                && actionOrder <= authorityOrder) {
            return actions.poll();
        }
        if (automationOrder <= scheduledOrder && automationOrder <= authorityOrder) {
            return automationControls.poll();
        }
        if (authorityOrder <= scheduledOrder) {
            return authorityActions.poll();
        }
        return scheduledTriggers.poll();
    }

    TableActionEnvelope pollActionForClose() {
        return actions.poll();
    }

    AutomationControlEnvelope pollAutomationForClose() {
        return automationControls.poll();
    }

    AuthorityActionEnvelope pollAuthorityForClose() {
        return authorityActions.poll();
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
        return actions.size() + automationControls.size() + authorityActions.size();
    }

    boolean hasPendingWork() {
        return closeRequested.get()
                || initializeRequested.get()
                || ruleCompletion.get() != null
                || outboxHealth.get() != null
                || !actions.isEmpty()
                || !automationControls.isEmpty()
                || !authorityActions.isEmpty()
                || !scheduledTriggers.isEmpty();
    }
}
