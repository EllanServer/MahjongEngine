package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.SeatWind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TableParticipantRegistry {
    private final List<UUID> seats = new ArrayList<>(Collections.nCopies(4, null));
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final Map<UUID, String> botNames = new LinkedHashMap<>();
    private final Map<UUID, SeatWind> seatByPlayer = new HashMap<>(4);
    private final Set<UUID> readyPlayers = new LinkedHashSet<>();
    private final Set<UUID> leaveAfterRoundPlayers = new LinkedHashSet<>();
    private int occupiedSeatCount;
    private int nextBotNumber = 1;

    boolean addPlayer(UUID playerId, SeatWind wind) {
        if (playerId == null || wind == null || this.seatByPlayer.containsKey(playerId) || this.seats.get(wind.index()) != null || this.occupiedSeatCount >= 4) {
            return false;
        }
        this.spectators.remove(playerId);
        this.occupySeat(playerId, wind);
        this.clearPlayerStates(playerId);
        return true;
    }

    boolean addSpectator(UUID playerId, boolean spectateAllowed) {
        if (!spectateAllowed || playerId == null || this.seatByPlayer.containsKey(playerId) || this.spectators.contains(playerId)) {
            return false;
        }
        this.spectators.add(playerId);
        return true;
    }

    boolean removeSpectator(UUID playerId) {
        return this.spectators.remove(playerId);
    }

    UUID createNextBotId(String tableId) {
        UUID botId;
        do {
            botId = UUID.nameUUIDFromBytes((tableId + "-bot-" + this.nextBotNumber).getBytes(StandardCharsets.UTF_8));
            this.nextBotNumber++;
        } while (this.seatByPlayer.containsKey(botId));
        return botId;
    }

    boolean addBot(UUID botId) {
        SeatWind emptySeat = this.firstEmptySeat();
        if (botId == null || emptySeat == null) {
            return false;
        }
        this.occupySeat(botId, emptySeat);
        this.botNames.put(botId, "Bot-" + this.botNames.size());
        this.readyPlayers.add(botId);
        this.leaveAfterRoundPlayers.remove(botId);
        return true;
    }

    boolean replaceBotWithPlayer(UUID playerId, SeatWind wind) {
        if (playerId == null || wind == null || this.seats.contains(playerId)) {
            return false;
        }
        UUID seated = this.seats.get(wind.index());
        if (seated == null || !this.botNames.containsKey(seated)) {
            return false;
        }
        this.botNames.remove(seated);
        this.clearPlayerStates(seated);
        this.spectators.remove(playerId);
        this.vacateSeat(wind);
        this.occupySeat(playerId, wind);
        this.clearPlayerStates(playerId);
        return true;
    }

    UUID removeLastBot() {
        for (int i = this.seats.size() - 1; i >= 0; i--) {
            UUID playerId = this.seats.get(i);
            if (playerId != null && this.botNames.containsKey(playerId)) {
                this.vacateSeat(SeatWind.fromIndex(i));
                this.botNames.remove(playerId);
                this.clearPlayerStates(playerId);
                return playerId;
            }
        }
        return null;
    }

    boolean removePlayer(UUID playerId) {
        this.botNames.remove(playerId);
        this.clearPlayerStates(playerId);
        SeatWind wind = this.seatByPlayer.get(playerId);
        if (wind == null) {
            return false;
        }
        this.vacateSeat(wind);
        return true;
    }

    boolean contains(UUID playerId) {
        return this.seatByPlayer.containsKey(playerId);
    }

    boolean isEmpty() {
        return this.occupiedSeatCount == 0;
    }

    int size() {
        return this.occupiedSeatCount;
    }

    int botCount() {
        return this.botNames.size();
    }

    int spectatorCount() {
        return this.spectators.size();
    }

    List<UUID> players() {
        return this.seats.stream().filter(java.util.Objects::nonNull).toList();
    }

    Set<UUID> spectators() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.spectators));
    }

    UUID owner() {
        for (UUID playerId : this.seats) {
            if (playerId != null) {
                return playerId;
            }
        }
        return null;
    }

    UUID firstHumanPlayer() {
        for (UUID playerId : this.seats) {
            if (playerId != null && !this.botNames.containsKey(playerId)) {
                return playerId;
            }
        }
        return null;
    }

    boolean isReady(UUID playerId) {
        return playerId != null && (this.botNames.containsKey(playerId) || this.readyPlayers.contains(playerId));
    }

    int readyCount() {
        int ready = 0;
        for (Map.Entry<UUID, SeatWind> entry : this.seatByPlayer.entrySet()) {
            UUID playerId = entry.getKey();
            if (this.isReady(playerId)) {
                ready++;
            }
        }
        return ready;
    }

    boolean toggleReady(UUID playerId) {
        if (this.readyPlayers.contains(playerId)) {
            this.readyPlayers.remove(playerId);
            return false;
        }
        this.readyPlayers.add(playerId);
        return true;
    }

    boolean isQueuedToLeave(UUID playerId) {
        return playerId != null && this.leaveAfterRoundPlayers.contains(playerId);
    }

    void queueLeave(UUID playerId) {
        this.leaveAfterRoundPlayers.add(playerId);
    }

    boolean hasQueuedLeaves() {
        return !this.leaveAfterRoundPlayers.isEmpty();
    }

    Set<UUID> queuedLeavePlayers() {
        return Set.copyOf(this.leaveAfterRoundPlayers);
    }

    void removeQueuedLeaves(List<UUID> removedPlayerIds) {
        this.leaveAfterRoundPlayers.removeAll(removedPlayerIds);
    }

    boolean isBot(UUID playerId) {
        return this.botNames.containsKey(playerId);
    }

    UUID playerAt(SeatWind wind) {
        return this.seats.get(wind.index());
    }

    SeatWind firstEmptySeat() {
        for (SeatWind wind : SeatWind.values()) {
            if (this.seats.get(wind.index()) == null) {
                return wind;
            }
        }
        return null;
    }

    List<UUID> seatIds() {
        return this.seats;
    }

    Set<UUID> spectatorIds() {
        return this.spectators;
    }

    String botDisplayNameSource(UUID playerId) {
        return this.botNames.get(playerId);
    }

    int seatIndexOf(UUID playerId) {
        SeatWind wind = this.seatByPlayer.get(playerId);
        return wind == null ? -1 : wind.index();
    }

    /**
     * O(1) reverse lookup by playerId. Used by MahjongTableSession.seatOf to
     * avoid the previous O(4) linear scan over SeatWind.values() that called
     * playerAt(wind) for each wind. The underlying seatByPlayer map is
     * maintained as an invariant of occupySeat/vacateSeat so a single
     * HashMap.get is sufficient.
     *
     * Thread-safety: same contract as the rest of this class — callers run
     * on the table's region thread for correctness; cross-thread callers
     * get a best-effort read (HashMap.get is not synchronized but does not
     * throw ConcurrentModificationException, and the worst case is a stale
     * null which the caller treats as "not seated"). See MahjongTableSession.seatOf
     * for the full contract.
     */
    SeatWind seatOf(UUID playerId) {
        return playerId == null ? null : this.seatByPlayer.get(playerId);
    }

    void clearReadyPlayers() {
        this.readyPlayers.clear();
    }

    void readyBotsOnly() {
        this.readyPlayers.clear();
        for (UUID playerId : this.seats) {
            if (playerId != null && this.botNames.containsKey(playerId)) {
                this.readyPlayers.add(playerId);
            }
        }
    }

    void clearLeaveQueue() {
        this.leaveAfterRoundPlayers.clear();
    }

    void clearSpectators() {
        this.spectators.clear();
    }

    void clearBotNames() {
        this.botNames.clear();
    }

    void clearSeats() {
        Collections.fill(this.seats, null);
        this.seatByPlayer.clear();
        this.occupiedSeatCount = 0;
    }

    void resetBotCounter() {
        this.nextBotNumber = 1;
    }

    private void occupySeat(UUID playerId, SeatWind wind) {
        this.seats.set(wind.index(), playerId);
        this.seatByPlayer.put(playerId, wind);
        this.occupiedSeatCount++;
    }

    private void vacateSeat(SeatWind wind) {
        UUID previous = this.seats.get(wind.index());
        if (previous != null) {
            this.seatByPlayer.remove(previous);
            this.occupiedSeatCount--;
        }
        this.seats.set(wind.index(), null);
    }

    private void clearPlayerStates(UUID playerId) {
        this.readyPlayers.remove(playerId);
        this.leaveAfterRoundPlayers.remove(playerId);
    }
}

