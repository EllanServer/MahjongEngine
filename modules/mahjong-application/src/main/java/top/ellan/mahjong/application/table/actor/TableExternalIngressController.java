package top.ellan.mahjong.application.table.actor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** O(1) bounded submission facade for ordinary interactions and trustee controls. */
final class TableExternalIngressController {
    private final TableActorInbox inbox;
    private final BooleanSupplier closed;
    private final BiFunction<TableActionCode, String, TableActionResult> result;
    private final Runnable scheduleDrain;

    TableExternalIngressController(
            TableActorInbox inbox,
            BooleanSupplier closed,
            BiFunction<TableActionCode, String, TableActionResult> result,
            Runnable scheduleDrain) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.closed = Objects.requireNonNull(closed, "closed");
        this.result = Objects.requireNonNull(result, "result");
        this.scheduleDrain = Objects.requireNonNull(scheduleDrain, "scheduleDrain");
    }

    CompletionStage<TableActionResult> submit(PlayerId actor, ActionToken token) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(token, "token");
        CompletableFuture<TableActionResult> response = availableResponse();
        if (response.isDone()) {
            return response;
        }
        if (!inbox.offerAction(actor, token, response)) {
            response.complete(result.apply(TableActionCode.MAILBOX_FULL, "mailbox-full"));
        } else {
            scheduleDrain.run();
        }
        return response;
    }

    CompletionStage<TableActionResult> setAutomated(PlayerId playerId, boolean enabled) {
        Objects.requireNonNull(playerId, "playerId");
        CompletableFuture<TableActionResult> response = availableResponse();
        if (response.isDone()) {
            return response;
        }
        if (!inbox.offerAutomation(playerId, enabled, response)) {
            response.complete(result.apply(TableActionCode.MAILBOX_FULL, "automation-mailbox-full"));
        } else {
            scheduleDrain.run();
        }
        return response;
    }

    private CompletableFuture<TableActionResult> availableResponse() {
        return closed.getAsBoolean()
                ? CompletableFuture.completedFuture(
                        result.apply(TableActionCode.TABLE_CLOSED, "closed"))
                : new CompletableFuture<>();
    }
}
