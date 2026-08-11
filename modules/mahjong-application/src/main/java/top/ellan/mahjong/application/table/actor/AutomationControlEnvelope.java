package top.ellan.mahjong.application.table.actor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.spi.PlayerId;

/** Arrival-ordered bounded trustee toggle owned by the table actor. */
record AutomationControlEnvelope(
        long ingressOrder,
        PlayerId playerId,
        boolean enabled,
        CompletableFuture<TableActionResult> response)
        implements TableIngress {
    AutomationControlEnvelope {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(response, "response");
    }
}
