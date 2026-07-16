package top.ellan.mahjong.gameroom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.MahjongTableSession;

public final class GameRoomManager {
    private final MahjongTableManager tableManager;
    private final Supplier<DebugService> debugSupplier;
    private final ServerScheduler scheduler;
    private final MessageService messages;
    private final Supplier<PluginSettings> settingsSupplier;
    private volatile Path storageFile;
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoomMembership = new ConcurrentHashMap<>();
    private final Map<UUID, Long> exitCountdowns = new ConcurrentHashMap<>();
    private final Map<UUID, String> exitCountdownTableIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> exitCountdownRoomIds = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<Integer>> warnedSeconds = new ConcurrentHashMap<>();

    public GameRoomManager(
        MahjongTableManager tableManager,
        Supplier<DebugService> debugSupplier,
        ServerScheduler scheduler,
        MessageService messages,
        Supplier<PluginSettings> settingsSupplier,
        Path storageFile
    ) {
        this.tableManager = Objects.requireNonNull(tableManager, "tableManager");
        this.debugSupplier = Objects.requireNonNull(debugSupplier, "debugSupplier");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile");
    }

    public Collection<GameRoom> rooms() {
        return List.copyOf(this.rooms.values());
    }

    public GameRoom room(String id) {
        if (id == null) {
            return null;
        }
        return this.rooms.get(GameRoom.normalizeId(id));
    }

    public Optional<GameRoom> roomAt(Location location) {
        if (location == null) {
            return Optional.empty();
        }
        for (GameRoom room : this.rooms.values()) {
            if (room.contains(location)) {
                return Optional.of(room);
            }
        }
        return Optional.empty();
    }

    public boolean isLocationInAnyRoom(Location location) {
        return this.roomAt(location).isPresent();
    }

    public GameRoom createRoom(GameRoom room) {
        Objects.requireNonNull(room, "room");
        GameRoom existing = this.rooms.putIfAbsent(room.id(), room);
        if (existing != null) {
            return existing;
        }
        this.save();
        return room;
    }

