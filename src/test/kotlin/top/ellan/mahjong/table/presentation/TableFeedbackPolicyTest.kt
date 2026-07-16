package top.ellan.mahjong.table.presentation

import top.ellan.mahjong.presentation.TableFeedbackPolicy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableFeedbackPolicyTest {
    @Test
    fun `reaction wins over turn and guidance without repeating identical detail`() {
        val resolved =
            TableFeedbackPolicy.resolveDecision(
                listOf(
                    TableFeedbackPolicy.DecisionCue(
                        "guidance",
                        TableFeedbackPolicy.Priority.GUIDANCE,
                        "Suggested: Ron",
                        "",
                    ),
                    TableFeedbackPolicy.DecisionCue(
                        "turn",
                        TableFeedbackPolicy.Priority.TURN,
                        "Your turn",
                        "Suggested discard 5p",
                    ),
                    TableFeedbackPolicy.DecisionCue(
                        "reaction",
                        TableFeedbackPolicy.Priority.REACTION,
                        "Choose an action",
                        "Choose an action",
                    ),
                ),
            )

        assertEquals("reaction", resolved.eventKey)
        assertEquals(TableFeedbackPolicy.Priority.REACTION, resolved.priority)
        assertEquals("Choose an action", resolved.text)
    }

    @Test
    fun `delivery gate suppresses repeated event within a channel and scope`() {
        val gate = TableFeedbackPolicy.DeliveryGate()
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000101")

        assertTrue(gate.shouldDeliver(viewer, TableFeedbackPolicy.Channel.SOUND, "resolution", "ron-1"))
        assertFalse(gate.shouldDeliver(viewer, TableFeedbackPolicy.Channel.SOUND, "resolution", "ron-1"))
        assertTrue(gate.shouldDeliver(viewer, TableFeedbackPolicy.Channel.SOUND, "turn", "east"))
        assertTrue(gate.shouldDeliver(viewer, TableFeedbackPolicy.Channel.SOUND, "resolution", "ron-2"))
    }

    @Test
    fun `long English Chinese and emoji labels respect visual budgets`() {
        val samples =
            listOf(
                "ExtremelyLongPlayerNameThatWouldCrossTheNeighbouringSeat",
                "这是一个会和相邻座位文字重叠的超长玩家名称",
                "これは隣の席と重なる非常に長いプレイヤー名です",
                "Player🀄With🀄Emoji🀄And🀄Tiles",
            )

        samples.forEach { sample ->
            val compact = TableFeedbackPolicy.compactPlayerName(sample)
            assertTrue(compact.endsWith("…"))
            assertTrue(TableFeedbackPolicy.visualUnits(compact) <= TableFeedbackPolicy.PLAYER_NAME_BUDGET)
        }
    }

    @Test
    fun `multi ron keeps every winner while compacting individual names`() {
        val names =
            listOf(
                "EastPlayerWithVeryLongName",
                "超长的南家玩家名称",
                "WestPlayerWithVeryLongName",
            )

        val joined = TableFeedbackPolicy.joinPlayerNames(names)

        assertEquals(2, joined.count { it == '/' })
        assertTrue(joined.split(" / ").all { TableFeedbackPolicy.visualUnits(it) <= TableFeedbackPolicy.PLAYER_NAME_BUDGET })
    }

    @Test
    fun `winning announcements remain longer than ordinary calls`() {
        assertEquals(60L, TableFeedbackPolicy.announcementDurationTicks("table.action.ron"))
        assertEquals(40L, TableFeedbackPolicy.announcementDurationTicks("table.action.pon"))
    }
}
