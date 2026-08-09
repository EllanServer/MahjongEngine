package top.ellan.mahjong.application.persistence;

import java.time.Instant;
import java.util.Objects;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.spi.RuleStateSnapshot;

/** Snapshot row written in the same transaction as its covering event batch. */
public record SnapshotWrite(
        MatchId matchId,
        long stateRevision,
        Instant createdAt,
        RuleStateSnapshot snapshot,
        java.util.Optional<TableLifecycle> lifecycleAfterCommit) {
    public SnapshotWrite(MatchId matchId, Instant createdAt, RuleStateSnapshot snapshot) {
        this(matchId, snapshot.sequence(), createdAt, snapshot, java.util.Optional.empty());
    }

    public SnapshotWrite(
            MatchId matchId,
            long stateRevision,
            Instant createdAt,
            RuleStateSnapshot snapshot) {
        this(matchId, stateRevision, createdAt, snapshot, java.util.Optional.empty());
    }

    public SnapshotWrite {
        Objects.requireNonNull(matchId, "matchId");
        if (stateRevision < 0) {
            throw new IllegalArgumentException("Snapshot state revision must be non-negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(snapshot, "snapshot");
        lifecycleAfterCommit = Objects.requireNonNull(
                lifecycleAfterCommit, "lifecycleAfterCommit");
        lifecycleAfterCommit.ifPresent(status -> {
            if (status != TableLifecycle.ACTIVE && status != TableLifecycle.FINISHED) {
                throw new IllegalArgumentException(
                        "Only ACTIVE or FINISHED may be committed with a rule snapshot");
            }
        });
    }
}
