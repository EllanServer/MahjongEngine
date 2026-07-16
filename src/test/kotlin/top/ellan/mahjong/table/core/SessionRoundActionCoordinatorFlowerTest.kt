package top.ellan.mahjong.table.core

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.table.core.round.TableRoundController
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRoundActionCoordinatorFlowerTest {
    private val actor = UUID.fromString("00000000-0000-0000-0000-00000000f102")

    @Test
    fun `accepted flower refreshes the table and publishes the action`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        `when`(session.roundControllerInternal()).thenReturn(controller)
        `when`(controller.declareFlower(actor, 3)).thenReturn(true)

        assertTrue(SessionRoundActionCoordinator(session).declareFlower(actor, 3))

        verify(controller).declareFlower(actor, 3)
        verify(session).clearSelectedHandTilesInternal()
        verify(session).rememberPublicActionInternal(actor, "table.action.flower")
        verify(session).render()
        verify(session).flushViewerPresentationIfNeededInternal()
    }

    @Test
    fun `rejected flower does not refresh or publish`() {
        val session = mock(TableSessionMutator::class.java)
        val controller = mock(TableRoundController::class.java)
        `when`(session.roundControllerInternal()).thenReturn(controller)

        assertFalse(SessionRoundActionCoordinator(session).declareFlower(actor, 3))

        verify(session, never()).clearSelectedHandTilesInternal()
        verify(session, never()).rememberPublicActionInternal(actor, "table.action.flower")
        verify(session, never()).render()
    }
}
