package top.ellan.mahjong.application.table.actor;

import java.util.Objects;
import top.ellan.mahjong.spi.PlayerId;

/** One human action window that expired without polling any other table or player. */
record HumanDecisionTimeout(PlayerId actor, boolean discardTurn) {
    HumanDecisionTimeout {
        Objects.requireNonNull(actor, "actor");
    }
}
