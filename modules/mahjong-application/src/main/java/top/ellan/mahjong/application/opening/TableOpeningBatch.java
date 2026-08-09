package top.ellan.mahjong.application.opening;

import java.util.Objects;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleOpeningPresentation;

/** Immutable public opening emitted once per hand, never during event replay. */
public record TableOpeningBatch(
        TableId tableId,
        RuleId ruleId,
        long revision,
        RuleOpeningPresentation opening) {
    public TableOpeningBatch {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(ruleId, "ruleId");
        if (revision < 0) {
            throw new IllegalArgumentException("opening revision must be non-negative");
        }
        Objects.requireNonNull(opening, "opening");
    }
}
