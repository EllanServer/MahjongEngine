package top.ellan.mahjong.domain.lobby;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/**
 * Immutable pre-match table state. It contains no Paper or rule implementation types and can be
 * persisted independently from a match snapshot.
 */
public record TableLobby(
        TableId tableId,
        long revision,
        PlayerId ownerId,
        RuleId ruleId,
        ProfileId profileId,
        Map<String, String> configuration,
        List<LobbySeat> seats,
        Set<PlayerId> spectators,
        LobbyPhase phase,
        Instant createdAt) {
    private static final int MAX_CONFIGURATION_ENTRIES = 64;

    public TableLobby {
        Objects.requireNonNull(tableId, "tableId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.size() > MAX_CONFIGURATION_ENTRIES) {
            throw new IllegalArgumentException("too many lobby configuration entries");
        }
        LinkedHashMap<String, String> configurationCopy = new LinkedHashMap<>();
        configuration.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            String key = requireConfigurationText(entry.getKey(), "key");
                            String value = requireConfigurationText(entry.getValue(), "value");
                            configurationCopy.put(key, value);
                        });
        configuration = Map.copyOf(configurationCopy);
        seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        if (seats.size() < 2 || seats.size() > 4) {
            throw new IllegalArgumentException("a lobby requires two through four seats");
        }
        for (int index = 0; index < seats.size(); index++) {
            if (seats.get(index).seatId().value() != index) {
                throw new IllegalArgumentException("lobby seats must be contiguous and ordered");
            }
        }
        spectators = Set.copyOf(Objects.requireNonNull(spectators, "spectators"));
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(createdAt, "createdAt");
        HashSet<PlayerId> participants = new HashSet<>();
        for (LobbySeat seat : seats) {
            seat.occupant()
                    .ifPresent(
                            player -> {
                                if (!participants.add(player)) {
                                    throw new IllegalArgumentException("duplicate seated player");
                                }
                            });
        }
        for (PlayerId spectator : spectators) {
            if (!participants.add(spectator)) {
                throw new IllegalArgumentException("a player cannot be seated and spectating");
            }
        }
    }

    public static TableLobby create(
            TableId tableId,
            PlayerId ownerId,
            RuleId ruleId,
            ProfileId profileId,
            Map<String, String> configuration,
            int seatCount,
            Instant createdAt) {
        ArrayList<LobbySeat> seats = new ArrayList<>(seatCount);
        for (int index = 0; index < seatCount; index++) {
            seats.add(LobbySeat.empty(new SeatId(index)));
        }
        return new TableLobby(
                tableId,
                0,
                ownerId,
                ruleId,
                profileId,
                configuration,
                seats,
                Set.of(),
                LobbyPhase.WAITING,
                createdAt);
    }

    public Optional<SeatId> seatOf(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        for (LobbySeat seat : seats) {
            if (seat.occupant().filter(playerId::equals).isPresent()) {
                return Optional.of(seat.seatId());
            }
        }
        return Optional.empty();
    }

    public boolean contains(PlayerId playerId) {
        return seatOf(playerId).isPresent() || spectators.contains(playerId);
    }

    public int occupiedSeatCount() {
        int occupied = 0;
        for (LobbySeat seat : seats) {
            if (seat.occupant().isPresent()) {
                occupied++;
            }
        }
        return occupied;
    }

    public boolean isBotSeat(LobbySeat seat) {
        Objects.requireNonNull(seat, "seat");
        return LobbyBotIdentity.occupies(this, seat);
    }

    /** All four seats are filled and ready; the owner may be an unseated spectator (v1.5 bot match). */
    public boolean readyToStart() {
        if (phase != LobbyPhase.WAITING || occupiedSeatCount() != seats.size()) {
            return false;
        }
        for (LobbySeat seat : seats) {
            if (!seat.ready() || seat.presence() != SeatPresence.ONLINE) {
                return false;
            }
        }
        return true;
    }

    public List<TableParticipant> matchParticipants() {
        if (occupiedSeatCount() != seats.size()) {
            throw new IllegalStateException("all seats must be occupied before creating a match");
        }
        ArrayList<TableParticipant> result = new ArrayList<>(seats.size() + spectators.size());
        for (LobbySeat seat : seats) {
            ParticipantRole role = isBotSeat(seat)
                    ? ParticipantRole.BOT
                    : ParticipantRole.PLAYER;
            result.add(
                    new TableParticipant(
                            seat.occupant().orElseThrow(),
                            role,
                            Optional.of(seat.seatId())));
        }
        spectators.stream()
                .sorted()
                .forEach(
                        player ->
                                result.add(
                                        new TableParticipant(
                                                player,
                                                ParticipantRole.SPECTATOR,
                                                Optional.empty())));
        return List.copyOf(result);
    }

    public TableLobby withState(
            List<LobbySeat> nextSeats,
            Set<PlayerId> nextSpectators,
            LobbyPhase nextPhase) {
        return withOwnerAndState(ownerId, nextSeats, nextSpectators, nextPhase);
    }

    /**
     * Advances the lobby revision while atomically changing ownership and membership.
     *
     * <p>Ownership transfer is part of the same immutable transition as vacating the previous
     * owner's seat. This prevents an observer or persistence adapter from seeing an owner that is
     * no longer seated while another player has already taken control of the lobby.
     */
    public TableLobby withOwnerAndState(
            PlayerId nextOwnerId,
            List<LobbySeat> nextSeats,
            Set<PlayerId> nextSpectators,
            LobbyPhase nextPhase) {
        return new TableLobby(
                tableId,
                revision + 1,
                Objects.requireNonNull(nextOwnerId, "nextOwnerId"),
                ruleId,
                profileId,
                configuration,
                nextSeats,
                nextSpectators,
                nextPhase,
                createdAt);
    }

    public TableLobby withRules(
            RuleId nextRuleId,
            ProfileId nextProfileId,
            Map<String, String> nextConfiguration) {
        ArrayList<LobbySeat> reset = new ArrayList<>(seats.size());
        for (LobbySeat seat : seats) {
            boolean bot = isBotSeat(seat);
            reset.add(
                    seat.occupant().isEmpty()
                            ? seat
                            : new LobbySeat(
                                    seat.seatId(),
                                    seat.occupant(),
                                    bot,
                                    bot ? SeatPresence.ONLINE : seat.presence()));
        }
        return new TableLobby(
                tableId,
                revision + 1,
                ownerId,
                Objects.requireNonNull(nextRuleId, "nextRuleId"),
                Objects.requireNonNull(nextProfileId, "nextProfileId"),
                nextConfiguration,
                reset,
                spectators,
                LobbyPhase.WAITING,
                createdAt);
    }

    public TableLobby recoveredOffline() {
        ArrayList<LobbySeat> recoveredSeats = new ArrayList<>(seats.size());
        for (LobbySeat seat : seats) {
            boolean bot = isBotSeat(seat);
            recoveredSeats.add(
                    seat.occupant().isEmpty()
                            ? seat
                            : new LobbySeat(
                                    seat.seatId(),
                                    seat.occupant(),
                                    bot,
                                    bot ? SeatPresence.ONLINE : SeatPresence.OFFLINE));
        }
        return new TableLobby(
                tableId,
                revision,
                ownerId,
                ruleId,
                profileId,
                configuration,
                recoveredSeats,
                new LinkedHashSet<>(spectators),
                LobbyPhase.WAITING,
                createdAt);
    }

    /**
     * Reopens the durable table shell after a completed match.
     *
     * <p>Explicit departures vacate their seats, retained humans must ready again, deterministic
     * bots remain ready, and ownership moves to the first retained human when necessary. An empty
     * human roster returns {@link Optional#empty()} so the physical table cannot become orphaned.
     */
    public Optional<TableLobby> resetForReuse(Set<PlayerId> departedPlayers) {
        return TableLobbyReuse.reset(this, departedPlayers);
    }

    private static String requireConfigurationText(String value, String field) {
        value = Objects.requireNonNull(value, "configuration " + field);
        if (value.length() > 4_096 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid configuration " + field);
        }
        return value;
    }
}
