package top.ellan.mahjong.application.table.actor;

import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.spi.RuleAction;

/** One admission decision with either an action or a stable rejection code. */
record ActionAdmission(
        RuleAction action,
        TableActionCode rejectionCode,
        String reasonCode) {
    static ActionAdmission accepted(RuleAction action) {
        return new ActionAdmission(action, null, "");
    }

    static ActionAdmission rejected(TableActionCode code, String reasonCode) {
        return new ActionAdmission(null, code, reasonCode);
    }

    boolean accepted() {
        return action != null;
    }
}
