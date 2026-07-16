package top.ellan.mahjong.table.runtime

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.compat.CraftEngineService
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.runtime.PluginTask
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.core.MahjongTableManager
import top.ellan.mahjong.table.core.MahjongTableSession
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableSeatCoordinatorTest {
    @Test
    fun `starting seat watchdog no longer depends on Bukkit current tick`() {
        val tableManager = mock(MahjongTableManager::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val session = mock(MahjongTableSession::class.java)
        val coordinator = TableSeatCoordinator({ null }, scheduler, tableManager)
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000301")

        `when`(scheduler.runGlobalTimer(Mockito.any(Runnable::class.java), Mockito.anyLong(), Mockito.anyLong())).thenReturn(task)
        `when`(task.isCancelled()).thenReturn(false)
        `when`(session.id()).thenReturn("TABLE01")

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Int> { Bukkit.getCurrentTick() }.thenThrow(IllegalStateException("No currently ticking region"))

            coordinator.startSeatWatchdog(session, playerId, SeatWind.EAST, 40L)
        }

        verify(scheduler, times(1)).runGlobalTimer(Mockito.any(Runnable::class.java), Mockito.eq(1L), Mockito.eq(2L))
        assertEquals(1, seatWatchdogs(coordinator).size)
    }

    @Test
    fun `seat watchdog expires after configured logical duration without Bukkit tick access`() {
        val tableManager = mock(MahjongTableManager::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val session = mock(MahjongTableSession::class.java)
        val player = mock(Player::class.java)
        val coordinator = TableSeatCoordinator({ null }, scheduler, tableManager)
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000302")

        `when`(scheduler.runGlobalTimer(Mockito.any(Runnable::class.java), Mockito.anyLong(), Mockito.anyLong())).thenReturn(task)
        `when`(task.isCancelled()).thenReturn(false)
        `when`(session.id()).thenReturn("TABLE02")
        `when`(tableManager.resolveTableById("TABLE02")).thenReturn(session)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.seatOf(playerId)).thenReturn(SeatWind.SOUTH)
        `when`(player.isOnline).thenReturn(true)

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Player?> { Bukkit.getPlayer(playerId) }.thenReturn(player)

            coordinator.startSeatWatchdog(session, playerId, SeatWind.SOUTH, 4L)
            invokeRunSeatWatchdogs(coordinator)
            assertEquals(1, seatWatchdogs(coordinator).size)

            invokeRunSeatWatchdogs(coordinator)
            assertEquals(1, seatWatchdogs(coordinator).size)

            invokeRunSeatWatchdogs(coordinator)
            assertTrue(seatWatchdogs(coordinator).isEmpty())
        }
    }

    @Test
    fun `seat watchdog inspects players on entity scheduler instead of global thread`() {
        val tableManager = mock(MahjongTableManager::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val session = mock(MahjongTableSession::class.java)
        val player = mock(Player::class.java)
        val coordinator = TableSeatCoordinator({ null }, scheduler, tableManager)
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000303")

        `when`(scheduler.runGlobalTimer(Mockito.any(Runnable::class.java), Mockito.anyLong(), Mockito.anyLong())).thenReturn(task)
        `when`(scheduler.runEntity(Mockito.eq(player), Mockito.any(Runnable::class.java))).thenReturn(task)
        `when`(task.isCancelled()).thenReturn(false)
        `when`(session.id()).thenReturn("TABLE03")
        `when`(tableManager.resolveTableById("TABLE03")).thenReturn(session)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.seatOf(playerId)).thenReturn(SeatWind.WEST)
        `when`(player.isOnline).thenReturn(true)

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Player?> { Bukkit.getPlayer(playerId) }.thenReturn(player)

            coordinator.startSeatWatchdog(session, playerId, SeatWind.WEST, 40L)
            invokeRunSeatWatchdogs(coordinator)
        }

        verify(scheduler, times(1)).runEntity(Mockito.eq(player), Mockito.any(Runnable::class.java))
    }

    @Test
    fun `seat restore is scheduled on player entity thread`() {
        val tableManager = mock(MahjongTableManager::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val session = mock(MahjongTableSession::class.java)
        val player = mock(Player::class.java)
        val craftEngine = mock(CraftEngineService::class.java)
        val coordinator = TableSeatCoordinator({ craftEngine }, scheduler, tableManager)

        `when`(scheduler.runEntity(Mockito.eq(player), Mockito.any(Runnable::class.java))).thenReturn(task)
        `when`(player.uniqueId).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000304"))

        coordinator.requestSeatRestore(player, session, SeatWind.NORTH)

        verify(scheduler, times(1)).runEntity(Mockito.eq(player), Mockito.any(Runnable::class.java))
        verify(scheduler, never()).runRegion(Mockito.any(), Mockito.any(Runnable::class.java))
    }

    @Test
    fun `seat exit places player feet on the nearby floor collision top`() {
        val world = mock(World::class.java)
        val support = mock(Block::class.java)
        val probe = Location(world, 10.25, 75.0, 20.75)

        `when`(world.minHeight).thenReturn(-64)
        `when`(world.maxHeight).thenReturn(320)
        `when`(world.getBlockAt(10, 74, 20)).thenReturn(support)
        `when`(support.isPassable).thenReturn(false)
        `when`(support.isLiquid).thenReturn(false)
        `when`(support.boundingBox).thenReturn(BoundingBox(10.0, 74.0, 20.0, 11.0, 75.0, 21.0))

        val exit = TableSeatCoordinator.groundedSeatExit(probe, 75)

        assertEquals(75.02, exit.y, 0.000001)
        assertEquals(probe.x, exit.x)
        assertEquals(probe.z, exit.z)
    }

    @Suppress("UNCHECKED_CAST")
    private fun seatWatchdogs(coordinator: TableSeatCoordinator): MutableMap<UUID, Any?> {
        val field = coordinator.javaClass.getDeclaredField("seatWatchdogs")
        field.isAccessible = true
        return field.get(coordinator) as MutableMap<UUID, Any?>
    }

    private fun invokeRunSeatWatchdogs(coordinator: TableSeatCoordinator) {
        val method = coordinator.javaClass.getDeclaredMethod("runSeatWatchdogs")
        method.isAccessible = true
        method.invoke(coordinator)
    }
}
