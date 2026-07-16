package top.ellan.mahjong.table.action

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.table.core.TableSessionContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerActionSnapshotFactoryCurrentPlayerTest {
    private val viewerId = UUID.fromString("00000000-0000-0000-0000-00000000f201")

    @Test
    fun `player that completed dingque waits while preparation remains active`() {
        val session = startedSession()
        `when`(session.canChooseSichuanMissingSuit(viewerId)).thenReturn(false)
        `when`(session.isSichuanExchangePhase(viewerId)).thenReturn(false)
        `when`(session.isCurrentPlayer(viewerId)).thenReturn(false)

        val snapshot = PlayerActionSnapshotFactory(session).capture(viewerId)

        assertEquals(PlayerActionPhase.WAITING, snapshot.phase())
    }

    @Test
    fun `player with pending dingque keeps dingque actions`() {
        val session = startedSession()
        `when`(session.canChooseSichuanMissingSuit(viewerId)).thenReturn(true)

        val snapshot = PlayerActionSnapshotFactory(session).capture(viewerId)

        assertEquals(PlayerActionPhase.SICHUAN_DING_QUE, snapshot.phase())
        assertEquals(3, snapshot.actions().size)
    }

    private fun startedSession(): TableSessionContext {
        val session = mock(TableSessionContext::class.java)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.isStarted()).thenReturn(true)
        return session
    }
}
