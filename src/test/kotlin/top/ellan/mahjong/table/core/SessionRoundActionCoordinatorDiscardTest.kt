package top.ellan.mahjong.table.core

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.table.core.round.TableRoundController
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionRoundActionCoordinatorDiscardTest {
    private val actor = UUID.fromString("00000000-0000-0000-0000-00000000e101")

    @Test
    fun `accepted round resolution without river growth refreshes without announcing a discard`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(session.handTileAtInternal(actor, 0)).thenReturn(MahjongTile.M1)
        `when`(controller.discards(actor)).thenReturn(emptyList())
        `when`(controller.discard(actor, 0)).thenReturn(true)

        assertTrue(SessionRoundActionCoordinator(session).discard(actor, 0))

        verify(session).clearSelectedHandTilesInternal()
        verify(session).clearLastPublicActionInternal()
        verify(session).render()
        verify(session).flushViewerPresentationIfNeededInternal()
        verify(session, never()).rememberPublicDiscardInternal(any(), any())
        verify(session, never()).playDiscardSoundInternal()
    }

    @Test
    fun `ordinary accepted discard is still announced when the river grows`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(session.handTileAtInternal(actor, 0)).thenReturn(MahjongTile.M1)
        `when`(controller.discards(actor)).thenReturn(emptyList(), listOf(MahjongTile.M1))
        `when`(controller.discard(actor, 0)).thenReturn(true)

        assertTrue(SessionRoundActionCoordinator(session).discard(actor, 0))

        verify(session).rememberPublicDiscardInternal(actor, MahjongTile.M1)
        verify(session).playDiscardSoundInternal()
    }

    @Test
    fun `suukaikan preempting riichi refreshes without announcing riichi or a discard`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(session.handTileAtInternal(actor, 0)).thenReturn(MahjongTile.M1)
        `when`(controller.discards(actor)).thenReturn(emptyList())
        `when`(controller.declareRiichi(actor, 0)).thenReturn(true)

        assertTrue(SessionRoundActionCoordinator(session).declareRiichi(actor, 0))

        verify(session).render()
        verify(session).flushViewerPresentationIfNeededInternal()
        verify(session, never()).rememberPublicDiscardInternal(any(), any())
        verify(session, never()).rememberPublicActionsInternal(any(), anyString())
        verify(session, never()).playDiscardSoundInternal()
        verify(session, never()).playRiichiSoundInternal()
    }

    @Test
    fun `accepted riichi declaration plays its event sound exactly once`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(session.handTileAtInternal(actor, 0)).thenReturn(MahjongTile.M1)
        `when`(controller.discards(actor)).thenReturn(emptyList(), listOf(MahjongTile.M1))
        `when`(controller.declareRiichi(actor, 0)).thenReturn(true)

        assertTrue(SessionRoundActionCoordinator(session).declareRiichi(actor, 0))

        verify(session).rememberPublicActionInternal(actor, "table.action.riichi")
        verify(session).playRiichiSoundInternal()
    }
}
