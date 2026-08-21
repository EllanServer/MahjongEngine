package top.ellan.mahjong.plugin.gameroom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameRoomFileCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsCurrentSchemaAndNullableOwnerAtomically() throws Exception {
        Path file = temporaryDirectory.resolve("game-rooms.yml");
        UUID world = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID owner = UUID.fromString("22222222-2222-2222-2222-222222222222");
        GameRoom east = new GameRoom("east", "East Room", world, "world", -8, 60, -8, 8, 80, 8, owner);
        GameRoom west = new GameRoom("west", "West Room", world, "world", 16, 60, 16, 32, 80, 32, null);
        NavigableMap<String, GameRoom> rooms = new TreeMap<>();
        rooms.put(west.id(), west);
        rooms.put(east.id(), east);

        GameRoomFileCodec codec = new GameRoomFileCodec();
        codec.write(file, rooms);

        assertEquals(rooms, codec.read(file));
        assertFalse(Files.exists(temporaryDirectory.resolve("game-rooms.yml.tmp")));
        String yaml = Files.readString(file);
        assertTrue(yaml.startsWith("format-version: 1\nrooms:\n  east:"));
        assertTrue(yaml.contains("owner: 22222222-2222-2222-2222-222222222222"));
    }

    @Test
    void rejectsUnknownOrMissingFormatVersion() throws Exception {
        Path unknown = temporaryDirectory.resolve("unknown.yml");
        Files.writeString(unknown, "format-version: 2\nrooms: {}\n");
        Path missing = temporaryDirectory.resolve("missing.yml");
        Files.writeString(missing, "rooms: {}\n");
        GameRoomFileCodec codec = new GameRoomFileCodec();

        assertThrows(IllegalStateException.class, () -> codec.read(unknown));
        assertThrows(RuntimeException.class, () -> codec.read(missing));
    }

    @Test
    void rejectsYamlAliasesAndDuplicateKeys() throws Exception {
        Path duplicate = temporaryDirectory.resolve("duplicate-key.yml");
        Files.writeString(
                duplicate,
                "format-version: 1\nformat-version: 1\nrooms: {}\n");
        Path alias = temporaryDirectory.resolve("alias.yml");
        Files.writeString(
                alias,
                """
                format-version: 1
                rooms:
                  east: &room
                    name: Room
                    world-id: 11111111-1111-1111-1111-111111111111
                    world-name: world
                    min-x: 0
                    min-y: 60
                    min-z: 0
                    max-x: 10
                    max-y: 70
                    max-z: 10
                    owner: null
                  west: *room
                """);
        GameRoomFileCodec codec = new GameRoomFileCodec();

        assertThrows(RuntimeException.class, () -> codec.read(duplicate));
        assertThrows(RuntimeException.class, () -> codec.read(alias));
    }

    @Test
    void rejectsIdsThatCollideAfterCanonicalization() throws Exception {
        Path file = temporaryDirectory.resolve("duplicates.yml");
        String room = """
                name: Room
                world-id: 11111111-1111-1111-1111-111111111111
                world-name: world
                min-x: 0
                min-y: 60
                min-z: 0
                max-x: 10
                max-y: 70
                max-z: 10
                owner: null
                """;
        Files.writeString(
                file,
                "format-version: 1\nrooms:\n  East:\n"
                        + room.indent(4)
                        + "  east:\n"
                        + room.indent(4));

        assertThrows(IllegalStateException.class, () -> new GameRoomFileCodec().read(file));
    }
}
