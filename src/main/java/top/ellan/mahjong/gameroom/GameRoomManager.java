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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.debug.DebugService;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.runtime.PluginTask;
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
    private final Map<UUID, PluginTask> exitCountdownTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> exitCountdownGenerations = new ConcurrentHashMap<>();
    private final AtomicLong countdownGeneration = new AtomicLong();

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
        long generation = this.countdownGeneration.incrementAndGet();
        this.exitCountdownGenerations.put(playerId, generation);
        this.scheduleCountdownCheck(playerId, generation);

        // Send warning message
        org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            this.scheduler.runEntity(player, () -> this.messages.send(
                player,
                "gameroom.leave_warning",
                this.messages.tag("seconds", String.valueOf(countdownSeconds)),
                this.messages.tag("room_name", room == null ? "" : room.name())
            ));
        }
        this.logDebug("Player " + playerId + " left room during match, countdown started (" + countdownSeconds + "s)");
    }

    private void cancelExitCountdown(UUID playerId) {
        Long removed = this.exitCountdowns.get(playerId);
        this.removeCountdownState(playerId);
        if (removed != null) {
            org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                this.scheduler.runEntity(
                    player,
                    () -> this.messages.send(player, "gameroom.countdown_cancelled")
                );
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

    private void scheduleCountdownCheck(UUID playerId, long generation) {
        AtomicReference<PluginTask> self = new AtomicReference<>();
        PluginTask next = this.scheduler.runGlobalDelayed(
            () -> this.checkCountdown(playerId, generation, self.get()),
            20L
        );
        self.set(next);
        PluginTask previous = this.exitCountdownTasks.put(playerId, next);
        if (previous != null && previous != next) {
            previous.cancel();
        }
        if (!Objects.equals(this.exitCountdownGenerations.get(playerId), generation)
            && this.exitCountdownTasks.remove(playerId, next)) {
            next.cancel();
        }
    }

    private void checkCountdown(UUID playerId, long generation, PluginTask expectedTask) {
        this.exitCountdownTasks.remove(playerId, expectedTask);
        if (!Objects.equals(this.exitCountdownGenerations.get(playerId), generation)) {
            return;
        }
        if (!this.isEnabled()) {
            this.removeCountdownState(playerId);
            return;
        }
        Long deadlineValue = this.exitCountdowns.get(playerId);
        if (deadlineValue == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long deadline = deadlineValue;
        if (now >= deadline) {
            String tableId = this.exitCountdownTableIds.get(playerId);
            if (tableId != null) {
                this.logDebug("Player " + playerId + " countdown expired, force-ending table " + tableId);
                MahjongTableSession session = this.tableManager.resolveTableById(tableId);
                if (session != null && (session.isStarted() || session.contains(playerId))) {
                    this.scheduler.runRegion(session.center(), () -> {
                        this.tableManager.forceEndTable(tableId);
                        this.tableManager.removePlayerFromTableWithoutMove(playerId);
                    });
                }
            }
            this.removeCountdownState(playerId);
            return;
        }
        int remaining = (int) ((deadline - now) / 1000L);
        if (isCountdownWarningSecond(remaining)) {
            java.util.Set<Integer> warned = this.warnedSeconds.computeIfAbsent(
                playerId,
                ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()
            );
            if (warned.add(remaining)) {
                org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    this.scheduler.runEntity(player, () -> this.messages.send(
                        player,
                        "gameroom.countdown",
                        this.messages.tag("seconds", String.valueOf(remaining))
                    ));
                }
            }
        }
        if (Objects.equals(this.exitCountdownGenerations.get(playerId), generation)) {
            this.scheduleCountdownCheck(playerId, generation);
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
        this.exitCountdownTasks.values().forEach(PluginTask::cancel);
        this.exitCountdownTasks.clear();
        this.exitCountdowns.clear();
        this.exitCountdownTableIds.clear();
        this.exitCountdownRoomIds.clear();
        this.warnedSeconds.clear();
        this.exitCountdownGenerations.clear();
    }

    private void removeCountdownState(UUID playerId) {
        PluginTask task = this.exitCountdownTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        this.exitCountdowns.remove(playerId);
        this.exitCountdownTableIds.remove(playerId);
        this.exitCountdownRoomIds.remove(playerId);
        this.warnedSeconds.remove(playerId);
        this.exitCountdownGenerations.remove(playerId);
    }

    public void shutdown() {
        this.clearRuntimeState();
    }

    private void logDebug(String message) {
        DebugService debug = this.debugSupplier.get();
        if (debug != null) {
            debug.log("gameroom", message);
        }
    }
}
