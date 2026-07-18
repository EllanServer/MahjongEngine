package top.ellan.mahjong.table.core.round

import top.ellan.mahjong.model.MahjongTile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SichuanHuEvaluatorTest {
    @Test
    fun `standard hand can win`() {
        val concealed =
            listOf(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S9,
            )

        assertTrue(SichuanHuEvaluator.canWin(concealed, MahjongTile.S9, 0))
    }

    @Test
    fun `seven pairs hand can win`() {
        val concealed =
            listOf(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M3,
                MahjongTile.P4,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P5,
                MahjongTile.S6,
                MahjongTile.S6,
                MahjongTile.S7,
            )

        assertTrue(SichuanHuEvaluator.canWin(concealed, MahjongTile.S7, 0))
        assertEquals(listOf(MahjongTile.S7), SichuanHuEvaluator.waitingTiles(concealed, 0))
    }

    @Test
    fun `honor tile is not a valid sichuan winning tile`() {
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
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
            )

        assertFalse(SichuanHuEvaluator.canWin(concealed, MahjongTile.EAST, 0))
    }

    @Test
    fun `red fives normalize without changing wait ordering`() {
        val concealed =
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5_RED,
                MahjongTile.M6,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.P5,
            )

        assertEquals(listOf(MahjongTile.P2, MahjongTile.P5), SichuanHuEvaluator.waitingTiles(concealed, 0))
        assertTrue(SichuanHuEvaluator.canWin(concealed, MahjongTile.P5_RED, 0))
    }

    @Test
    fun `optimized waiting scan matches reference recursion for deterministic hands`() {
        val deck = sichuanTiles().flatMap { tile -> List(4) { tile } }
        repeat(64) { seed ->
            val concealed = deck.shuffled(Random(seed)).take(13)
            val expected = sichuanTiles().filter { wait -> referenceCanWin(concealed, wait, 0) }
            assertEquals(expected, SichuanHuEvaluator.waitingTiles(concealed, 0), "seed=$seed")
        }
    }

    @Test
    fun `optimized evaluator preserves waits and winning shape for fixed meld counts`() {
        for (fixedMeldCount in 0..4) {
            repeat(24) { seed ->
                val (concealed, winningTile) = randomLegalWin(seed + fixedMeldCount * 100, fixedMeldCount)
                val expected = referenceEvaluate(concealed, winningTile, fixedMeldCount)
                assertEquals(
                    expected,
                    SichuanHuEvaluator.evaluate(concealed, winningTile, fixedMeldCount),
                    "fixedMelds=$fixedMeldCount seed=$seed concealed=$concealed winning=$winningTile",
                )
                assertEquals(expected.valid(), SichuanHuEvaluator.canWin(concealed, winningTile, fixedMeldCount))
                assertEquals(
                    sichuanTiles().filter { wait -> referenceCanWin(concealed, wait, fixedMeldCount) },
                    SichuanHuEvaluator.waitingTiles(concealed, fixedMeldCount),
                    "waits fixedMelds=$fixedMeldCount seed=$seed",
                )
            }
        }
    }
}

private fun sichuanTiles(): List<MahjongTile> =
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
        MahjongTile.P2,
        MahjongTile.P3,
        MahjongTile.P4,
        MahjongTile.P5,
        MahjongTile.P6,
        MahjongTile.P7,
        MahjongTile.P8,
        MahjongTile.P9,
        MahjongTile.S1,
        MahjongTile.S2,
        MahjongTile.S3,
        MahjongTile.S4,
        MahjongTile.S5,
        MahjongTile.S6,
        MahjongTile.S7,
        MahjongTile.S8,
        MahjongTile.S9,
    )

private fun referenceCanWin(
    concealed: List<MahjongTile>,
    winningTile: MahjongTile,
    fixedMeldCount: Int,
): Boolean {
    val requiredMelds = 4 - fixedMeldCount
    if (requiredMelds < 0 || concealed.size + 1 != requiredMelds * 3 + 2) return false
    val tiles = sichuanTiles()
    val counts = IntArray(27)
    for (tile in concealed + winningTile) {
        val index = tiles.indexOf(tile.baseTileForReference())
        if (index < 0 || ++counts[index] > 4) return false
    }
    if (fixedMeldCount == 0 && counts.filter { it != 0 }.all { it == 2 || it == 4 } && counts.sumOf { it / 2 } == 7) {
        return true
    }
    for (pair in counts.indices) {
        if (counts[pair] < 2) continue
        counts[pair] -= 2
        val winning = referenceMelds(counts, requiredMelds)
        counts[pair] += 2
        if (winning) return true
    }
    return false
}

