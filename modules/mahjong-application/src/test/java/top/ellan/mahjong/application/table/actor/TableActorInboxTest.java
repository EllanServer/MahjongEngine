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

class TableActorInboxTest {
    private static final PlayerId PLAYER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void internalContinuationsKeepReservedSlotsWhenPlayerMailboxIsFull() {
        TableActorInbox inbox = new TableActorInbox(1);
        assertTrue(inbox.offer(action(1)));
        assertFalse(inbox.offer(action(2)));

        RuleTaskCompletion completion =
                new RuleTaskCompletion(0, Optional.empty(), null, null);
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
        inbox.completeRule(new RuleTaskCompletion(0, Optional.empty(), null, null));
        inbox.completeRule(new RuleTaskCompletion(0, Optional.empty(), null, null));

        assertTrue(inbox.duplicateRuleCompletion());
        assertTrue(inbox.takeCloseRequest());
    }

    private static TableActionEnvelope action(long revision) {
        return new TableActionEnvelope(
                PLAYER,
                new ActionToken(UUID.randomUUID(), PLAYER, revision),
                new CompletableFuture<TableActionResult>());
    }
}
