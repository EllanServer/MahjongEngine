package top.ellan.mahjong.table.presentation

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.db.DatabaseService
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.rank.PlayerRankStorage
import top.ellan.mahjong.riichi.RoundResolution
import top.ellan.mahjong.riichi.model.ExhaustiveDraw
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.table.core.MahjongTableManager
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableFinalStanding
import top.ellan.mahjong.table.core.TableOverheadViews
import top.ellan.mahjong.table.core.TableRuntimeServices
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class TablePlayerFeedbackCoordinatorTest {
    @Test
    fun `equal consecutive round resolutions are processed once per round`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val tableManager = mock(MahjongTableManager::class.java)
        val overheadViews = mock(TableOverheadViews::class.java)
        val resolution = RoundResolution("DRAW", draw = ExhaustiveDraw.NORMAL)
        val coordinator = TablePlayerFeedbackCoordinator(session)

        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.lastResolution()).thenReturn(resolution)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(session.id()).thenReturn("TABLE01")
        `when`(session.seatIds()).thenReturn(emptyList())
        `when`(session.finalStandings()).thenReturn(emptyList())
        `when`(plugin.tableManager()).thenReturn(tableManager)
        `when`(tableManager.overheadViews()).thenReturn(overheadViews)

        coordinator.sync()
        coordinator.resetForRoundStart()
        coordinator.onRoundStarted()
        coordinator.sync()

        verify(session, times(2)).resetReadyStateForNextRound()
        verify(session, times(2)).promptPlayersToReady()
        verify(overheadViews, times(2)).closeTable("TABLE01")
    }

    @Test
    fun `settlement ui opens once for seated humans without duplicate chat prompts`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val messages = mock(MessageService::class.java)
        val coordinator = TablePlayerFeedbackCoordinator(session)

        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val botId = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val southId = UUID.fromString("00000000-0000-0000-0000-000000000303")
        val spectatorId = UUID.fromString("00000000-0000-0000-0000-000000000404")

        val east = mock(Player::class.java)
        val south = mock(Player::class.java)
        val spectator = mock(Player::class.java)

        `when`(session.seatIds()).thenReturn(listOf(eastId, botId, southId))
        `when`(session.isBot(botId)).thenReturn(true)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.messages()).thenReturn(messages)
        `when`(session.isRoundFinished()).thenReturn(false)

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Player?> { Bukkit.getPlayer(eastId) }.thenReturn(east)
            bukkit.`when`<Player?> { Bukkit.getPlayer(botId) }.thenReturn(null)
            bukkit.`when`<Player?> { Bukkit.getPlayer(southId) }.thenReturn(south)
            bukkit.`when`<Player?> { Bukkit.getPlayer(spectatorId) }.thenReturn(spectator)

            invokePrivate(coordinator, "openSettlementForPlayers")
        }

        verify(session).openSettlementUi(east)
        verify(session).openSettlementUi(south)
        verify(session, never()).openSettlementUi(spectator)
        verify(messages, never()).send(east, "table.round_finished_ready")
        verify(messages, never()).send(south, "table.round_finished_ready")
        verify(messages, never()).send(spectator, "table.round_finished_ready")
    }

    @Test
    fun `round persistence keeps one frozen pending operation across the next round start`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val database = mock(DatabaseService::class.java)
        val resolution = RoundResolution("DRAW", draw = ExhaustiveDraw.NORMAL)
        val pending = CompletableFuture<Void>()
        val coordinator = TablePlayerFeedbackCoordinator(session)

        `when`(session.plugin()).thenReturn(plugin)
        `when`(session.lastResolution()).thenReturn(resolution)
        `when`(plugin.database()).thenReturn(database)
        `when`(database.persistRoundResultAsync(session, resolution)).thenReturn(pending)

        invokePersistence(coordinator, "persistSettlementIfNeeded", "round-one")
        invokePersistence(coordinator, "persistSettlementIfNeeded", "round-one")
        assertEquals(1, privateMapSize(coordinator, "pendingSettlementPersistence"))
        verify(database, times(1)).persistRoundResultAsync(session, resolution)

        coordinator.onRoundStarted()
        assertEquals(1, privateMapSize(coordinator, "pendingSettlementPersistence"))
        pending.complete(null)
        assertEquals(0, privateMapSize(coordinator, "pendingSettlementPersistence"))
    }

    @Test
    fun `exhausted round persistence is terminal for that epoch instead of busy resubmitting`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val database = mock(DatabaseService::class.java)
        val resolution = RoundResolution("DRAW", draw = ExhaustiveDraw.NORMAL)
        val exhausted = CompletableFuture<Void>()
        val coordinator = TablePlayerFeedbackCoordinator(session)

        `when`(session.plugin()).thenReturn(plugin)
        `when`(session.lastResolution()).thenReturn(resolution)
        `when`(plugin.database()).thenReturn(database)
        `when`(database.persistRoundResultAsync(session, resolution)).thenReturn(exhausted)

        invokePersistence(coordinator, "persistSettlementIfNeeded", "round-one")
        exhausted.completeExceptionally(IllegalStateException("database unavailable"))
        invokePersistence(coordinator, "persistSettlementIfNeeded", "round-one")

        verify(database, times(1)).persistRoundResultAsync(session, resolution)
        assertEquals(1, privateSetSize(coordinator, "failedSettlementPersistence"))
    }

    @Test
    fun `rank persistence also keeps at most one pending operation`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val rankStorage = mock(PlayerRankStorage::class.java)
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000505")
        val standings = listOf(TableFinalStanding(playerId, "Alice", 1, 42000, 57.0, false))
        val pending = CompletableFuture<Void>()
        val rule = MahjongRule()
        val coordinator = TablePlayerFeedbackCoordinator(session)

        `when`(session.plugin()).thenReturn(plugin)
        `when`(session.id()).thenReturn("TABLE01")
        `when`(session.currentVariant()).thenReturn(MahjongVariant.RIICHI)
        `when`(session.configuredRuleSnapshot()).thenReturn(rule)
        `when`(session.finalStandings()).thenReturn(standings)
        `when`(plugin.playerRankStorage()).thenReturn(rankStorage)
        `when`(rankStorage.rankingEnabled()).thenReturn(true)
        `when`(
            rankStorage.persistMatchRanksAsync("TABLE01:0:match-one", "TABLE01", MahjongVariant.RIICHI, rule.length, standings),
        ).thenReturn(pending)

        invokePersistence(coordinator, "persistRankIfNeeded", "match-one")
        invokePersistence(coordinator, "persistRankIfNeeded", "match-one")

        verify(rankStorage, times(1)).persistMatchRanksAsync(
            "TABLE01:0:match-one",
            "TABLE01",
            MahjongVariant.RIICHI,
            rule.length,
            standings,
        )
        assertEquals(1, privateMapSize(coordinator, "pendingRankPersistence"))
    }

    private fun invokePrivate(
        target: Any,
        methodName: String,
    ) {
        val method = target.javaClass.getDeclaredMethod(methodName)
        method.isAccessible = true
        method.invoke(target)
    }

    private fun invokePersistence(
        target: Any,
        methodName: String,
        fingerprint: String,
    ) {
        val method = target.javaClass.getDeclaredMethod(methodName, String::class.java)
        method.isAccessible = true
        method.invoke(target, fingerprint)
    }

    private fun privateMapSize(
        target: Any,
        fieldName: String,
    ): Int {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return (field.get(target) as Map<*, *>).size
    }

    private fun privateSetSize(
        target: Any,
        fieldName: String,
    ): Int {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return (field.get(target) as Set<*>).size
    }
}