private fun referenceEvaluate(
    concealed: List<MahjongTile>,
    winningTile: MahjongTile,
    fixedMeldCount: Int,
): SichuanHuEvaluator.Result {
    val requiredMelds = 4 - fixedMeldCount
    if (requiredMelds < 0 || concealed.size + 1 != requiredMelds * 3 + 2) {
        return SichuanHuEvaluator.Result.invalid()
    }
    val tiles = sichuanTiles()
    val counts = IntArray(27)
    for (tile in concealed + winningTile) {
        val index = tiles.indexOf(tile.baseTileForReference())
        if (index < 0 || ++counts[index] > 4) return SichuanHuEvaluator.Result.invalid()
    }
    if (fixedMeldCount == 0 && counts.filter { it != 0 }.all { it == 2 || it == 4 } && counts.sumOf { it / 2 } == 7) {
        return SichuanHuEvaluator.Result(true, true, emptyList())
    }

    var bestShape: List<SichuanHuEvaluator.MeldShape>? = null
    var bestSequenceCount = Int.MAX_VALUE
    for (pair in counts.indices) {
        if (counts[pair] < 2) continue
        counts[pair] -= 2
        val melds = mutableListOf<SichuanHuEvaluator.MeldShape>()
        if (referenceCollectMelds(counts, requiredMelds, melds)) {
            val sequenceCount = melds.count { it == SichuanHuEvaluator.MeldShape.SEQUENCE }
            if (sequenceCount < bestSequenceCount) {
                bestSequenceCount = sequenceCount
                bestShape = melds.toList()
            }
        }
        counts[pair] += 2
    }
    return bestShape?.let { SichuanHuEvaluator.Result(true, false, it) } ?: SichuanHuEvaluator.Result.invalid()
}

private fun referenceCollectMelds(
    counts: IntArray,
    remaining: Int,
    melds: MutableList<SichuanHuEvaluator.MeldShape>,
): Boolean {
    if (remaining == 0) return counts.all { it == 0 }
    val first = counts.indexOfFirst { it > 0 }
    if (first < 0) return false
    if (counts[first] >= 3) {
        counts[first] -= 3
        melds += SichuanHuEvaluator.MeldShape.TRIPLET
        if (referenceCollectMelds(counts, remaining - 1, melds)) {
            counts[first] += 3
            return true
        }
        melds.removeLast()
        counts[first] += 3
    }
    val rank = first % 9
    if (rank <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
        counts[first]--
        counts[first + 1]--
        counts[first + 2]--
        melds += SichuanHuEvaluator.MeldShape.SEQUENCE
        if (referenceCollectMelds(counts, remaining - 1, melds)) {
            counts[first]++
            counts[first + 1]++
            counts[first + 2]++
            return true
        }
        melds.removeLast()
        counts[first]++
        counts[first + 1]++
        counts[first + 2]++
    }
    return false
}

private fun randomLegalWin(
    seed: Int,
    fixedMeldCount: Int,
): Pair<List<MahjongTile>, MahjongTile> {
    val random = Random(seed)
    val requiredMelds = 4 - fixedMeldCount
    repeat(1_000) {
        val counts = IntArray(27)
        counts[random.nextInt(27)] += 2
        var valid = true
        repeat(requiredMelds) {
            if (random.nextBoolean()) {
                val index = random.nextInt(27)
                counts[index] += 3
                valid = valid && counts[index] <= 4
            } else {
                val index = random.nextInt(3) * 9 + random.nextInt(7)
                counts[index]++
                counts[index + 1]++
                counts[index + 2]++
                valid = valid && counts[index] <= 4 && counts[index + 1] <= 4 && counts[index + 2] <= 4
            }
        }
        if (!valid) return@repeat

        val physical = counts.flatMapIndexed { index, count -> List(count) { sichuanTiles()[index] } }.toMutableList()
        val fiveIndices = physical.indices.filter { physical[it] in listOf(MahjongTile.M5, MahjongTile.P5, MahjongTile.S5) }
        if (fiveIndices.isNotEmpty()) {
            val redIndex = fiveIndices[random.nextInt(fiveIndices.size)]
            physical[redIndex] =
                when (physical[redIndex]) {
                    MahjongTile.M5 -> MahjongTile.M5_RED
                    MahjongTile.P5 -> MahjongTile.P5_RED
                    MahjongTile.S5 -> MahjongTile.S5_RED
                    else -> error("not a five")
                }
        }
        physical.shuffle(random)
        val winningTile = physical.removeAt(random.nextInt(physical.size))
        return physical.toList() to winningTile
    }
    error("Unable to construct a legal Sichuan hand for seed=$seed fixedMeldCount=$fixedMeldCount")
}

private fun referenceMelds(
    counts: IntArray,
    remaining: Int,
): Boolean {
    if (remaining == 0) return counts.all { it == 0 }
    val first = counts.indexOfFirst { it > 0 }
    if (first < 0) return false
    if (counts[first] >= 3) {
        counts[first] -= 3
        val winning = referenceMelds(counts, remaining - 1)
        counts[first] += 3
        if (winning) return true
    }
    val rank = first % 9
    if (rank <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
        counts[first]--
        counts[first + 1]--
        counts[first + 2]--
        val winning = referenceMelds(counts, remaining - 1)
        counts[first]++
        counts[first + 1]++
        counts[first + 2]++
        if (winning) return true
    }
    return false
}

private fun MahjongTile.baseTileForReference(): MahjongTile =
    when (this) {
        MahjongTile.M5_RED -> MahjongTile.M5
        MahjongTile.P5_RED -> MahjongTile.P5
        MahjongTile.S5_RED -> MahjongTile.S5
        else -> this
    }
