package top.ellan.mahjong.plugin.table;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Small synchronized ownership index; it is touched only on table lifecycle boundaries. */
public final class LiveTableDirectory {
    private final Map<TableId, List<TableParticipant>> reservations = new HashMap<>();
    private final ConcurrentHashMap<TableId, StartedRulePackMatch> tables =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, TableId> tableByPlayer =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableId, Set<PlayerId>> departures =
            new ConcurrentHashMap<>();

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

    public Optional<StartedRulePackMatch> find(TableId tableId) {
        return Optional.ofNullable(tables.get(Objects.requireNonNull(tableId, "tableId")));
    }

    public Optional<StartedRulePackMatch> findByPlayer(PlayerId playerId) {
        TableId tableId = tableByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return tableId == null ? Optional.empty() : Optional.ofNullable(tables.get(tableId));
    }

    public boolean ownsPlayer(PlayerId playerId) {
        return tableByPlayer.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    public Optional<SeatId> seatOf(TableId tableId, PlayerId playerId) {
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

    /** Marks a live-match departure without mutating the rule pack's fixed player roster. */
    public synchronized Optional<Departure> markDeparting(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        TableId tableId = tableByPlayer.get(playerId);
        StartedRulePackMatch table = tableId == null ? null : tables.get(tableId);
        if (table == null) {
            return Optional.empty();
        }
        ParticipantRole role = table.participants().stream()
                .filter(participant -> participant.playerId().equals(playerId))
                .map(TableParticipant::role)
                .findFirst()
                .orElse(null);
        if (role == null || role == ParticipantRole.BOT) {
            return Optional.empty();
        }
        Set<PlayerId> updated = new HashSet<>(departures.getOrDefault(tableId, Set.of()));
        boolean changed = updated.add(playerId);
        departures.put(tableId, Set.copyOf(updated));
        if (role == ParticipantRole.SPECTATOR) {
            tableByPlayer.remove(playerId, tableId);
        }
        return Optional.of(new Departure(tableId, role, changed));
    }

    public boolean isDeparting(TableId tableId, PlayerId playerId) {
        return departures.getOrDefault(
                        Objects.requireNonNull(tableId, "tableId"), Set.of())
                .contains(Objects.requireNonNull(playerId, "playerId"));
    }

    public List<StartedRulePackMatch> list() {
        return tables.values().stream()
                .sorted(java.util.Comparator.comparing(StartedRulePackMatch::tableId))
                .toList();
    }

    public int size() {
        return tables.size();
    }

    public synchronized Optional<StartedRulePackMatch> remove(TableId tableId) {
        StartedRulePackMatch removed = tables.remove(Objects.requireNonNull(tableId, "tableId"));
        if (removed != null) {
            removed.participants()
                    .forEach(value -> tableByPlayer.remove(value.playerId(), tableId));
            departures.remove(tableId);
        }
        return Optional.ofNullable(removed);
    }

    /** Removes only the exact completed generation and returns its deferred departures. */
    public synchronized Optional<Removed> removeCompleted(
            TableId tableId, MatchBinding binding) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(binding, "binding");
        StartedRulePackMatch current = tables.get(tableId);
        if (current == null || !current.binding().equals(binding)) {
            return Optional.empty();
        }
        tables.remove(tableId);
        current.participants()
                .forEach(value -> tableByPlayer.remove(value.playerId(), tableId));
        Set<PlayerId> departed = Set.copyOf(departures.getOrDefault(tableId, Set.of()));
        departures.remove(tableId);
        return Optional.of(new Removed(current, departed));
    }

    public record Departure(TableId tableId, ParticipantRole role, boolean changed) {}

    public record Removed(StartedRulePackMatch match, Set<PlayerId> departedPlayers) {
        public Removed {
            Objects.requireNonNull(match, "match");
            departedPlayers = Set.copyOf(
                    Objects.requireNonNull(departedPlayers, "departedPlayers"));
        }
    }
}
