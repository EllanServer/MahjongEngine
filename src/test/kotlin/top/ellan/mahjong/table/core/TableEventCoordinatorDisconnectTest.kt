package top.ellan.mahjong.table.core

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.runtime.TableOverheadViewCoordinator
import top.ellan.mahjong.table.runtime.TableSeatCoordinator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableEventCoordinatorDisconnectTest {
    private val playerId = UUID.fromString("00000000-0000-0000-0000-00000000d001")

    @Test
    fun `disconnect retains active seat and reconnect removes unattended delegation`() {
        val manager = mock(MahjongTableManager::class.java)
        val session = mock(MahjongTableSession::class.java)
        val coordinator = TableEventCoordinator(manager)
        `when`(manager.tableFor(playerId)).thenReturn(session)
        `when`(session.isStarted()).thenReturn(true)

        assertTrue(coordinator.retainActiveSeatForDisconnect(playerId))
        verify(session).setPlayerUnattended(playerId, true)

        coordinator.restoreConnectedSeat(playerId)
        verify(session).setPlayerUnattended(playerId, false)
    }

    @Test
    fun `disconnect does not retain an inactive seat`() {
        val manager = mock(MahjongTableManager::class.java)
        val session = mock(MahjongTableSession::class.java)
        val coordinator = TableEventCoordinator(manager)
        `when`(manager.tableFor(playerId)).thenReturn(session)
        `when`(session.isStarted()).thenReturn(false)
        `when`(session.isRoundStartInProgress()).thenReturn(false)

        assertFalse(coordinator.retainActiveSeatForDisconnect(playerId))
        verify(session, never()).setPlayerUnattended(playerId, true)
    }

    @Test
    fun `reconnect immediately refreshes the retained viewers current actions on the table region`() {
        val manager = mock(MahjongTableManager::class.java)
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val player = mock(Player::class.java)
        val event = mock(PlayerJoinEvent::class.java)
        val center = Location(mock(World::class.java), 0.0, 64.0, 0.0)
        val coordinator = TableEventCoordinator(manager)
        `when`(event.player).thenReturn(player)
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(manager.tableFor(playerId)).thenReturn(session)
        `when`(manager.pluginRef()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        doAnswer { invocation ->
            invocation.getArgument<Runnable>(1).run()
            null
        }.`when`(scheduler).runRegion(eq(center), any(Runnable::class.java))

        coordinator.onJoin(event)

        verify(session).setPlayerUnattended(playerId, false)
        verify(session).flushViewerActionsNow(playerId)
    }

    @Test
    fun `paired overhead exit events restore the human seat without becoming a leave action`() {
        val manager = mock(MahjongTableManager::class.java)
        val session = mock(MahjongTableSession::class.java)
        val overheadViews = mock(TableOverheadViewCoordinator::class.java)
        val seats = mock(TableSeatCoordinator::class.java)
        val player = mock(Player::class.java)
        val coordinator = TableEventCoordinator(manager)

        `when`(player.uniqueId).thenReturn(playerId)
        `when`(manager.overheadViewCoordinatorRef()).thenReturn(overheadViews)
        `when`(manager.seatCoordinatorRef()).thenReturn(seats)
        `when`(manager.tableFor(playerId)).thenReturn(session)
        `when`(session.seatOf(playerId)).thenReturn(SeatWind.EAST)
        `when`(session.isStarted()).thenReturn(true)
        `when`(overheadViews.exit(player, true)).thenReturn(true, false)

        assertTrue(coordinator.exitOverheadAndRestoreSeat(player))
        assertTrue(coordinator.exitOverheadAndRestoreSeat(player))

        verify(session, times(2)).setPlayerUnattended(playerId, false)
        verify(seats, times(2)).startSeatWatchdog(session, playerId, SeatWind.EAST)
        verify(seats, times(2)).requestSeatRestore(player, session, SeatWind.EAST)
        verify(manager, never()).leave(playerId)
    }

    @Test
    fun `shift exits overhead restores the seat and never becomes a leave action`() {
        val manager = mock(MahjongTableManager::class.java)
        val session = mock(MahjongTableSession::class.java)
        val overheadViews = mock(TableOverheadViewCoordinator::class.java)
        val seats = mock(TableSeatCoordinator::class.java)
        val player = mock(Player::class.java)
        val event = mock(PlayerToggleSneakEvent::class.java)
        val coordinator = TableEventCoordinator(manager)

        `when`(event.isSneaking).thenReturn(true)
        `when`(event.player).thenReturn(player)
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(manager.overheadViewCoordinatorRef()).thenReturn(overheadViews)
        `when`(manager.seatCoordinatorRef()).thenReturn(seats)
        `when`(manager.tableFor(playerId)).thenReturn(session)
        `when`(session.seatOf(playerId)).thenReturn(SeatWind.EAST)
        `when`(session.isStarted()).thenReturn(true)
        `when`(overheadViews.exit(player, true)).thenReturn(true)

        coordinator.onSeatSneak(event)

        verify(overheadViews).exit(player, true)
        verify(event).isCancelled = true
        verify(session).setPlayerUnattended(playerId, false)
        verify(seats).startSeatWatchdog(session, playerId, SeatWind.EAST)
        verify(seats).requestSeatRestore(player, session, SeatWind.EAST)
        verify(manager, never()).leave(playerId)
    }
}
