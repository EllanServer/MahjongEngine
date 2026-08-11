package top.ellan.mahjong.application.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.domain.match.MatchId;

/** Ordered transaction request for one match only. */
public record MatchWriteBatch(
        MatchId matchId, List<MatchEventRecord> events, Optional<SnapshotWrite> snapshot) {
    public MatchWriteBatch {
        Objects.requireNonNull(matchId, "matchId");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("A write batch requires at least one event");
        }
        long expected = events.getFirst().sequence();
        long previousRevision = 0;
        for (MatchEventRecord event : events) {
            if (!event.matchId().equals(matchId) || event.sequence() != expected++) {
                throw new IllegalArgumentException("Batch events must be contiguous and match-scoped");
            }
            if (event.stateRevision() < previousRevision) {
                throw new IllegalArgumentException("State revisions must be monotonic");
            }
            previousRevision = event.stateRevision();
        }
        if (snapshot.isPresent()) {
            SnapshotWrite value = snapshot.orElseThrow();
            if (!value.matchId().equals(matchId)
                    || value.snapshot().sequence() > events.getLast().sequence()) {
                throw new IllegalArgumentException("Snapshot is outside the batch boundary");
            }
        }
    }
}
