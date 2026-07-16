package top.ellan.mahjong.riichi.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MahjongSoulScoringTest {
    @Test
    fun `top place uses plus fifteen placement bonus`() {
        assertEquals(40.0, MahjongSoulScoring.gameScore(50000, 1))
    }

    @Test
    fun `second place uses plus five placement bonus`() {
        assertEquals(5.0, MahjongSoulScoring.gameScore(25000, 2))
    }

    @Test
    fun `fourth place uses minus fifteen placement bonus`() {
        assertEquals(-35.0, MahjongSoulScoring.gameScore(5000, 4))
    }
}
