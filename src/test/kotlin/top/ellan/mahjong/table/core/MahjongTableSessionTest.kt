package top.ellan.mahjong.table.core

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.riichi.RiichiPlayerState
import top.ellan.mahjong.riichi.RiichiRoundEngine
import top.ellan.mahjong.table.core.round.RiichiTableRoundController
import top.ellan.mahjong.table.core.round.TableRoundController
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MahjongTableSessionTest {
    @Test
    fun `player removal after finished round ignores stale engine seats`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE01", Location(null, 0.0, 64.0, 0.0), false)
        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val southId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val westId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val northId = UUID.fromString("00000000-0000-0000-0000-000000000004")

        session.addPlayer(mockPlayer(eastId), SeatWind.EAST)
        session.addPlayer(mockPlayer(southId), SeatWind.SOUTH)
        session.addPlayer(mockPlayer(westId), SeatWind.WEST)
        session.addPlayer(mockPlayer(northId), SeatWind.NORTH)
        attachEngine(
            session,
            started = false,
            seatIds = listOf(eastId, southId, westId, northId),
        )

        removeParticipant(session, eastId)
        assertFalse(session.contains(eastId))
        assertNull(session.playerAt(SeatWind.EAST))
        assertNull(session.seatOf(eastId))
        assertEquals(3, session.size())
    }

    @Test
    fun `active round still reads seat occupants from engine`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE02", Location(null, 0.0, 64.0, 0.0), false)
        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val southId = UUID.fromString("00000000-0000-0000-0000-000000000012")

        session.addPlayer(mockPlayer(eastId), SeatWind.EAST)
        session.addPlayer(mockPlayer(southId), SeatWind.SOUTH)
        attachEngine(
            session,
            started = true,
            seatIds = listOf(southId, eastId),
        )

        assertEquals(southId, session.playerAt(SeatWind.EAST))
        assertEquals(eastId, session.playerAt(SeatWind.SOUTH))
    }

    @Test
    fun `empty seat accessors stay null safe for render snapshots`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE03", Location(null, 0.0, 64.0, 0.0), false)

        assertEquals(0, session.points(null))
        assertFalse(session.isRiichi(null))
        assertEquals(emptyList(), session.hand(null))
        assertEquals(emptyList(), session.discards(null))
        assertEquals(-1, session.riichiDiscardIndex(null))
        assertEquals(emptyList(), session.fuuro(null))
        assertEquals(emptyList(), session.scoringSticks(null))
    }

    @Test
    fun `gb preset switches table variant to gb`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE04", Location(null, 0.0, 64.0, 0.0), false)

        assertTrue(session.applyRulePreset("GB"))
        assertEquals(MahjongVariant.GB, session.currentVariant())
    }

    @Test
    fun `sichuan preset switches table variant to sichuan`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE04B", Location(null, 0.0, 64.0, 0.0), false)

        assertTrue(session.applyRulePreset("SICHUAN"))
        assertEquals(MahjongVariant.SICHUAN, session.currentVariant())
    }

    @Test
    fun `table owner transfers to next human player when owner leaves`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE04C", Location(null, 0.0, 64.0, 0.0), false)
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val nextOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000042")

        session.setOwner(ownerId)
        session.addPlayer(mockPlayer(ownerId), SeatWind.EAST)
        session.addPlayer(mockPlayer(nextOwnerId), SeatWind.SOUTH)

        assertTrue(session.isOwner(ownerId))
        removeParticipant(session, ownerId)
        reassignOwnerFromSeats(session)
        assertEquals(nextOwnerId, session.owner())
        assertTrue(session.isOwner(nextOwnerId))
    }

    @Test
    fun `gb helpers stay inactive before a gb round is created`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE05", Location(null, 0.0, 64.0, 0.0), false)

        session.applyRulePreset("GB")

        assertFalse(session.hasRoundController())
        assertFalse(session.gbCanWinByTsumo(UUID.fromString("00000000-0000-0000-0000-000000000005")))
        assertFalse(session.gbTingOptions(UUID.fromString("00000000-0000-0000-0000-000000000005")).valid)
    }

    @Test
    fun `gb standings preserve zero and negative raw points without mahjong soul score`() {
        val plugin = mock(TableRuntimeServices::class.java)
        `when`(plugin.messages()).thenReturn(MessageService())
        val session = MahjongTableSession(plugin, "TABLE-GB-SCORES", Location(null, 0.0, 64.0, 0.0), false)
        assertTrue(session.applyRulePreset("GB"))
        val playerIds =
            SeatWind.values().map { wind ->
                UUID.nameUUIDFromBytes("gb-score-${wind.name}".toByteArray()).also { playerId ->
                    session.addPlayer(mockPlayer(playerId), wind)
                }
            }
        val scores = listOf(2000, 500, 0, -500)
        val controller = mock(TableRoundController::class.java)
        `when`(controller.gameFinished()).thenReturn(true)
        SeatWind.values().forEachIndexed { index, wind ->
            `when`(controller.playerAt(wind)).thenReturn(playerIds[index])
            `when`(controller.points(playerIds[index])).thenReturn(scores[index])
        }
        attachController(session, controller)

        assertEquals(0, session.points(playerIds[2]))
        assertEquals(-500, session.points(playerIds[3]))
        val standings =
            mockStatic(Bukkit::class.java).use { bukkit ->
                playerIds.forEachIndexed { index, playerId ->
                    val onlinePlayer = mock(Player::class.java)
                    `when`(onlinePlayer.name).thenReturn("Player ${index + 1}")
                    bukkit.`when`<Player?> { Bukkit.getPlayer(playerId) }.thenReturn(onlinePlayer)
                }
                session.finalStandings()
            }
        assertEquals(scores.sortedDescending(), standings.map { it.points })
        assertTrue(standings.all { it.gameScore == 0.0 })
    }

    @Test
    fun `open door follows every dealer and valid first dice total`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE-DYNAMIC-DEALER", Location(null, 0.0, 64.0, 0.0), false)
        val controller = mock(TableRoundController::class.java)
        attachController(session, controller)

        SeatWind.values().forEach { dealer ->
            for (dicePoints in 2..12) {
                `when`(controller.dicePoints()).thenReturn(dicePoints)
                `when`(controller.dealerSeat()).thenReturn(dealer)
                `when`(controller.roundIndex()).thenReturn(Math.floorMod(dealer.index() + 2, SeatWind.values().size))

                val expected =
                    SeatWind.fromIndex(
                        Math.floorMod(dealer.index() + dicePoints - 1, SeatWind.values().size),
                    )
                assertEquals(expected, session.openDoorSeat(), "dealer=$dealer, dicePoints=$dicePoints")
            }
        }
    }

    @Test
    fun `player can replace bot on a specific seat before round start`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = MahjongTableSession(plugin, "TABLE06", Location(null, 0.0, 64.0, 0.0), false)
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000061")
        val player = mockPlayer(playerId)

        assertTrue(addBotParticipant(session, "TABLE06"))
        val botId = session.playerAt(SeatWind.EAST)
        assertTrue(botId != null && session.isBot(botId))

        assertTrue(session.replaceBotWithPlayer(player, SeatWind.EAST))
        assertEquals(playerId, session.playerAt(SeatWind.EAST))
        assertFalse(session.isBot(playerId))
        assertFalse(session.contains(botId))
        assertEquals(0, session.botCount())
        assertFalse(session.isReady(playerId))
    }

    private fun attachEngine(
        session: MahjongTableSession,
        started: Boolean,
        seatIds: List<UUID>,
    ) {
        val engine = mock(RiichiRoundEngine::class.java)
        `when`(engine.started).thenReturn(started)
        `when`(engine.seats).thenReturn(seatIds.map(::mockSeatPlayer).toMutableList())
        val controllerField = MahjongTableSession::class.java.getDeclaredField("roundController")
        controllerField.isAccessible = true
        controllerField.set(session, RiichiTableRoundController(engine))
    }

    private fun attachController(
        session: MahjongTableSession,
        controller: TableRoundController,
    ) {
        val controllerField = MahjongTableSession::class.java.getDeclaredField("roundController")
        controllerField.isAccessible = true
        controllerField.set(session, controller)
    }

    private fun mockSeatPlayer(playerId: UUID): RiichiPlayerState = RiichiPlayerState(playerId.toString(), playerId.toString())

    private fun removeParticipant(
        session: MahjongTableSession,
        playerId: UUID,
    ) {
        val participantsField = MahjongTableSession::class.java.getDeclaredField("participants")
        participantsField.isAccessible = true
        val participants = participantsField.get(session)
        val removePlayer = participants.javaClass.getDeclaredMethod("removePlayer", UUID::class.java)
        removePlayer.isAccessible = true
        removePlayer.invoke(participants, playerId)
    }

    private fun reassignOwnerFromSeats(session: MahjongTableSession) {
        val reassignOwner = MahjongTableSession::class.java.getDeclaredMethod("reassignOwnerFromSeats")
        reassignOwner.isAccessible = true
        reassignOwner.invoke(session)
    }

    private fun addBotParticipant(
        session: MahjongTableSession,
        tableId: String,
    ): Boolean {
        val participantsField = MahjongTableSession::class.java.getDeclaredField("participants")
        participantsField.isAccessible = true
        val participants = participantsField.get(session)
        val createNextBotId = participants.javaClass.getDeclaredMethod("createNextBotId", String::class.java)
        createNextBotId.isAccessible = true
        val addBot = participants.javaClass.getDeclaredMethod("addBot", UUID::class.java)
        addBot.isAccessible = true
        val botId = createNextBotId.invoke(participants, tableId) as UUID
        return addBot.invoke(participants, botId) as Boolean
    }

    private fun mockPlayer(playerId: UUID): Player {
        val player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(playerId)
        return player
    }
}
