package top.ellan.mahjong.plugin.gameroom;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;

/** Durable room catalog with immutable chunk-bucket snapshots for hot-path point queries. */
public final class GameRoomRegistry {
    private static final int MAX_ROOMS = 1_024;
    private static final int COMMAND_PAGE_SIZE_LIMIT = 50;
    private static final int COMPLETION_LIMIT = 50;

    private final Path storageFile;
    private final Executor ioExecutor;
    private final GameRoomFileCodec fileCodec = new GameRoomFileCodec();
    private final Object mutationLock = new Object();
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(Snapshot.empty());

    public GameRoomRegistry(Path storageFile, Executor ioExecutor) {
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile")
                .toAbsolutePath()
                .normalize();
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /** Startup-only blocking load; MahjongRuntime invokes it on its bounded I/O executor. */
    public void load() {
        synchronized (mutationLock) {
            current.set(Snapshot.build(readRooms()));
        }
    }

    public CompletionStage<Void> reload() {
        return CompletableFuture.runAsync(this::load, ioExecutor);
    }

    public Optional<GameRoom> find(String id) {
        try {
            return Optional.ofNullable(current.get().roomsById().get(GameRoom.normalizeId(id)));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public Optional<GameRoom> roomAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        List<GameRoom> candidates = current.get()
                .roomsByChunk()
                .get(new RoomCell(location.getWorld().getUID(), x >> 4, z >> 4));
        if (candidates == null) {
            return Optional.empty();
        }
        return candidates.stream().filter(room -> room.contains(x, y, z)).findFirst();
    }

    /** O(bucket-size) check for a finite table box; a valid room necessarily contains its center. */
    public Optional<GameRoom> roomContainingBox(
            Location center, int horizontalRadius, int height) {
        if (center == null
                || center.getWorld() == null
                || horizontalRadius < 0
                || height < 1) {
            return Optional.empty();
        }
        int centerX = center.getBlockX();
        int minY = center.getBlockY();
        int centerZ = center.getBlockZ();
        List<GameRoom> candidates = current.get()
                .roomsByChunk()
                .get(
                        new RoomCell(
                                center.getWorld().getUID(),
                                centerX >> 4,
                                centerZ >> 4));
        if (candidates == null) {
            return Optional.empty();
        }
        int minX = centerX - horizontalRadius;
        int minZ = centerZ - horizontalRadius;
        int maxX = centerX + horizontalRadius;
        int maxY;
        try {
            maxY = Math.addExact(minY, height - 1);
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        int maxZ = centerZ + horizontalRadius;
        return candidates.stream()
                .filter(
                        room ->
                                room.contains(minX, minY, minZ)
                                        && room.contains(maxX, maxY, maxZ))
                .findFirst();
    }

    public CompletionStage<Boolean> create(GameRoom room) {
        Objects.requireNonNull(room, "room");
        return CompletableFuture.supplyAsync(
                () -> {
                    synchronized (mutationLock) {
                        Snapshot snapshot = current.get();
                        if (snapshot.roomsById().containsKey(room.id())) {
                            return false;
                        }
                        if (snapshot.roomsById().size() >= MAX_ROOMS) {
                            throw new IllegalStateException("Game-room limit reached");
                        }
                        TreeMap<String, GameRoom> updated =
                                new TreeMap<>(snapshot.roomsById());
                        updated.put(room.id(), room);
                        writeRooms(updated);
                        current.set(Snapshot.build(updated));
                        return true;
                    }
                },
                ioExecutor);
    }

    public CompletionStage<Boolean> delete(String id) {
        String normalized = GameRoom.normalizeId(id);
        return CompletableFuture.supplyAsync(
                () -> {
                    synchronized (mutationLock) {
                        Snapshot snapshot = current.get();
                        if (!snapshot.roomsById().containsKey(normalized)) {
                            return false;
                        }
                        TreeMap<String, GameRoom> updated =
                                new TreeMap<>(snapshot.roomsById());
                        updated.remove(normalized);
                        writeRooms(updated);
                        current.set(Snapshot.build(updated));
                        return true;
                    }
                },
                ioExecutor);
    }

    public RoomPage page(int requestedPage, int pageSize) {
        if (requestedPage < 1 || pageSize < 1 || pageSize > COMMAND_PAGE_SIZE_LIMIT) {
            throw new IllegalArgumentException("Invalid room page request");
        }
        List<GameRoom> ordered = current.get().orderedRooms();
        int pageCount = Math.max(1, (ordered.size() + pageSize - 1) / pageSize);
        int page = Math.min(requestedPage, pageCount);
        int start = Math.min((page - 1) * pageSize, ordered.size());
        int end = Math.min(start + pageSize, ordered.size());
        return new RoomPage(page, pageCount, ordered.size(), ordered.subList(start, end));
    }

    /** Prefix navigation reads at most 50 sorted IDs and never copies the full catalog. */
    public List<String> completeIds(String rawPrefix) {
        String prefix = Objects.requireNonNull(rawPrefix, "rawPrefix").trim().toLowerCase(java.util.Locale.ROOT);
        if (!prefix.matches("[a-z0-9_-]{0,32}")) {
            return List.of();
        }
        NavigableMap<String, GameRoom> rooms = current.get().roomsById();
        ArrayList<String> result = new ArrayList<>(Math.min(COMPLETION_LIMIT, rooms.size()));
        Map.Entry<String, GameRoom> entry = rooms.ceilingEntry(prefix);
        while (entry != null
                && entry.getKey().startsWith(prefix)
                && result.size() < COMPLETION_LIMIT) {
            result.add(entry.getKey());
            entry = rooms.higherEntry(entry.getKey());
        }
        return List.copyOf(result);
    }

    public int size() {
        return current.get().roomsById().size();
    }

    private NavigableMap<String, GameRoom> readRooms() {
        try {
            NavigableMap<String, GameRoom> rooms = fileCodec.read(storageFile);
            if (rooms.size() > MAX_ROOMS) {
                throw new IllegalStateException("Game-room limit exceeded");
            }
            return rooms;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load game rooms", failure);
        }
    }

    private void writeRooms(NavigableMap<String, GameRoom> rooms) {
        try {
            fileCodec.write(storageFile, rooms);
        } catch (IOException failure) {
            throw new CompletionException("Cannot save game rooms", failure);
        }
    }

    public record RoomPage(int page, int pageCount, int total, List<GameRoom> rooms) {
        public RoomPage {
            rooms = List.copyOf(Objects.requireNonNull(rooms, "rooms"));
        }
    }

    private record RoomCell(UUID worldId, int chunkX, int chunkZ) {}

    private record Snapshot(
            NavigableMap<String, GameRoom> roomsById,
            List<GameRoom> orderedRooms,
            Map<RoomCell, List<GameRoom>> roomsByChunk) {
        private Snapshot {
            roomsById = java.util.Collections.unmodifiableNavigableMap(
                    new TreeMap<>(Objects.requireNonNull(roomsById, "roomsById")));
            orderedRooms = List.copyOf(Objects.requireNonNull(orderedRooms, "orderedRooms"));
            roomsByChunk = Map.copyOf(Objects.requireNonNull(roomsByChunk, "roomsByChunk"));
        }

        private static Snapshot empty() {
            return new Snapshot(new TreeMap<>(), List.of(), Map.of());
        }

        private static Snapshot build(Map<String, GameRoom> source) {
            TreeMap<String, GameRoom> sorted = new TreeMap<>(source);
            HashMap<RoomCell, ArrayList<GameRoom>> mutableIndex = new HashMap<>();
            for (GameRoom room : sorted.values()) {
                for (int chunkX = room.minX() >> 4; chunkX <= room.maxX() >> 4; chunkX++) {
                    for (int chunkZ = room.minZ() >> 4;
                            chunkZ <= room.maxZ() >> 4;
                            chunkZ++) {
                        mutableIndex
                                .computeIfAbsent(
                                        new RoomCell(room.worldId(), chunkX, chunkZ),
                                        ignored -> new ArrayList<>())
                                .add(room);
                    }
                }
            }
            HashMap<RoomCell, List<GameRoom>> immutableIndex = new HashMap<>();
            mutableIndex.forEach((cell, rooms) -> immutableIndex.put(cell, List.copyOf(rooms)));
            return new Snapshot(sorted, List.copyOf(sorted.values()), immutableIndex);
        }
    }
}
