package top.ellan.mahjong.application.table.actor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/** Bounded authority submission and lifecycle/revision admission policy. */
final class TableAuthorityActionController {
    private final TableActorInbox inbox;
    private final BooleanSupplier closed;
    private final BiFunction<TableActionCode, String, TableActionResult> result;
    private final Runnable scheduleDrain;

    TableAuthorityActionController(
            TableActorInbox inbox,
            BooleanSupplier closed,
            BiFunction<TableActionCode, String, TableActionResult> result,
            Runnable scheduleDrain) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.closed = Objects.requireNonNull(closed, "closed");
        this.result = Objects.requireNonNull(result, "result");
        this.scheduleDrain = Objects.requireNonNull(scheduleDrain, "scheduleDrain");
    }

    CompletionStage<TableActionResult> submit(
            PlayerId authority, long expectedRevision, RuleAction action) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(action, "action");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        if (closed.getAsBoolean()) {
            return CompletableFuture.completedFuture(
                    result.apply(TableActionCode.TABLE_CLOSED, "closed"));
        }
        CompletableFuture<TableActionResult> response = new CompletableFuture<>();
        if (!inbox.offerAuthority(authority, expectedRevision, action, response)) {
            response.complete(result.apply(TableActionCode.MAILBOX_FULL, "authority-mailbox-full"));
            return response;
        }
        scheduleDrain.run();
        return response;
    }

    ActionAdmission admit(
            AuthorityActionEnvelope envelope,
            TableActorStateMachine stateMachine,
            boolean ruleInFlight) {
        if (closed.getAsBoolean()) {
            return ActionAdmission.rejected(TableActionCode.TABLE_CLOSED, "closed");
        }
        TableLifecycle lifecycle = stateMachine.lifecycle();
        if (lifecycle.terminal()) {
            return ActionAdmission.rejected(TableActionCode.TABLE_FINISHED, "match-ended");
        }
        if (!lifecycle.acceptsRuleActions()) {
            TableActionCode code = lifecycle == TableLifecycle.PAUSED_PERSISTENCE
                    ? TableActionCode.TABLE_PAUSED
                    : TableActionCode.TABLE_BLOCKED;
            return ActionAdmission.rejected(code, "table-not-active");
        }
        if (ruleInFlight) {
            return ActionAdmission.rejected(
                    TableActionCode.RULE_BUSY, "rule-calculation-in-flight");
        }
        if (envelope.expectedRevision() != stateMachine.revision()) {
            return ActionAdmission.rejected(TableActionCode.STALE_TOKEN, "stale-revision");
        }
        return ActionAdmission.accepted(envelope.action());
    }
}
