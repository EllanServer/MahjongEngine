package top.ellan.mahjong.application.table;

import java.util.Objects;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.table.TableId;

/** Durable terminal boundary emitted after the final rule snapshot and result are committed. */
public record MatchCompletion(TableId tableId, MatchBinding binding, long stateRevision) {
    public MatchCompletion {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(binding, "binding");
        if (stateRevision < 0) {
            throw new IllegalArgumentException("stateRevision must be non-negative");
        }
    }
}
