package top.ellan.mahjong.application.table.actor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Flag bits (single atomic word instead of four AtomicBooleans).
    private static final int FLAG_INITIALIZE = 1 << 0;
    private static final int FLAG_CLOSE = 1 << 1;
    private static final int FLAG_DUPLICATE_RULE = 1 << 2;
    private static final int FLAG_TRIGGER_OVERFLOW = 1 << 3;

    // Non-empty mask bits; only producers raise bits, only the drain sweeps them away.
    private static final int MASK_ACTIONS = 1 << 0;
    private static final int MASK_SCHEDULED = 1 << 1;
    private static final int MASK_AUTOMATION = 1 << 2;
    private static final int MASK_AUTHORITY = 1 << 3;

    private final ArrayBlockingQueue<TableActionEnvelope> actions;
    private final ArrayBlockingQueue<ScheduledActionTrigger> scheduledTriggers =
            new ArrayBlockingQueue<>(SCHEDULED_TRIGGER_CAPACITY);
    private final ArrayBlockingQueue<AutomationControlEnvelope> automationControls =
            new ArrayBlockingQueue<>(AUTOMATION_CONTROL_CAPACITY);
    private final ArrayBlockingQueue<AuthorityActionEnvelope> authorityActions =
            new ArrayBlockingQueue<>(AUTHORITY_ACTION_CAPACITY);
    private final AtomicLong ingressOrder = new AtomicLong();
    // Manual padding keeps the producer-hot ingressOrder off the same cache line as the
    // signal slots written by the rule executor and persistence callbacks.
    @SuppressWarnings("unused")
    private long p1;

    @SuppressWarnings("unused")
    private long p2;

    @SuppressWarnings("unused")
    private long p3;

    @SuppressWarnings("unused")
    private long p4;

    @SuppressWarnings("unused")
    private long p5;

    @SuppressWarnings("unused")
    private long p6;

    @SuppressWarnings("unused")
    private long p7;
    private final AtomicReference<RuleTaskCompletion> ruleCompletion = new AtomicReference<>();
    private final AtomicReference<OutboxHealth> outboxHealth = new AtomicReference<>();
    private final AtomicInteger flags = new AtomicInteger();
    private final AtomicInteger nonEmptyMask = new AtomicInteger();

    TableActorInbox(int actionCapacity) {
        actions = new ArrayBlockingQueue<>(actionCapacity);
    }

    boolean offerAction(
            PlayerId actor,
            ActionToken token,
            java.util.concurrent.CompletableFuture<TableActionResult> response) {
        if (!actions.offer(
                new TableActionEnvelope(ingressOrder.getAndIncrement(), actor, token, response))) {
            return false;
        }
        nonEmptyMask.updateAndGet(mask -> mask | MASK_ACTIONS);
        return true;
    }

    boolean offerScheduled(long expectedRevision, ScheduledRuleAction scheduledAction) {
        boolean accepted = scheduledTriggers.offer(new ScheduledActionTrigger(
                ingressOrder.getAndIncrement(), expectedRevision, scheduledAction));
        if (accepted) {
            nonEmptyMask.updateAndGet(mask -> mask | MASK_SCHEDULED);
        } else {
            flags.updateAndGet(value -> value | FLAG_TRIGGER_OVERFLOW);
            requestClose();
        }
        return accepted;
    }

    boolean offerAutomation(
            PlayerId playerId,
            boolean enabled,
            java.util.concurrent.CompletableFuture<TableActionResult> response) {
        if (!automationControls.offer(new AutomationControlEnvelope(
                ingressOrder.getAndIncrement(), playerId, enabled, response))) {
            return false;
        }
        nonEmptyMask.updateAndGet(mask -> mask | MASK_AUTOMATION);
        return true;
    }

    boolean offerAuthority(
            PlayerId authority,
            long expectedRevision,
            top.ellan.mahjong.spi.RuleAction action,
            java.util.concurrent.CompletableFuture<TableActionResult> response) {
        if (!authorityActions.offer(new AuthorityActionEnvelope(
                ingressOrder.getAndIncrement(), authority, expectedRevision, action, response))) {
            return false;
        }
        nonEmptyMask.updateAndGet(mask -> mask | MASK_AUTHORITY);
        return true;
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
        flags.updateAndGet(value -> value | FLAG_INITIALIZE);
    }

    boolean takeInitializeRequest() {
        return (flags.getAndUpdate(value -> value & ~FLAG_INITIALIZE) & FLAG_INITIALIZE) != 0;
    }

    void requestClose() {
        flags.updateAndGet(value -> value | FLAG_CLOSE);
    }

    boolean takeCloseRequest() {
        return (flags.getAndUpdate(value -> value & ~FLAG_CLOSE) & FLAG_CLOSE) != 0;
    }

    void completeRule(RuleTaskCompletion completion) {
        if (!ruleCompletion.compareAndSet(null, completion)) {
            flags.updateAndGet(value -> value | FLAG_DUPLICATE_RULE);
            requestClose();
        }
    }

    RuleTaskCompletion takeRuleCompletion() {
        return ruleCompletion.getAndSet(null);
    }

    boolean duplicateRuleCompletion() {
        return (flags.get() & FLAG_DUPLICATE_RULE) != 0;
    }

    boolean scheduledTriggerOverflow() {
        return (flags.get() & FLAG_TRIGGER_OVERFLOW) != 0;
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
        // Only the takeable flags drive rescheduling; DUPLICATE_RULE and TRIGGER_OVERFLOW are
        // latched one-shot diagnostics that must not keep the drain loop spinning.
        if ((flags.get() & (FLAG_INITIALIZE | FLAG_CLOSE)) != 0
                || ruleCompletion.get() != null
                || outboxHealth.get() != null) {
            return true;
        }
        int mask = nonEmptyMask.get();
        if (mask == 0) {
            return false;
        }
        boolean actionsEmpty = actions.isEmpty();
        boolean automationEmpty = automationControls.isEmpty();
        boolean authorityEmpty = authorityActions.isEmpty();
        boolean scheduledEmpty = scheduledTriggers.isEmpty();
        if (actionsEmpty && automationEmpty && authorityEmpty && scheduledEmpty) {
            // A failed CAS means a producer raced us and raised a bit again, so there is work.
            return !nonEmptyMask.compareAndSet(mask, 0);
        }
        return true;
    }
}
