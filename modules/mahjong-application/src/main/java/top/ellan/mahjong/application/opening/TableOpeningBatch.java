package top.ellan.mahjong.application.opening;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleOpeningPresentation;
import top.ellan.mahjong.spi.RulePackRef;

/** Immutable public opening emitted once per hand, never during event replay. */
public record TableOpeningBatch(
        TableId tableId,
        RulePackRef rulePack,
        long revision,
        List<PlayerId> audience,
        RuleOpeningPresentation opening) {
    public TableOpeningBatch {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(rulePack, "rulePack");
        if (revision < 0) {
            throw new IllegalArgumentException("opening revision must be non-negative");
        }
        audience = List.copyOf(Objects.requireNonNull(audience, "audience"));
        if (new HashSet<>(audience).size() != audience.size()) {
            throw new IllegalArgumentException("opening audience contains duplicates");
        }
        Objects.requireNonNull(opening, "opening");
    }

    public RuleId ruleId() {
        return rulePack.ruleId();
    }
}
