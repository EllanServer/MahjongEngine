package top.ellan.mahjong.application.table.actor;

import java.util.Map;
import java.util.UUID;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.domain.TableAggregate;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;

/** Validates lifecycle, actor ownership, revision and capability token before rule execution. */
final class TableActionAdmission {
    private TableActionAdmission() {}

    static ActionAdmission evaluate(
            TableAggregate aggregate,
            boolean ruleInFlight,
            PlayerId actor,
            ActionToken token,
            Map<UUID, AuthorizedAction> actionCatalog,
            String failureCode) {
        TableLifecycle lifecycle = aggregate.lifecycle();
        if (lifecycle == TableLifecycle.PAUSED_PERSISTENCE) {
            return ActionAdmission.rejected(
                    TableActionCode.TABLE_PAUSED,
                    "persistence-backpressure");
        }
        if (lifecycle == TableLifecycle.BLOCKED_RULE_PACK
                || lifecycle == TableLifecycle.NEEDS_ADMIN_REVIEW) {
            return ActionAdmission.rejected(TableActionCode.TABLE_BLOCKED, failureCode);
        }
        if (lifecycle.terminal()) {
            return ActionAdmission.rejected(TableActionCode.TABLE_FINISHED, "match-ended");
        }
        if (!lifecycle.acceptsRuleActions()) {
            return ActionAdmission.rejected(TableActionCode.TABLE_BLOCKED, "not-active");
        }
        if (ruleInFlight) {
            return ActionAdmission.rejected(
                    TableActionCode.RULE_BUSY,
                    "rule-calculation-in-flight");
        }
        if (!token.actor().equals(actor)) {
            return ActionAdmission.rejected(
                    TableActionCode.WRONG_ACTOR,
                    "token-owner-mismatch");
        }
        if (token.revision() != aggregate.revision()) {
            return ActionAdmission.rejected(TableActionCode.STALE_TOKEN, "stale-revision");
        }
        AuthorizedAction authorized = actionCatalog.get(token.value());
        if (authorized == null || !authorized.token().equals(token)) {
            return ActionAdmission.rejected(TableActionCode.STALE_TOKEN, "unknown-token");
        }
        return ActionAdmission.accepted(authorized.legalAction().action());
    }
}
