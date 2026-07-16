package top.ellan.mahjong.gameroom

import org.bukkit.Location
import org.bukkit.World
import org.mockito.Mockito.mock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameRoomSelectionServiceTest {
    private val playerId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123")

    @Test
    fun `preview outlines a single selected block before both corners are placed`() {
        val world = mock(World::class.java)
        val service = GameRoomSelectionService()

        service.setFirst(playerId, Location(world, 10.0, 64.0, -3.0))

        val points = service.previewPoints(playerId, Location(world, 10.5, 65.0, -2.5))

        assertEquals(8, points.size)
        assertTrue(points.any { it.x == 10.0 && it.y == 64.0 && it.z == -3.0 })
        assertTrue(points.any { it.x == 11.0 && it.y == 65.0 && it.z == -2.0 })
    }

    @Test
    fun `preview outlines complete cuboid using inclusive block bounds`() {
        val world = mock(World::class.java)
        val service = GameRoomSelectionService()

        service.setFirst(playerId, Location(world, 0.0, 64.0, 0.0))
        service.setSecond(playerId, Location(world, 2.0, 65.0, 1.0))

        val points = service.previewPoints(playerId, Location(world, 1.0, 64.0, 1.0))

        assertTrue(points.size > 8)
        assertTrue(points.any { it.x == 0.0 && it.y == 64.0 && it.z == 0.0 })
        assertTrue(points.any { it.x == 3.0 && it.y == 66.0 && it.z == 2.0 })
    }

    @Test
    fun `preview does not draw misleading bounds for corners in different worlds`() {
        val service = GameRoomSelectionService()

        service.setFirst(playerId, Location(mock(World::class.java), 0.0, 64.0, 0.0))
        service.setSecond(playerId, Location(mock(World::class.java), 5.0, 70.0, 5.0))

        assertTrue(service.previewPoints(playerId, null).isEmpty())
    }

    @Test
    fun `preview caps huge selections by increasing point spacing`() {
        val world = mock(World::class.java)
        val service = GameRoomSelectionService()

        service.setFirst(playerId, Location(world, 0.0, 0.0, 0.0))
        service.setSecond(playerId, Location(world, 1000.0, 1000.0, 1000.0))

        val points = service.previewPoints(playerId, null)

        assertTrue(points.size <= 600)
        assertTrue(points.any { it.x == 0.0 && it.y == 0.0 && it.z == 0.0 })
        assertTrue(points.any { it.x == 1001.0 && it.y == 1001.0 && it.z == 1001.0 })
    }
}
