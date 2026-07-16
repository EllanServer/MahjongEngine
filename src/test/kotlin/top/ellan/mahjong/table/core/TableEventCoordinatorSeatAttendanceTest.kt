package top.ellan.mahjong.table.core

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.config.PluginSettings
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.display.DisplayClickAction
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.runtime.TableOverheadViewCoordinator
import top.ellan.mahjong.table.runtime.TableSeatCoordinator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class TableEventCoordinatorSeatAttendanceTest {
    private val playerId = UUID.fromString("00000000-0000-0000-0000-00000000d002")

    @Test
    fun `ordinary shift is not interpreted as leaving the seat`() {
        val fixture = fixture()
        val event = mock(PlayerToggleSneakEvent::class.java)
        `when`(event.isSneaking).thenReturn(true)
        `when`(event.player).thenReturn(fixture.player)

        fixture.coordinator.onSeatSneak(event)

        verify(fixture.manager, never()).leave(playerId)
        verify(fixture.session, never()).setPlayerUnattended(playerId, true)
    }

    @Test
    fun `dismount delegates only after the player remains away from the correct seat`() {
        val fixture = fixture()
        val event = SeatDismountEvent(fixture.player, fixture.vehicle)
        val delayed = ArgumentCaptor.forClass(Runnable::class.java)

        fixture.coordinator.onSeatDismount(event)

        assertTrue(event.isCancelled)
        verify(fixture.manager, never()).leave(playerId)
        verify(fixture.scheduler).runEntityDelayed(eq(fixture.player), delayed.capture(), eq(8L))

        delayed.value.run()

        verify(fixture.session).setPlayerUnattended(playerId, true)
        verify(fixture.manager, never()).leave(playerId)
    }

    @Test
    fun `mounting the same seat clears delegation and invalidates an older absence check`() {
        val fixture = fixture()
        val delayed = ArgumentCaptor.forClass(Runnable::class.java)
        fixture.coordinator.onSeatDismount(SeatDismountEvent(fixture.player, fixture.vehicle))
        verify(fixture.scheduler).runEntityDelayed(eq(fixture.player), delayed.capture(), eq(8L))

        `when`(fixture.seats.seatAction(fixture.vehicle))
            .thenReturn(DisplayClickAction.joinSeat("TABLE-A", SeatWind.EAST))
        fixture.coordinator.onSeatMount(SeatMountEvent(fixture.player, fixture.vehicle))
        delayed.value.run()

        verify(fixture.session).setPlayerUnattended(playerId, false)
        verify(fixture.session, never()).setPlayerUnattended(playerId, true)
        verify(fixture.manager, never()).leave(playerId)
    }

    private fun fixture(): Fixture {
        val manager = mock(MahjongTableManager::class.java)
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val settings = mock(PluginSettings::class.java)
        val seats = mock(TableSeatCoordinator::class.java)
        val overhead = mock(TableOverheadViewCoordinator::class.java)
        val player = mock(Player::class.java)
        val vehicle = mock(Entity::class.java)

        `when`(player.uniqueId).thenReturn(playerId)
        `when`(player.isOnline).thenReturn(true)
        `when`(player.isInsideVehicle).thenReturn(false)
        `when`(manager.tableFor(playerId)).thenReturn(session)
        `when`(manager.pluginRef()).thenReturn(plugin)
        `when`(manager.seatCoordinatorRef()).thenReturn(seats)
        `when`(manager.overheadViewCoordinatorRef()).thenReturn(overhead)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(plugin.settings()).thenReturn(settings)
        `when`(settings.tableFreeMoveDuringRound()).thenReturn(false)
        `when`(session.id()).thenReturn("TABLE-A")
        `when`(session.seatOf(playerId)).thenReturn(SeatWind.EAST)
        `when`(session.isStarted()).thenReturn(true)

        return Fixture(
            manager,
            session,
            scheduler,
            seats,
            player,
            vehicle,
            TableEventCoordinator(manager),
        )
    }

    private data class Fixture(
        val manager: MahjongTableManager,
        val session: MahjongTableSession,
        val scheduler: ServerScheduler,
        val seats: TableSeatCoordinator,
        val player: Player,
        val vehicle: Entity,
        val coordinator: TableEventCoordinator,
    )

    class SeatMountEvent(
        private val player: Player,
        private val mount: Entity,
    ) : Event() {
        fun getEntity(): Entity = player

        fun getMount(): Entity = mount

        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            @JvmField
            val HANDLERS = HandlerList()

            @JvmStatic
            fun getHandlerList(): HandlerList = HANDLERS
        }
    }

    class SeatDismountEvent(
        private val player: Player,
        private val dismounted: Entity,
    ) : Event(),
        Cancellable {
        private var cancelled = false

        fun getEntity(): Entity = player

        fun getDismounted(): Entity = dismounted

        override fun isCancelled(): Boolean = cancelled

        override fun setCancelled(cancel: Boolean) {
            cancelled = cancel
        }

        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            @JvmField
            val HANDLERS = HandlerList()

            @JvmStatic
            fun getHandlerList(): HandlerList = HANDLERS
        }
    }
}
