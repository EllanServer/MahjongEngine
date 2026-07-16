package top.ellan.mahjong.table.runtime

import org.bukkit.Location
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.runtime.PluginTask
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableRuntimeServices
import java.util.UUID
import kotlin.test.Test

class SichuanBotPreparationSchedulerTest {
    @Test
    fun `dealer bot waits without retries while human dingque is pending`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val center = mock(Location::class.java)
        val eastBot = UUID.fromString("00000000-0000-0000-0000-00000000c010")
        val northBot = UUID.fromString("00000000-0000-0000-0000-00000000c011")
        val westBot = UUID.fromString("00000000-0000-0000-0000-00000000c012")
        val human = UUID.fromString("00000000-0000-0000-0000-00000000c013")
        val bots = listOf(eastBot, northBot, westBot)

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.players()).thenReturn(bots + human)
        bots.forEach { botId ->
            `when`(session.isBot(botId)).thenReturn(true)
            `when`(session.isSpectator(botId)).thenReturn(false)
            `when`(session.canChooseSichuanMissingSuit(botId)).thenReturn(false)
            `when`(session.isSichuanExchangePhase(botId)).thenReturn(false)
        }
        `when`(session.canChooseSichuanMissingSuit(human)).thenReturn(true)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(eastBot)
        `when`(session.isCurrentPlayer(eastBot)).thenReturn(false)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)

        repeat(12) {
            BotActionScheduler.schedule(session)
        }

        verifyNoInteractions(scheduler)
        verify(session, never()).setBotTask(any(PluginTask::class.java))
        verify(session, never()).gbCanWinByTsumo(eastBot)
        verify(session, never()).discard(eq(eastBot), any(Int::class.java))
    }

    @Test
    fun `preparation still schedules pending bot after pending human`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val human = UUID.fromString("00000000-0000-0000-0000-00000000c014")
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c015")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(human, botId))
        `when`(session.canChooseSichuanMissingSuit(human)).thenReturn(true)
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.isSpectator(botId)).thenReturn(false)
        `when`(session.canChooseSichuanMissingSuit(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(1L))).thenReturn(task)

        BotActionScheduler.schedule(session)

        verify(scheduler).runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(1L))
        verify(session).setBotTask(task)
    }

    @Test
    fun `stale turn task exits during preparation without retry`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c016")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.isSpectator(botId)).thenReturn(false)
        `when`(session.canChooseSichuanMissingSuit(botId)).thenReturn(false)
        `when`(session.isSichuanExchangePhase(botId)).thenReturn(false)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(botId)
        `when`(session.isCurrentPlayer(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(20L))).thenReturn(task)

        BotActionScheduler.schedule(session)
        `when`(session.isCurrentPlayer(botId)).thenReturn(false)
        runnableCaptor.value.run()

        verify(session, never()).gbCanWinByTsumo(botId)
        verify(session, never()).canSelectHandTile(eq(botId), any(Int::class.java))
        verify(scheduler, never()).runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(10L))
    }
}
