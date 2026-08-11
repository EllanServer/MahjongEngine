package top.ellan.mahjong.domain.table;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.domain.match.CompetitionRef;
import top.ellan.mahjong.domain.match.MatchBinding;

/** Small immutable aggregate owned by a TableActor. */
public record TableAggregate(
        TableId tableId,
        long revision,
        TableLifecycle lifecycle,
        List<TableParticipant> participants,
        Optional<MatchBinding> matchBinding,
        CompetitionRef competitionRef) {
    public TableAggregate {
        Objects.requireNonNull(tableId, "tableId");
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
        Objects.requireNonNull(lifecycle, "lifecycle");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        matchBinding = Objects.requireNonNull(matchBinding, "matchBinding");
        Objects.requireNonNull(competitionRef, "competitionRef");
        if (new HashSet<>(participants.stream().map(TableParticipant::playerId).toList()).size()
                != participants.size()) {
            throw new IllegalArgumentException("Duplicate table participant");
        }
        long distinctSeats =
                participants.stream().flatMap(item -> item.seat().stream()).distinct().count();
        long seatedPlayers = participants.stream().filter(item -> item.seat().isPresent()).count();
        if (distinctSeats != seatedPlayers) {
            throw new IllegalArgumentException("Duplicate table seat");
        }
        if (lifecycle == TableLifecycle.ACTIVE && matchBinding.isEmpty()) {
            throw new IllegalArgumentException("An active table requires a match binding");
        }
    }

    public TableAggregate withRevision(long nextRevision) {
        if (nextRevision <= revision) {
            throw new IllegalArgumentException("Revision must advance");
        }
        return new TableAggregate(
                tableId, nextRevision, lifecycle, participants, matchBinding, competitionRef);
    }

    public TableAggregate withLifecycle(TableLifecycle nextLifecycle) {
        return new TableAggregate(
                tableId, revision, nextLifecycle, participants, matchBinding, competitionRef);
    }
}
