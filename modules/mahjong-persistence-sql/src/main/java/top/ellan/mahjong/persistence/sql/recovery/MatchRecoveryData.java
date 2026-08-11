package top.ellan.mahjong.persistence.sql.recovery;

import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;

import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.domain.table.TableParticipant;

/** Last durable snapshot plus actions committed after it. */
public record MatchRecoveryData(
        MatchInstanceRecord match,
        List<TableParticipant> participants,
        RuleStateSnapshot snapshot,
        long snapshotStateRevision,
        List<RecoveredAction> actionsAfterSnapshot) {
    public MatchRecoveryData {
        Objects.requireNonNull(match, "match");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshotStateRevision < 0) {
            throw new IllegalArgumentException("Snapshot revision must be non-negative");
        }
        actionsAfterSnapshot =
                List.copyOf(Objects.requireNonNull(actionsAfterSnapshot, "actionsAfterSnapshot"));
        if (snapshot.sequence() > match.lastCommittedSequence()) {
            throw new IllegalArgumentException("Snapshot is ahead of the committed boundary");
        }
    }
}
