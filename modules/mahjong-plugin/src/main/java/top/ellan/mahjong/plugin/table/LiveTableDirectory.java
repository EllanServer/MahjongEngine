package top.ellan.mahjong.plugin.table;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Small synchronized ownership index; it is touched only on table lifecycle boundaries. */
public final class LiveTableDirectory {
    private final Map<TableId, List<TableParticipant>> reservations = new HashMap<>();
    private final Map<TableId, StartedRulePackMatch> tables = new HashMap<>();
    private final Map<PlayerId, TableId> tableByPlayer = new HashMap<>();

    public synchronized boolean reserve(TableId tableId, List<TableParticipant> participants) {
        Objects.requireNonNull(tableId, "tableId");
        List<TableParticipant> copy =
                List.copyOf(Objects.requireNonNull(participants, "participants"));
        if (reservations.containsKey(tableId) || tables.containsKey(tableId)) {
            return false;
        }
        if (copy.stream().anyMatch(value -> tableByPlayer.containsKey(value.playerId()))) {
            return false;
        }
        reservations.put(tableId, copy);
        copy.forEach(value -> tableByPlayer.put(value.playerId(), tableId));
        return true;
    }

    public synchronized void completeReservation(StartedRulePackMatch started) {
        Objects.requireNonNull(started, "started");
        List<TableParticipant> reserved = reservations.remove(started.tableId());
        if (reserved == null || !reserved.equals(started.participants())) {
            throw new IllegalStateException("No matching table reservation");
        }
        if (tables.putIfAbsent(started.tableId(), started) != null) {
            throw new IllegalStateException("Table is already live");
        }
    }

    public synchronized void releaseReservation(TableId tableId) {
        List<TableParticipant> removed = reservations.remove(Objects.requireNonNull(tableId, "tableId"));
        if (removed != null) {
            removed.forEach(value -> tableByPlayer.remove(value.playerId(), tableId));
        }
    }

    public synchronized boolean registerRecovered(StartedRulePackMatch started) {
        Objects.requireNonNull(started, "started");
        if (tables.containsKey(started.tableId())
                || started.participants().stream()
                        .anyMatch(value -> tableByPlayer.containsKey(value.playerId()))) {
            return false;
        }
        tables.put(started.tableId(), started);
        started.participants()
                .forEach(value -> tableByPlayer.put(value.playerId(), started.tableId()));
        return true;
    }

    public synchronized Optional<StartedRulePackMatch> find(TableId tableId) {
        return Optional.ofNullable(tables.get(Objects.requireNonNull(tableId, "tableId")));
    }

    public synchronized Optional<StartedRulePackMatch> findByPlayer(PlayerId playerId) {
        TableId tableId = tableByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return tableId == null ? Optional.empty() : Optional.ofNullable(tables.get(tableId));
    }

    public synchronized boolean ownsPlayer(PlayerId playerId) {
        return tableByPlayer.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    public synchronized Optional<SeatId> seatOf(TableId tableId, PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        StartedRulePackMatch table = tables.get(Objects.requireNonNull(tableId, "tableId"));
        if (table == null) {
            return Optional.empty();
        }
        return table.participants().stream()
                .filter(participant -> participant.playerId().equals(playerId))
                .flatMap(participant -> participant.seat().stream())
                .findFirst();
    }

    public synchronized List<StartedRulePackMatch> list() {
        return tables.values().stream()
                .sorted(java.util.Comparator.comparing(StartedRulePackMatch::tableId))
                .toList();
    }

    public synchronized Optional<StartedRulePackMatch> remove(TableId tableId) {
        StartedRulePackMatch removed = tables.remove(Objects.requireNonNull(tableId, "tableId"));
        if (removed != null) {
            removed.participants()
                    .forEach(value -> tableByPlayer.remove(value.playerId(), tableId));
        }
        return Optional.ofNullable(removed);
    }
}
