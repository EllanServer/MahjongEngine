package top.ellan.mahjong.application.lobby.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import top.ellan.mahjong.application.lobby.port.LobbyStateObserver;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.PlayerId;

/**
 * O(1) read index for lobbies. Four-seat mutations are serialized only at lifecycle boundaries;
 * Paper and CraftEngine event threads never wait for rule, render, or SQL work.
 */
public final class LobbyTableDirectory implements LobbyStateObserver {
    private final ConcurrentHashMap<TableId, HostedLobby> lobbies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, TableId> tableByPlayer = new ConcurrentHashMap<>();
    private final Map<TableId, Set<PlayerId>> indexedPlayers = new HashMap<>();
    private final Map<TableId, PlayerId> reservations = new HashMap<>();

    public synchronized boolean reserve(TableId tableId, PlayerId ownerId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(ownerId, "ownerId");
        if (reservations.containsKey(tableId)
                || lobbies.containsKey(tableId)
                || tableByPlayer.containsKey(ownerId)) {
            return false;
        }
        reservations.put(tableId, ownerId);
        tableByPlayer.put(ownerId, tableId);
        return true;
    }

    public synchronized void releaseReservation(TableId tableId) {
        PlayerId owner = reservations.remove(Objects.requireNonNull(tableId, "tableId"));
        if (owner != null) {
            tableByPlayer.remove(owner, tableId);
        }
    }

    public synchronized void completeReservation(HostedLobby hosted) {
        Objects.requireNonNull(hosted, "hosted");
        PlayerId owner = reservations.remove(hosted.tableId());
        if (owner == null || !owner.equals(hosted.state().ownerId())) {
            throw new IllegalStateException("no matching lobby reservation");
        }
        tableByPlayer.remove(owner, hosted.tableId());
        if (!register(hosted)) {
            throw new IllegalStateException("lobby reservation became invalid");
        }
    }

    public synchronized boolean register(HostedLobby hosted) {
        Objects.requireNonNull(hosted, "hosted");
        TableLobby state = hosted.state();
        Set<PlayerId> players = indexedPlayers(state);
        if (reservations.containsKey(state.tableId())
                || lobbies.containsKey(state.tableId())
                || players.stream().anyMatch(tableByPlayer::containsKey)) {
            return false;
        }
        lobbies.put(state.tableId(), hosted);
        indexedPlayers.put(state.tableId(), players);
        players.forEach(player -> tableByPlayer.put(player, state.tableId()));
        return true;
    }

    @Override
    public synchronized void changed(TableLobby state) {
        Objects.requireNonNull(state, "state");
        if (!lobbies.containsKey(state.tableId())) {
            return;
        }
        Set<PlayerId> previous = indexedPlayers.getOrDefault(state.tableId(), Set.of());
        Set<PlayerId> next = indexedPlayers(state);
        for (PlayerId player : next) {
            TableId existing = tableByPlayer.get(player);
            if (existing != null && !existing.equals(state.tableId())) {
                throw new IllegalStateException("player belongs to another lobby");
            }
        }
        previous.stream()
                .filter(player -> !next.contains(player))
                .forEach(player -> tableByPlayer.remove(player, state.tableId()));
        next.forEach(player -> tableByPlayer.put(player, state.tableId()));
        indexedPlayers.put(state.tableId(), next);
    }

    public Optional<HostedLobby> find(TableId tableId) {
        return Optional.ofNullable(lobbies.get(Objects.requireNonNull(tableId, "tableId")));
    }

    public Optional<HostedLobby> findByPlayer(PlayerId playerId) {
        TableId tableId = tableByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return tableId == null ? Optional.empty() : Optional.ofNullable(lobbies.get(tableId));
    }

    public int size() {
        return lobbies.size();
    }

    public synchronized Set<TableId> tableIds() {
        HashSet<TableId> result = new HashSet<>(lobbies.keySet());
        result.addAll(reservations.keySet());
        return Set.copyOf(result);
    }

    public synchronized Optional<HostedLobby> remove(TableId tableId) {
        Objects.requireNonNull(tableId, "tableId");
        HostedLobby removed = lobbies.remove(tableId);
        Set<PlayerId> players = indexedPlayers.remove(tableId);
        if (players != null) {
            players.forEach(player -> tableByPlayer.remove(player, tableId));
        }
        return Optional.ofNullable(removed);
    }

    private static Set<PlayerId> indexedPlayers(TableLobby state) {
        HashSet<PlayerId> result = new HashSet<>();
        result.add(state.ownerId());
        for (LobbySeat seat : state.seats()) {
            seat.occupant().ifPresent(result::add);
        }
        result.addAll(state.spectators());
        return Set.copyOf(result);
    }
}
