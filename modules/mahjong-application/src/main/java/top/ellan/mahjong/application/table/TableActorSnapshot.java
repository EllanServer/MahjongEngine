package top.ellan.mahjong.application.table;

import java.util.Objects;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;

/** Lock-free diagnostic snapshot published by the single writer. */
public record TableActorSnapshot(
        TableId tableId,
        long revision,
        TableLifecycle lifecycle,
        int mailboxDepth,
        boolean ruleCalculationInFlight,
        OutboxHealth outboxHealth,
        String failureCode) {
    public TableActorSnapshot {
        Objects.requireNonNull(tableId, "tableId");
        if (revision < 0 || mailboxDepth < 0) {
            throw new IllegalArgumentException("Invalid actor counters");
        }
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(outboxHealth, "outboxHealth");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
    }
}
