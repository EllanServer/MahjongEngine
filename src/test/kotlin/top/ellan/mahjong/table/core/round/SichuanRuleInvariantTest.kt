package top.ellan.mahjong.table.core.round

import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SichuanRuleInvariantTest {
    private val engine: SichuanRulesEngine = DefaultSichuanRulesEngine()

    @Test
    fun `sichuan wall has exactly four copies of every suited tile`() {
        val wall = GbRoundSupport.buildWall(GbRuleProfile.SICHUAN)

        assertEquals(108, wall.size)
        assertFalse(wall.any { it.isFlower || GbRoundSupport.isHonor(it) || it.isRedFive })
        assertEquals(27, wall.toSet().size)
        wall.groupingBy { it }.eachCount().forEach { (_, count) -> assertEquals(4, count) }
    }

    @Test
    fun `every exchange direction is a four seat permutation without self delivery`() {
        for (dicePoints in 2..12) {
            val direction = SichuanExchangeDirection.fromDicePoints(dicePoints)
            val targets = SeatWind.entries.map(direction::targetOf)

            assertEquals(SeatWind.entries.toSet(), targets.toSet())
            SeatWind.entries.forEach { source -> assertTrue(direction.targetOf(source) != source) }
        }
    }

    @Test
    fun `standard and seven pairs wins are invariant to tile order and winning tile position`() {
        val hands =
            listOf(
                listOf(
                    MahjongTile.M1,
                    MahjongTile.M2,
                    MahjongTile.M3,
                    MahjongTile.M4,
                    MahjongTile.M5,
                    MahjongTile.M6,
                    MahjongTile.M7,
                    MahjongTile.M8,
                    MahjongTile.M9,
                    MahjongTile.P1,
                    MahjongTile.P1,
                    MahjongTile.P1,
                    MahjongTile.P2,
                    MahjongTile.P2,
                ),
                listOf(
                    MahjongTile.M1,
                    MahjongTile.M1,
                    MahjongTile.M2,
                    MahjongTile.M2,
                    MahjongTile.M3,
                    MahjongTile.M3,
                    MahjongTile.M4,
                    MahjongTile.M4,
                    MahjongTile.P5,
                    MahjongTile.P5,
                    MahjongTile.P6,
                    MahjongTile.P6,
                    MahjongTile.P7,
                    MahjongTile.P7,
                ),
            )

        hands.forEachIndexed { handIndex, completeHand ->
            completeHand.indices.forEach { winningIndex ->
                val concealed = completeHand.toMutableList()
                val winningTile = concealed.removeAt(winningIndex)
                repeat(8) { shuffleIndex ->
                    val shuffled = concealed.shuffled(Random(handIndex * 10_000 + winningIndex * 100 + shuffleIndex))
                    assertTrue(
                        engine.evaluateFan(shuffled, emptyList(), winningTile, "DISCARD", emptyList(), false).valid,
                        "hand=$handIndex winningIndex=$winningIndex shuffle=$shuffleIndex",
                    )
                }
            }
        }
    }

    @Test
    fun `waiting tiles are exactly tiles that complete a legal shape`() {
        val concealed =
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P2,
            )

        val waits = engine.waitingTiles(concealed, 0)

        assertTrue(MahjongTile.P2 in waits)
        assertFalse(waits.any { it.isFlower || GbRoundSupport.isHonor(it) })
        waits.forEach { wait ->
            assertTrue(engine.evaluateFan(concealed, emptyList(), wait, "DISCARD", emptyList(), false).valid)
        }
    }

    @Test
    fun `impossible fifth copy is rejected even when its shape would otherwise decompose`() {
        val concealed =
            listOf(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P2,
            )

        assertFalse(engine.evaluateFan(concealed, emptyList(), MahjongTile.M1, "DISCARD", emptyList(), false).valid)
    }

    @Test
    fun `all win and kong payment paths conserve table points`() {
        SeatWind.entries.forEach { winner ->
            val opponents = SeatWind.entries.filter { it != winner }
            assertEquals(0, engine.winDeltas(winner, null, "SELF_DRAW", 32, opponents).sumOf { it.delta })
            opponents.forEach { discarder ->
                assertEquals(0, engine.winDeltas(winner, discarder, "DISCARD", 32, opponents).sumOf { it.delta })
            }
            assertEquals(0, engine.kanDeltas(winner, opponents, 2).sumOf { it.delta })
        }
    }
}
