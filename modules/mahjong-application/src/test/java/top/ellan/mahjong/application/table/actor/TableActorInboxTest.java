package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.ScheduledRuleAction;

class TableActorInboxTest {
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void internalContinuationsKeepReservedSlotsWhenPlayerMailboxIsFull() {
        TableActorInbox inbox = new TableActorInbox(1);
        assertTrue(offer(inbox, 1));
        assertFalse(offer(inbox, 2));

        RuleTaskCompletion completion =
                new RuleTaskCompletion(
                        0, Optional.empty(), Optional.empty(), null, null);
        OutboxHealth health = new OutboxHealth(3, Duration.ofMillis(5), 7, false, Optional.empty());
        inbox.completeRule(completion);
        inbox.updateOutbox(health);

        assertSame(completion, inbox.takeRuleCompletion());
        assertSame(health, inbox.takeOutboxHealth());
        assertTrue(inbox.hasPendingWork());
    }

    @Test
    void duplicateRuleContinuationFailsClosed() {
        TableActorInbox inbox = new TableActorInbox(1);
        inbox.completeRule(new RuleTaskCompletion(
                0, Optional.empty(), Optional.empty(), null, null));
        inbox.completeRule(new RuleTaskCompletion(
                0, Optional.empty(), Optional.empty(), null, null));

        assertTrue(inbox.duplicateRuleCompletion());
        assertTrue(inbox.takeCloseRequest());
    }

    @Test
    void playerActionAndDeadlinePreserveIngressOrder() {
        TableActorInbox playerFirst = new TableActorInbox(2);
        assertTrue(offer(playerFirst, 0));
        assertTrue(playerFirst.offerScheduled(0, scheduled()));
        assertTrue(playerFirst.pollIngress() instanceof TableActionEnvelope);
        assertTrue(playerFirst.pollIngress() instanceof ScheduledActionTrigger);

        TableActorInbox deadlineFirst = new TableActorInbox(2);
        assertTrue(deadlineFirst.offerScheduled(0, scheduled()));
        assertTrue(offer(deadlineFirst, 0));
        assertTrue(deadlineFirst.pollIngress() instanceof ScheduledActionTrigger);
        assertTrue(deadlineFirst.pollIngress() instanceof TableActionEnvelope);
    }

    @Test
    void scheduledTriggerOverflowFailsOnlyThisInboxClosed() {
        TableActorInbox inbox = new TableActorInbox(1);
        for (int index = 0; index < 4; index++) {
            assertTrue(inbox.offerScheduled(index, scheduled()));
        }
        assertFalse(inbox.offerScheduled(5, scheduled()));
        assertTrue(inbox.scheduledTriggerOverflow());
        assertTrue(inbox.takeCloseRequest());
    }

    private static boolean offer(TableActorInbox inbox, long revision) {
        return inbox.offerAction(
                PLAYER,
                new ActionToken(UUID.randomUUID(), PLAYER, revision),
                new CompletableFuture<TableActionResult>());
    }

    private static ScheduledRuleAction scheduled() {
        return new ScheduledRuleAction(
                PLAYER,
                new RuleAction("timeout", new byte[0]),
                Duration.ZERO,
                "test-timeout");
    }
}
