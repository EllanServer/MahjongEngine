package top.ellan.mahjong.craftengine;

import java.util.Objects;
import top.ellan.mahjong.domain.TableId;

/** Render fault notification; it never changes rule or persistence state. */
public record CraftEngineTableFailure(TableId tableId, long revision, String failureType) {
    public CraftEngineTableFailure {
        Objects.requireNonNull(tableId, "tableId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        failureType = Objects.requireNonNull(failureType, "failureType");
    }
}
