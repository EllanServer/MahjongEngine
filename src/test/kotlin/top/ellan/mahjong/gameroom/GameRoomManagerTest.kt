package top.ellan.mahjong.gameroom

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import top.ellan.mahjong.config.PluginSettings
import top.ellan.mahjong.debug.DebugService
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.pluginSettings
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.core.MahjongTableManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameRoomManagerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var tableManager: MahjongTableManager
    private lateinit var debug: DebugService
    private lateinit var scheduler: ServerScheduler
    private lateinit var messages: MessageService

    @BeforeEach
    fun setUp() {
        tableManager = mock(MahjongTableManager::class.java)
        debug = mock(DebugService::class.java)
        scheduler = mock(ServerScheduler::class.java)
        messages = mock(MessageService::class.java)
    }

    @Test
    fun `load keeps persisted rooms available even when system is disabled`() {
        val settingsRef = AtomicReference(settings(enabled = false, file = "disabled-rooms.yml"))
        writeRoomsFile(
            tempDir.resolve("disabled-rooms.yml"),
            """
            rooms:
              main-hall:
                name: Main Hall
                world: world
                minX: 1
                minY: 60
                minZ: 2
                maxX: 10
                maxY: 75
                maxZ: 12
            """.trimIndent(),
        )
        val manager = createManager(settingsRef)

        manager.load()

        assertNotNull(manager.room("main-hall"))
        assertEquals("Main Hall", manager.room("main-hall")?.name())
    }

    @Test
    fun `refresh configuration switches storage file and clears runtime state when disabled`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000321")
        val settingsRef = AtomicReference(settings(enabled = true, file = "rooms-a.yml"))
        writeRoomsFile(
            tempDir.resolve("rooms-a.yml"),
            """
            rooms:
              alpha:
                name: Alpha
                world: world
                minX: 0
                minY: 60
                minZ: 0
                maxX: 5
                maxY: 70
                maxZ: 5
            """.trimIndent(),
        )
        writeRoomsFile(
            tempDir.resolve("rooms-b.yml"),
            """
            rooms:
              beta:
                name: Beta
                world: world_nether
                minX: 10
                minY: 50
                minZ: 10
                maxX: 20
                maxY: 65
                maxZ: 20
            """.trimIndent(),
        )
        val manager = createManager(settingsRef)
        manager.load()
        mutableMapField<UUID, String>(manager, "playerRoomMembership")[playerId] = "alpha"
        mutableMapField<UUID, Long>(manager, "exitCountdowns")[playerId] = 100L
        mutableMapField<UUID, String>(manager, "exitCountdownTableIds")[playerId] = "TABLE01"
        mutableMapField<UUID, String>(manager, "exitCountdownRoomIds")[playerId] = "alpha"
        mutableMapField<UUID, MutableSet<Int>>(manager, "warnedSeconds")[playerId] = linkedSetOf(10)

        settingsRef.set(settings(enabled = false, file = "rooms-b.yml"))
        manager.refreshConfiguration(tempDir.resolve("rooms-b.yml"))

        assertNull(manager.room("alpha"))
        assertNotNull(manager.room("beta"))
        assertTrue(manager.roomForPlayer(playerId).isEmpty())
        assertFalse(manager.hasActiveCountdown(playerId))
    }

    @Test
    fun `delete room clears countdowns tied to that room`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000654")
        val settingsRef = AtomicReference(settings())
        val manager = createManager(settingsRef)
        manager.createRoom(GameRoom("alpha", "Alpha", "world", 0, 60, 0, 5, 70, 5, null))
        mutableMapField<UUID, Long>(manager, "exitCountdowns")[playerId] = 100L
        mutableMapField<UUID, String>(manager, "exitCountdownTableIds")[playerId] = "TABLE02"
        mutableMapField<UUID, String>(manager, "exitCountdownRoomIds")[playerId] = "alpha"
        mutableMapField<UUID, MutableSet<Int>>(manager, "warnedSeconds")[playerId] = linkedSetOf(5)

        assertTrue(manager.deleteRoom("alpha"))

        assertFalse(manager.hasActiveCountdown(playerId))
    }

    private fun createManager(settingsRef: AtomicReference<PluginSettings>): GameRoomManager =
        GameRoomManager(
            tableManager,
            { debug },
            scheduler,
            messages,
            { settingsRef.get() },
            tempDir.resolve(settingsRef.get().gameRooms().file()),
        )

    private fun settings(
        enabled: Boolean = true,
        file: String = "game-rooms.yml",
    ): PluginSettings =
        pluginSettings(
            "gameRooms.enabled" to enabled,
            "gameRooms.file" to file,
        )

    private fun writeRoomsFile(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> mutableMapField(
        manager: GameRoomManager,
        fieldName: String,
    ): MutableMap<K, V> {
        val field = GameRoomManager::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(manager) as MutableMap<K, V>
    }
}
