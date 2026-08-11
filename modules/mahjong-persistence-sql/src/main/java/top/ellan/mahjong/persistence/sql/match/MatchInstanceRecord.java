package top.ellan.mahjong.persistence.sql.match;

import java.time.Instant;
import java.util.Objects;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;

/** Durable match metadata; rule provenance is never inferred from current configuration. */
public record MatchInstanceRecord(
        MatchBinding binding,
        TableId tableId,
        TableLifecycle status,
        Instant updatedAt,
        long lastCommittedSequence) {
    public MatchInstanceRecord {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (lastCommittedSequence < 0) {
            throw new IllegalArgumentException("lastCommittedSequence must be non-negative");
        }
    }
}
