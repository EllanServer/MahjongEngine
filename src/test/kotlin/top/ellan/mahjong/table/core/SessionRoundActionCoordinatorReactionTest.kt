package top.ellan.mahjong.table.core

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.scene.MeldView
import top.ellan.mahjong.riichi.ReactionResponses
import top.ellan.mahjong.riichi.RoundResolution
import top.ellan.mahjong.riichi.model.YakuSettlement
import top.ellan.mahjong.table.core.round.TableRoundController
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionRoundActionCoordinatorReactionTest {
    private val actor = UUID.fromString("00000000-0000-0000-0000-00000000e001")

    @Test
    fun `accepted reaction is not announced before priority window resolves`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(controller.react(actor, ReactionResponses.PON)).thenReturn(true)
        `when`(controller.hasPendingReaction()).thenReturn(true)

        assertTrue(SessionRoundActionCoordinator(session).react(actor, ReactionResponses.PON))

        verify(session, never()).rememberPublicActionInternal(any(), anyString())
        verify(session, never()).playReactionSoundInternal(any())
    }

    @Test
    fun `resolved claim is announced for actual claimant even when final response was skip`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        val pon =
            MeldView(
                listOf(MahjongTile.M5, MahjongTile.M5, MahjongTile.M5),
                listOf(false, false, false),
                0,
                0,
                null,
            )
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(actor)
        `when`(controller.fuuro(actor)).thenReturn(emptyList(), listOf(pon))
        `when`(controller.react(actor, ReactionResponses.SKIP)).thenReturn(true)
        `when`(controller.hasPendingReaction()).thenReturn(false)

        assertTrue(SessionRoundActionCoordinator(session).react(actor, ReactionResponses.SKIP))

        verify(session).rememberPublicActionInternal(actor, "table.action.pon")
        verify(session).playReactionSoundInternal(ReactionResponses.PON)
    }

    @Test
    fun `multi ron announcement uses all actual resolution winners instead of final responder`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        val firstWinner = UUID.fromString("00000000-0000-0000-0000-00000000e011")
        val secondWinner = UUID.fromString("00000000-0000-0000-0000-00000000e012")
        val resolution =
            RoundResolution(
                "RON",
                listOf(winnerSettlement(firstWinner), winnerSettlement(secondWinner)),
            )
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(firstWinner)
        `when`(session.playerAt(SeatWind.SOUTH)).thenReturn(secondWinner)
        `when`(session.playerAt(SeatWind.WEST)).thenReturn(actor)
        `when`(controller.fuuro(firstWinner)).thenReturn(emptyList())
        `when`(controller.fuuro(secondWinner)).thenReturn(emptyList())
        `when`(controller.fuuro(actor)).thenReturn(emptyList())
        `when`(controller.lastResolution()).thenReturn(null, resolution)
        `when`(controller.react(actor, ReactionResponses.SKIP)).thenReturn(true)
        `when`(controller.hasPendingReaction()).thenReturn(false)

        assertTrue(SessionRoundActionCoordinator(session).react(actor, ReactionResponses.SKIP))

        verify(session).rememberPublicActionsInternal(listOf(firstWinner, secondWinner), "table.action.ron")
        verify(session).playReactionSoundInternal(ReactionResponses.RON)
    }

    private fun winnerSettlement(playerId: UUID) =
        YakuSettlement(
            displayName = playerId.toString(),
            uuid = playerId.toString(),
            yakuList = emptyList(),
            yakumanList = emptyList(),
            doubleYakumanList = emptyList(),
            riichi = false,
            winningTile = top.ellan.mahjong.riichi.model.MahjongTile.M1,
            hands = emptyList(),
            fuuroList = emptyList(),
            doraIndicators = emptyList(),
            uraDoraIndicators = emptyList(),
            fu = 30,
            han = 1,
            score = 1000,
        )
}
