package top.ellan.mahjong.table.core.round

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.ellan.mahjong.gb.jni.GbTingCandidate
import top.ellan.mahjong.gb.jni.GbTingResponse
import top.ellan.mahjong.model.MahjongTile

class GbBotDecisionServiceTest {
    @Test
    fun `ready score ignores waits below minimum fan`() {
        val service = GbBotDecisionService(8)
        val lowFan = GbTingResponse(true, listOf(GbTingCandidate("W1", 7)), null)
        val qualified = GbTingResponse(true, listOf(GbTingCandidate("W1", 8)), null)

        assertEquals(0, service.readyScore(lowFan))
        assertTrue(service.readyScore(qualified) > 0)
    }

    @Test
    fun `discard preference favors isolated terminal over connected middle tile`() {
        val hand = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3, MahjongTile.P9)

        assertTrue(
            GbBotDecisionService.discardPreference(hand, MahjongTile.P9) >
                GbBotDecisionService.discardPreference(hand, MahjongTile.M2)
        )
    }

    @Test
    fun `discard suggestion uses injected ting evaluator`() {
        val service = GbBotDecisionService(8)
        val hand = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3, MahjongTile.EAST)

        val suggestedIndex = service.suggestedDiscardIndex(hand, emptyList()) { remaining, _ ->
            if (MahjongTile.EAST !in remaining) {
                GbTingResponse(true, listOf(GbTingCandidate("W1", 8)), null)
            } else {
                GbTingResponse(true, emptyList(), null)
            }
        }

        assertEquals(3, suggestedIndex)
    }

    @Test
    fun `discard suggestion evaluates the first remaining hand once per duplicated tile`() {
        val service = GbBotDecisionService(8)
        val hand = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M1)
        val evaluatedHands = mutableListOf<List<MahjongTile>>()

        service.suggestedDiscardIndex(hand, emptyList()) { remaining, _ ->
            evaluatedHands += remaining.toList()
            GbTingResponse(true, emptyList(), null)
        }

        assertEquals(
            listOf(
                listOf(MahjongTile.M2, MahjongTile.M1),
                listOf(MahjongTile.M1, MahjongTile.M1),
            ),
            evaluatedHands,
        )
    }

    @Test
    fun `discard suggestion preserves null evaluator recomputation`() {
        val service = GbBotDecisionService(8)
        val hand = listOf(MahjongTile.M1, MahjongTile.M1, MahjongTile.M1)
        var evaluations = 0

        service.suggestedDiscardIndex(hand, emptyList()) { _, _ ->
            evaluations++
            null
        }

        assertEquals(hand.size, evaluations)
    }
}
