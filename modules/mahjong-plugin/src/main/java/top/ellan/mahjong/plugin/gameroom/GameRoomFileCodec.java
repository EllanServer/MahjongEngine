package top.ellan.mahjong.plugin.gameroom;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.serializer.NodeSerializer;
import net.momirealms.sparrow.yaml.serializer.NodeSerializers;

/** Strict Sparrow YAML codec; one instance is confined to one room registry. */
final class GameRoomFileCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int CODE_POINT_LIMIT = 2_000_000;

    private final SparrowYaml yaml = SparrowYaml.builder()
            .setAllowDuplicateKeys(false)
            .setAllowObjectKeys(false)
            .setCodePointLimit(CODE_POINT_LIMIT)
            .setMaxAliasesForCollections(0)
            .build();
    private final NodeSerializer<StoredRoom> roomSerializer = createRoomSerializer();
    private final NodeSerializer<Map<String, StoredRoom>> roomsSerializer =
            roomSerializer.mapOf();

    synchronized NavigableMap<String, GameRoom> read(Path storageFile) throws IOException {
        Objects.requireNonNull(storageFile, "storageFile");
        if (!Files.exists(storageFile)) {
            return new TreeMap<>();
        }
        YamlDocument document = yaml.load(storageFile);
        int version = document.get(Integer.class, "format-version");
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException(
                    "Unsupported game-room format version " + version + "; expected " + FORMAT_VERSION);
        }
        Map<String, StoredRoom> stored =
                document.getOrDefault(roomsSerializer, Map.of(), "rooms");
        TreeMap<String, GameRoom> result = new TreeMap<>();
        for (Map.Entry<String, StoredRoom> entry : stored.entrySet()) {
            GameRoom room = entry.getValue().toGameRoom(entry.getKey());
            if (result.putIfAbsent(room.id(), room) != null) {
                throw new IllegalStateException("Duplicate normalized game-room id: " + room.id());
            }
        }
        return result;
    }

    synchronized void write(Path storageFile, NavigableMap<String, GameRoom> rooms)
            throws IOException {
        Objects.requireNonNull(storageFile, "storageFile");
        Objects.requireNonNull(rooms, "rooms");

        YamlDocument document = yaml.load("");
        document.set(Integer.class, FORMAT_VERSION, "format-version");
        LinkedHashMap<String, StoredRoom> stored = new LinkedHashMap<>(rooms.size());
        rooms.forEach((id, room) -> stored.put(id, StoredRoom.from(room)));
        document.set(roomsSerializer, stored, "rooms");

        Path parent = storageFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
        try {
            document.save(temporary);
            try {
                Files.move(
                        temporary,
                        storageFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static NodeSerializer<StoredRoom> createRoomSerializer() {
        return NodeSerializers.mapping(StoredRoom.class)
                .group(
                        NodeSerializers.STRING.required("name").forGetter(StoredRoom::name),
                        NodeSerializers.UUID.required("world-id").forGetter(StoredRoom::worldId),
                        NodeSerializers.STRING.required("world-name").forGetter(StoredRoom::worldName),
                        NodeSerializers.INT.required("min-x").forGetter(StoredRoom::minX),
                        NodeSerializers.INT.required("min-y").forGetter(StoredRoom::minY),
                        NodeSerializers.INT.required("min-z").forGetter(StoredRoom::minZ),
                        NodeSerializers.INT.required("max-x").forGetter(StoredRoom::maxX),
                        NodeSerializers.INT.required("max-y").forGetter(StoredRoom::maxY),
                        NodeSerializers.INT.required("max-z").forGetter(StoredRoom::maxZ),
                        NodeSerializers.UUID.optional("owner").forGetter(StoredRoom::owner))
                .apply(StoredRoom::new);
    }

    private record StoredRoom(
            String name,
            UUID worldId,
            String worldName,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            Optional<UUID> owner) {
        private StoredRoom {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(owner, "owner");
        }

        private static StoredRoom from(GameRoom room) {
            Objects.requireNonNull(room, "room");
            return new StoredRoom(
                    room.name(),
                    room.worldId(),
                    room.worldName(),
                    room.minX(),
                    room.minY(),
                    room.minZ(),
                    room.maxX(),
                    room.maxY(),
                    room.maxZ(),
                    Optional.ofNullable(room.ownerId()));
        }

        private GameRoom toGameRoom(String id) {
            return new GameRoom(
                    id,
                    name,
                    worldId,
                    worldName,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    owner.orElse(null));
        }
    }
}