    public boolean deleteRoom(String id) {
        if (id == null) {
            return false;
        }
        GameRoom removed = this.rooms.remove(GameRoom.normalizeId(id));
        if (removed == null) {
            return false;
        }
        this.playerRoomMembership.entrySet().removeIf(entry -> removed.id().equals(entry.getValue()));
        java.util.List<UUID> affectedCountdownPlayers = this.exitCountdownRoomIds.entrySet().stream()
            .filter(entry -> removed.id().equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
        for (UUID playerId : affectedCountdownPlayers) {
            this.removeCountdownState(playerId);
        }
        this.save();
        return true;
    }

    public void load() {
        this.rooms.clear();
        if (!Files.exists(this.storageFile)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.storageFile.toFile());
        ConfigurationSection roomsSection = yaml.getConfigurationSection("rooms");
        if (roomsSection == null) {
            return;
        }
        for (String id : roomsSection.getKeys(false)) {
            ConfigurationSection section = roomsSection.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            String world = section.getString("world");
            int minX = section.getInt("minX");
            int minY = section.getInt("minY");
            int minZ = section.getInt("minZ");
            int maxX = section.getInt("maxX");
            int maxY = section.getInt("maxY");
            int maxZ = section.getInt("maxZ");
            String name = section.getString("name", id);
            UUID owner = section.getString("owner") == null ? null : UUID.fromString(section.getString("owner"));
            if (world == null || world.isBlank()) {
                continue;
            }
            this.rooms.put(GameRoom.normalizeId(id), new GameRoom(id, name, world, minX, minY, minZ, maxX, maxY, maxZ, owner));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (GameRoom room : this.rooms.values()) {
            String base = "rooms." + room.id();
            yaml.set(base + ".name", room.name());
            yaml.set(base + ".world", room.worldName());
            yaml.set(base + ".minX", room.minX());
            yaml.set(base + ".minY", room.minY());
            yaml.set(base + ".minZ", room.minZ());
            yaml.set(base + ".maxX", room.maxX());
            yaml.set(base + ".maxY", room.maxY());
            yaml.set(base + ".maxZ", room.maxZ());
            yaml.set(base + ".owner", room.ownerId() == null ? null : room.ownerId().toString());
        }
        try {
            Files.createDirectories(this.storageFile.getParent());
            yaml.save(this.storageFile.toFile());
        } catch (IOException ex) {
            this.logDebug("Failed to save game rooms: " + ex.getMessage());
        }
    }

    public void refreshConfiguration(Path storageFile) {
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile");
        this.load();
        this.playerRoomMembership.entrySet().removeIf(entry -> !this.rooms.containsKey(entry.getValue()));
        if (!this.isEnabled()) {
            this.clearRuntimeState();
        }
    }

    public Optional<GameRoom> roomForPlayer(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        String roomId = this.playerRoomMembership.get(playerId);
        if (roomId != null) {
            GameRoom room = this.rooms.get(roomId);
            if (room != null) {
                return Optional.of(room);
            }
        }
        return Optional.empty();
    }

    private Optional<GameRoom> roomFor(UUID playerId) {
        String roomId = this.playerRoomMembership.get(playerId);
        if (roomId != null) {
            GameRoom room = this.rooms.get(roomId);
            if (room != null) {
                return Optional.of(room);
            }
        }
        return Optional.empty();
    }

    public void updateMembership(UUID playerId, Location location) {
        if (playerId == null || location == null) {
            return;
        }
        Optional<GameRoom> currentRoom = this.roomAt(location);
        Optional<GameRoom> previousRoom = this.roomFor(playerId);

        boolean sameRoom = previousRoom.isPresent() && currentRoom.isPresent()
            && previousRoom.get().id().equals(currentRoom.get().id());
        if (sameRoom) {
            return;
        }

        // Player left a room
        if (previousRoom.isPresent() && currentRoom.isEmpty()) {
            GameRoom room = previousRoom.get();
            this.playerRoomMembership.remove(playerId);
            // If player is in an active match, start countdown
            this.startExitCountdownIfNeeded(playerId, room);
        }

        // Player entered a room
        if (currentRoom.isPresent()) {
            this.playerRoomMembership.put(playerId, currentRoom.get().id());
            // Cancel countdown if player returns
            this.cancelExitCountdown(playerId);
        }
    }

    public void handlePlayerQuit(UUID playerId) {
        if (!this.isEnabled() || playerId == null) {
            return;
        }
        Optional<GameRoom> previousRoom = this.roomFor(playerId);
        if (previousRoom.isEmpty()) {
            return;
        }
        this.playerRoomMembership.remove(playerId);
        this.startExitCountdownIfNeeded(playerId, previousRoom.get());
    }

    private void startExitCountdownIfNeeded(UUID playerId, GameRoom room) {
        MahjongTableSession session = this.tableManager.tableFor(playerId);
        if (session == null || !session.isStarted()) {
            return;
        }
        int countdownSeconds = this.settingsSupplier.get().gameRooms().leaveCountdownSeconds();
        long deadline = System.currentTimeMillis() + countdownSeconds * 1000L;
        this.exitCountdowns.put(playerId, deadline);
        this.exitCountdownTableIds.put(playerId, session.id());
        if (room != null) {
            this.exitCountdownRoomIds.put(playerId, room.id());
        }

        // Send warning message
        org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            this.messages.send(player, "gameroom.leave_warning",
                this.messages.tag("seconds", String.valueOf(countdownSeconds)),
                this.messages.tag("room_name", room == null ? "" : room.name())
            );
        }
        this.logDebug("Player " + playerId + " left room during match, countdown started (" + countdownSeconds + "s)");
    }

    private void cancelExitCountdown(UUID playerId) {
        Long removed = this.exitCountdowns.remove(playerId);
        this.exitCountdownTableIds.remove(playerId);
        this.exitCountdownRoomIds.remove(playerId);
        this.warnedSeconds.remove(playerId);
        if (removed != null) {
            org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                this.messages.send(player, "gameroom.countdown_cancelled");
            }
            this.logDebug("Player " + playerId + " returned, countdown cancelled");
        }
    }

    public boolean hasActiveCountdown(UUID playerId) {
        return this.exitCountdowns.containsKey(playerId);
    }

    public Optional<GameRoom> roomForTable(MahjongTableSession table) {
        if (table == null) {
            return Optional.empty();
        }
        return this.roomAt(table.center());
    }

