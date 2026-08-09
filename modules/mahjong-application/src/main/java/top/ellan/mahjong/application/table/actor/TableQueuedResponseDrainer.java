package top.ellan.mahjong.application.table.actor;

import java.util.function.BiFunction;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;

/** Completes every response-bearing bounded queue during shutdown or dispatcher rejection. */
final class TableQueuedResponseDrainer {
    private TableQueuedResponseDrainer() {}

    static void complete(
            TableActorInbox inbox,
            TableActionCode code,
            String reason,
            BiFunction<TableActionCode, String, TableActionResult> result) {
        TableActionEnvelope action;
        while ((action = inbox.pollActionForClose()) != null) {
            action.response().complete(result.apply(code, reason));
        }
        AutomationControlEnvelope automation;
        while ((automation = inbox.pollAutomationForClose()) != null) {
            automation.response().complete(result.apply(code, reason));
        }
        AuthorityActionEnvelope authority;
        while ((authority = inbox.pollAuthorityForClose()) != null) {
            authority.response().complete(result.apply(code, reason));
        }
    }
}
