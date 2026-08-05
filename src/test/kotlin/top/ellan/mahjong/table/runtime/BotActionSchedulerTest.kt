package top.ellan.mahjong.table.runtime

import org.bukkit.Location
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.runtime.PluginTask
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.action.PlayerActionSnapshotFactory
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableRuntimeServices
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotActionSchedulerTest {
    companion object {
        private const val MAX_GB_TURN_RETRY_ATTEMPTS = 8
        private const val MAX_SICHUAN_TURN_RETRY_ATTEMPTS = 8
    }

    @Test
    fun `non riichi variant uses gb style bot scheduling`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000b001")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        stubGbTurnSnapshot(session, botId)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(20L))).thenReturn(task)

        BotActionScheduler.schedule(session)

        verify(session).setBotTask(task)
        verify(session, never()).riichiEngine()
    }

    @Test
    fun `gb bot turn falls back to selectable discard index`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000b002")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        stubGbTurnSnapshot(session, botId)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(20L))).thenReturn(task)
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.gbCanWinByTsumo(botId)).thenReturn(false)
        `when`(session.gbSuggestedKanTile(botId)).thenReturn(null)
        `when`(session.hand(botId)).thenReturn(listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3))
        `when`(session.gbSuggestedDiscardIndex(botId)).thenReturn(0)
        `when`(session.canSelectHandTile(botId, 0)).thenReturn(false)
        `when`(session.canSelectHandTile(botId, 1)).thenReturn(false)
        `when`(session.canSelectHandTile(botId, 2)).thenReturn(true)
        `when`(session.discard(botId, 2)).thenReturn(true)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(20L))
        runnableCaptor.value.run()

        verify(session).discard(botId, 2)
        verify(session, never()).discard(botId, 0)
    }

    @Test
    fun `gb bot exposes a flower before considering kan or discard`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000b008")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        stubGbTurnSnapshot(session, botId)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(20L))).thenReturn(task)
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.canDeclareFlower(botId)).thenReturn(true)
        `when`(session.hand(botId)).thenReturn(listOf(MahjongTile.M1, MahjongTile.PLUM))
        `when`(session.suggestedFlowerIndices(botId)).thenReturn(listOf(1))
        `when`(session.declareFlower(botId, 1)).thenReturn(true)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(20L))
        runnableCaptor.value.run()

        verify(session).declareFlower(botId, 1)
        verify(session, never()).gbSuggestedKanTile(botId)
        verify(session, never()).discard(eq(botId), any(Int::class.java))
    }

    @Test
    fun `gb bot turn stops retrying after max attempts`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val logger = mock(Logger::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000b003")
        val initialRunnables = mutableListOf<Runnable>()
        val retryRunnables = mutableListOf<Runnable>()

        `when`(session.id()).thenReturn("T001")
        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        stubGbTurnSnapshot(session, botId)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(plugin.getLogger()).thenReturn(logger)
        `when`(session.center()).thenReturn(center)
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.gbCanWinByTsumo(botId)).thenReturn(false)
        `when`(session.gbSuggestedKanTile(botId)).thenReturn(null)
        `when`(session.hand(botId)).thenReturn(listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3))
        `when`(session.gbSuggestedDiscardIndex(botId)).thenReturn(-1)
        `when`(session.canSelectHandTile(botId, 2)).thenReturn(false)
        `when`(session.canSelectHandTile(botId, 1)).thenReturn(false)
        `when`(session.canSelectHandTile(botId, 0)).thenReturn(false)

        doAnswer { invocation ->
            initialRunnables.add(invocation.getArgument(1))
            task
        }.`when`(scheduler).runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(20L))
        doAnswer { invocation ->
            retryRunnables.add(invocation.getArgument(1))
            task
        }.`when`(scheduler).runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(10L))

        BotActionScheduler.schedule(session)

        assertEquals(1, initialRunnables.size)
        initialRunnables.single().run()

        var executedRetries = 0
        while (executedRetries < retryRunnables.size && executedRetries < 32) {
            retryRunnables[executedRetries].run()
            executedRetries++
        }

        assertTrue(executedRetries <= 32, "Retry execution guard should prevent infinite loops in the test harness.")
        verify(scheduler, times(MAX_GB_TURN_RETRY_ATTEMPTS)).runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(10L))
        verify(logger).warning(contains("GB bot turn retry exhausted"))
        verify(session, never()).discard(eq(botId), any(Int::class.java))
    }

    @Test
    fun `gb bot turn retries reset on next scheduling cycle`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val logger = mock(Logger::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000b004")
        val initialRunnables = mutableListOf<Runnable>()
        val retryRunnables = mutableListOf<Runnable>()

        `when`(session.id()).thenReturn("T002")
        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        stubGbTurnSnapshot(session, botId)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(plugin.getLogger()).thenReturn(logger)
        `when`(session.center()).thenReturn(center)
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.gbCanWinByTsumo(botId)).thenReturn(false)
        `when`(session.gbSuggestedKanTile(botId)).thenReturn(null)
        `when`(session.hand(botId)).thenReturn(listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3))
        `when`(session.gbSuggestedDiscardIndex(botId)).thenReturn(-1)
        `when`(session.canSelectHandTile(botId, 2)).thenReturn(false)
        `when`(session.canSelectHandTile(botId, 1)).thenReturn(false)
        `when`(session.canSelectHandTile(botId, 0)).thenReturn(false)

        doAnswer { invocation ->
            initialRunnables.add(invocation.getArgument(1))
            task
        }.`when`(scheduler).runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(20L))
        doAnswer { invocation ->
            retryRunnables.add(invocation.getArgument(1))
            task
        }.`when`(scheduler).runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(10L))

        BotActionScheduler.schedule(session)
        initialRunnables.single().run()
        var executedRetries = 0
        while (executedRetries < retryRunnables.size && executedRetries < 32) {
            retryRunnables[executedRetries].run()
            executedRetries++
        }
        assertEquals(MAX_GB_TURN_RETRY_ATTEMPTS, retryRunnables.size)

        // Second scheduling cycle — the retry counter resets because
        // scheduleGbTurnRetry starts from retryAttempts=0 again.
        BotActionScheduler.schedule(session)
        assertEquals(2, initialRunnables.size)
        initialRunnables[1].run()

        // Execute retry runnables from the second cycle
        while (executedRetries < retryRunnables.size && executedRetries < 64) {
            retryRunnables[executedRetries].run()
            executedRetries++
        }

        assertEquals(MAX_GB_TURN_RETRY_ATTEMPTS * 2, retryRunnables.size)
    }

    @Test
    fun `sichuan variant uses sichuan bot strategy`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c001")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.availableReactions(botId)).thenReturn(null)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(botId)
        `when`(session.isCurrentPlayer(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(20L))).thenReturn(task)

        BotActionScheduler.schedule(session)

        verify(session).setBotTask(task)
        verify(session, never()).riichiEngine()
    }

    @Test
    fun `sichuan bot exchange preparation uses short delay and one batched submission`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c001")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.canChooseSichuanMissingSuit(botId)).thenReturn(false)
        `when`(session.isSichuanExchangePhase(botId)).thenReturn(true)
        `when`(session.selectedHandTileIndices(botId)).thenReturn(emptyList())
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(session.hand(botId)).thenReturn(
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
            ),
        )
        `when`(session.submitSichuanExchangeSelection(botId, listOf(0, 1, 2))).thenReturn(true)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(1L))).thenReturn(task)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(1L))
        runnableCaptor.value.run()

        verify(session).submitSichuanExchangeSelection(botId, listOf(0, 1, 2))
        verify(session, never()).clickHandTile(eq(botId), any(Int::class.java), eq(false))
    }

    @Test
    fun `sichuan bot dingque preparation uses short delay`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c006")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.canChooseSichuanMissingSuit(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(session.hand(botId)).thenReturn(
            listOf(
                MahjongTile.M1,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
            ),
        )
        `when`(session.chooseSichuanMissingSuit(botId, "wan")).thenReturn(true)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(1L))).thenReturn(task)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(1L))
        runnableCaptor.value.run()

        verify(session).chooseSichuanMissingSuit(botId, "wan")
    }

    @Test
    fun `sichuan bot prioritises discarding from smallest suit when all three suits present`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c002")

        // Hand with 4 M tiles, 3 P tiles, 1 S tile — should discard from S suit (fewest)
        val hand =
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
            )

        `when`(session.id()).thenReturn("SC001")
        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.availableReactions(botId)).thenReturn(null)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(botId)
        `when`(session.isCurrentPlayer(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.gbCanWinByTsumo(botId)).thenReturn(false)
        `when`(session.gbSuggestedKanTile(botId)).thenReturn(null)
        `when`(session.hand(botId)).thenReturn(hand)
        `when`(session.canSelectHandTile(eq(botId), any(Int::class.java))).thenReturn(true)
        `when`(session.discard(botId, 7)).thenReturn(true)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(20L))
        runnableCaptor.value.run()

        // Should discard the S1 tile (index 7) since S suit has the fewest tiles
        verify(session).discard(botId, 7)
    }

    @Test
    fun `sichuan bot delegates to engine when already missing one suit`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c003")

        // Hand with only M and P tiles (already missing S suit)
        val hand =
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
            )

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.availableReactions(botId)).thenReturn(null)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(botId)
        `when`(session.isCurrentPlayer(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.gbCanWinByTsumo(botId)).thenReturn(false)
        `when`(session.gbSuggestedKanTile(botId)).thenReturn(null)
        `when`(session.hand(botId)).thenReturn(hand)
        `when`(session.gbSuggestedDiscardIndex(botId)).thenReturn(3)
        `when`(session.canSelectHandTile(botId, 3)).thenReturn(true)
        `when`(session.discard(botId, 3)).thenReturn(true)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(20L))
        runnableCaptor.value.run()

        // Should use the engine's suggested discard index since hand already misses one suit
        verify(session).discard(botId, 3)
    }

    @Test
    fun `sichuan bot declares tsumo when possible`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c004")

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.availableReactions(botId)).thenReturn(null)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(botId)
        `when`(session.isCurrentPlayer(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.gbCanWinByTsumo(botId)).thenReturn(true)
        `when`(session.declareTsumo(botId)).thenReturn(true)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(20L))
        runnableCaptor.value.run()

        verify(session).declareTsumo(botId)
        verify(session, never()).discard(eq(botId), any(Int::class.java))
    }

    @Test
    fun `sichuan bot reaction uses gb suggested reaction`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.actionSnapshotFactory()).thenReturn(PlayerActionSnapshotFactory(session))
        val plugin = mock(TableRuntimeServices::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val task = mock(PluginTask::class.java)
        val center = mock(Location::class.java)
        val botId = UUID.fromString("00000000-0000-0000-0000-00000000c005")
        val reactionOptions = mock(top.ellan.mahjong.riichi.ReactionOptions::class.java)

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.players()).thenReturn(listOf(botId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.availableReactions(botId)).thenReturn(reactionOptions)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(session.center()).thenReturn(center)
        `when`(scheduler.runRegionDelayed(any(Location::class.java), any(Runnable::class.java), eq(20L))).thenReturn(task)

        BotActionScheduler.schedule(session)

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runRegionDelayed(any(Location::class.java), runnableCaptor.capture(), eq(20L))
        runnableCaptor.value.run()

        verify(session).gbSuggestedReaction(botId)
        verify(session).react(eq(botId), any())
    }

    private fun stubGbTurnSnapshot(
        session: MahjongTableSession,
        botId: UUID,
    ) {
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.isSpectator(botId)).thenReturn(false)
        `when`(session.availableReactions(botId)).thenReturn(null)
        `when`(session.canChooseSichuanMissingSuit(botId)).thenReturn(false)
        `when`(session.isSichuanExchangePhase(botId)).thenReturn(false)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.seatOf(botId)).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(botId)
        `when`(session.isCurrentPlayer(botId)).thenReturn(true)
    }
}