    public Optional<GameRoom> roomForTableLocation(Location tableCenter) {
        return this.roomAt(tableCenter);
    }

    public boolean isTableInAnyRoom(Location tableCenter) {
        return this.roomAt(tableCenter).isPresent();
    }

    public boolean isRestrictNewTables() {
        PluginSettings settings = this.settingsSupplier.get();
        return settings.gameRooms().enabled() && settings.gameRooms().restrictNewTables();
    }

    public boolean isEnabled() {
        PluginSettings settings = this.settingsSupplier.get();
        return settings != null && settings.gameRooms().enabled();
    }

    public boolean isEnterExitMessages() {
        return this.settingsSupplier.get().gameRooms().enterExitMessages();
    }

    public int defaultRadius() {
        return this.settingsSupplier.get().gameRooms().defaultRadius();
    }

    public int defaultHeight() {
        return this.settingsSupplier.get().gameRooms().defaultHeight();
    }

    public void tick() {
        if (!this.isEnabled()) {
            if (!this.exitCountdowns.isEmpty() || !this.playerRoomMembership.isEmpty()) {
                this.clearRuntimeState();
            }
            return;
        }
        if (this.exitCountdowns.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        var iterator = this.exitCountdowns.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            UUID playerId = entry.getKey();
            long deadline = entry.getValue();

            if (now >= deadline) {
                // Countdown expired, force end the match and remove the player
                // who left the room from the table without moving them.
                String tableId = this.exitCountdownTableIds.get(playerId);
                if (tableId != null) {
                    this.logDebug("Player " + playerId + " countdown expired, force-ending table " + tableId);
                    // Schedule force-end on the table's region thread to avoid
                    // cross-thread mutation of game state (round controller,
                    // viewer presentation, bot task, etc.) on Folia.
                    MahjongTableSession session = this.tableManager.resolveTableById(tableId);
                    // Guard against redundant scheduling: if the match has already
                    // ended and the player is no longer at the table, skip the
                    // region task dispatch. Otherwise force-end the match and
                    // remove the leaving player from the table (without teleport).
                    if (session != null && (session.isStarted() || session.contains(playerId))) {
                        this.scheduler.runRegion(session.center(), () -> {
                            this.tableManager.forceEndTable(tableId);
                            this.tableManager.removePlayerFromTableWithoutMove(playerId);
                        });
                    }
                }
                iterator.remove();
                this.removeCountdownState(playerId);
            } else {
                long remainingSeconds = (deadline - now) / 1000;
                int remaining = (int) remainingSeconds;
                if (isCountdownWarningSecond(remaining)) {
                    java.util.Set<Integer> warned = this.warnedSeconds.computeIfAbsent(playerId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
                    if (warned.add(remaining)) {
                        org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
                        if (player != null) {
                            this.messages.send(player, "gameroom.countdown",
                                this.messages.tag("seconds", String.valueOf(remaining))
                            );
                        }
                    }
                }
            }
        }
    }

    private static boolean isCountdownWarningSecond(int remainingSeconds) {
        if (remainingSeconds <= 0) {
            return false;
        }
        // Last 10 seconds: warn at 10, 8, 6, 5, 4, 3, 2, 1
        if (remainingSeconds <= 10) {
            return remainingSeconds == 10 || remainingSeconds == 8
                || remainingSeconds == 6 || remainingSeconds <= 5;
        }
        // Before that: every 15 seconds
        return remainingSeconds % 15 == 0;
    }

    public Map<String, GameRoom> roomMap() {
        return Map.copyOf(this.rooms);
    }

    private void clearRuntimeState() {
        this.playerRoomMembership.clear();
        this.exitCountdowns.clear();
        this.exitCountdownTableIds.clear();
        this.exitCountdownRoomIds.clear();
        this.warnedSeconds.clear();
    }

    private void removeCountdownState(UUID playerId) {
        this.exitCountdowns.remove(playerId);
        this.exitCountdownTableIds.remove(playerId);
        this.exitCountdownRoomIds.remove(playerId);
        this.warnedSeconds.remove(playerId);
    }

    private void logDebug(String message) {
        DebugService debug = this.debugSupplier.get();
        if (debug != null) {
            debug.log("gameroom", message);
        }
    }
}
