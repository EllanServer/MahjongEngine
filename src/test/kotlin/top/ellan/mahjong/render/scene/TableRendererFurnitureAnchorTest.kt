package top.ellan.mahjong.render.scene

import org.bukkit.Location
import org.bukkit.World
import org.mockito.Mockito.mock
import top.ellan.mahjong.model.SeatWind
import kotlin.test.Test
import kotlin.test.assertEquals

class TableRendererFurnitureAnchorTest {
    private val world: World = mock(World::class.java)

    @Test
    fun `custom table furniture uses standard craftengine anchor`() {
        val tableCenter = Location(world, 10.0, 64.52, -3.0)

        val anchor = TableRenderer.tableFurnitureAnchor(tableCenter, "custom:table")

        assertEquals(10.0, anchor.x, 0.000001)
        assertEquals(63.52, anchor.y, 0.000001)
        assertEquals(-3.0, anchor.z, 0.000001)
    }

    @Test
    fun `built in table furniture keeps legacy anchor`() {
        val tableCenter = Location(world, 10.0, 64.52, -3.0)

        val anchor = TableRenderer.tableFurnitureAnchor(tableCenter, "mahjongpaper:table_visual")

        assertEquals(10.0, anchor.x, 0.000001)
        assertEquals(64.895, anchor.y, 0.000001)
        assertEquals(-3.0, anchor.z, 0.000001)
    }

    @Test
    fun `custom seat furniture uses standard craftengine anchor`() {
        val seatBase = Location(world, 2.0, 64.9, 5.0)

        val anchor = TableRenderer.seatFurnitureAnchor(seatBase, SeatWind.EAST, "custom:chair")

        assertEquals(2.0, anchor.x, 0.000001)
        assertEquals(63.525, anchor.y, 0.000001)
        assertEquals(5.0, anchor.z, 0.000001)
        assertEquals(90.0f, anchor.yaw)
    }
}
