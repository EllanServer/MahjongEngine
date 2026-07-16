package top.ellan.mahjong.table.action

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.table.core.TableSessionContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerActionSnapshotFactoryFlowerTest {
    private val viewerId = UUID.fromString("00000000-0000-0000-0000-00000000f101")

    @Test
    fun `one flower is exposed as a direct indexed turn action`() {
        val session = turnSession(listOf(MahjongTile.M1, MahjongTile.PLUM), listOf(1))

        val snapshot = PlayerActionSnapshotFactory(session).capture(viewerId)

        val flower = snapshot.actions().single { it.actionId() == PlayerActionId.FLOWER }
        assertEquals("turn:flower:1", flower.command())
        assertEquals("table.action.flower", flower.labelKey())
        assertEquals(listOf("PLUM", "1"), flower.arguments())
    }

    @Test
    fun `multiple flowers use a submenu and preserve each hand index`() {
        val session =
            turnSession(
                listOf(MahjongTile.PLUM, MahjongTile.M1, MahjongTile.SPRING),
                listOf(0, 2),
            )
        val factory = PlayerActionSnapshotFactory(session)

        val root = factory.capture(viewerId)
        assertTrue(root.actions().any { it.actionId() == PlayerActionId.MENU_TURN_FLOWER && it.command() == "menu:turn-flower" })
        assertTrue(root.actions().none { it.actionId() == PlayerActionId.FLOWER })

        `when`(session.viewerActionMenuState(viewerId)).thenReturn("turn-flower")
        val submenu = factory.capture(viewerId)
        assertEquals(
            listOf("turn:flower:0", "turn:flower:2"),
            submenu
                .actions()
                .filter {
                    it.actionId() == PlayerActionId.FLOWER
                }.map { it.command() },
        )
        assertTrue(submenu.actions().any { it.actionId() == PlayerActionId.MENU_BACK })
    }

    private fun turnSession(
        hand: List<MahjongTile>,
        flowerIndices: List<Int>,
    ): TableSessionContext {
        val session = mock(TableSessionContext::class.java)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.isCurrentPlayer(viewerId)).thenReturn(true)
        `when`(session.hand(viewerId)).thenReturn(hand)
        `when`(session.canDeclareFlower(viewerId)).thenReturn(true)
        `when`(session.suggestedFlowerIndices(viewerId)).thenReturn(flowerIndices)
        return session
    }
}
