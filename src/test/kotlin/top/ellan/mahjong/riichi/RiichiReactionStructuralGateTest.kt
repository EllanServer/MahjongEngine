package top.ellan.mahjong.riichi

import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.TileInstance
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RiichiReactionStructuralGateTest {
    @Test
    fun `reaction fast path skips furo analysis when no structural call exists`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M4,
                MahjongTile.M7,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
            )

        assertNull(player.reactionOptionsFor(TileInstance(mahjongTile = MahjongTile.EAST), false, false))
        assertEquals(0, cachedFuroReactionCount(player))
        val ronOnly = player.reactionOptionsFor(TileInstance(mahjongTile = MahjongTile.EAST), false, true)
        assertEquals(true, ronOnly?.canRon)
        assertEquals(false, ronOnly?.canPon)
        assertEquals(false, ronOnly?.canMinkan)
        assertEquals(ReactionType.RON, ronOnly?.suggestedResponse?.type)
        assertEquals(0, cachedFuroReactionCount(player))
    }

    @Test
    fun `reaction structural gate normalizes red fives and invalidates with hand version`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M5_RED,
                MahjongTile.M5,
                MahjongTile.M1,
                MahjongTile.M9,
                MahjongTile.P1,
                MahjongTile.P9,
                MahjongTile.S1,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
            )
        player.reactionOptionsFor(TileInstance(mahjongTile = MahjongTile.M5), false, false)
        assertEquals(1, cachedFuroReactionCount(player))

        val invalidating = RiichiPlayerState("Bob", "bob")
        invalidating.hands +=
            tiles(
                MahjongTile.P2,
                MahjongTile.M1,
                MahjongTile.M4,
                MahjongTile.M7,
                MahjongTile.P5,
                MahjongTile.P8,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.WHITE_DRAGON,
            )
        assertNull(invalidating.reactionOptionsFor(TileInstance(mahjongTile = MahjongTile.P2), false, false))
        assertEquals(0, cachedFuroReactionCount(invalidating))
        invalidating.drawTile(TileInstance(mahjongTile = MahjongTile.P2))
        invalidating.reactionOptionsFor(TileInstance(mahjongTile = MahjongTile.P2), false, false)
        assertEquals(1, cachedFuroReactionCount(invalidating))
    }

    @Test
    fun `structural gate matches the original furo path for seeded legal hands`() {
        val candidates = MahjongTile.entries.filterNot { it.isFlower || it == MahjongTile.UNKNOWN }
        repeat(4) { seed ->
            val hand = physicalDeck().shuffled(Random(seed)).take(13)
            val optimized = RiichiPlayerState("optimized-$seed", "optimized-$seed")
            val reference = ReferenceReactionPlayer("reference-$seed", "reference-$seed")
            optimized.hands += tiles(*hand.toTypedArray())
            reference.hands += tiles(*hand.toTypedArray())

            for (candidate in candidates) {
                val discard = TileInstance(UUID(seed.toLong() + 1L, candidate.ordinal.toLong() + 1L), candidate)
                for (allowChii in listOf(false, true)) {
                    for (canRon in listOf(false, true)) {
                        assertEquals(
                            reference.reactionOptionsWithoutStructuralGate(discard, allowChii, canRon),
                            optimized.reactionOptionsFor(discard, allowChii, canRon),
                            "seed=$seed tile=$candidate allowChii=$allowChii canRon=$canRon hand=$hand",
                        )
                    }
                }
            }
        }
    }

    private fun cachedFuroReactionCount(player: RiichiPlayerState): Int {
        @Suppress("UNCHECKED_CAST")
        val field = RiichiPlayerAnalysisState::class.java.getDeclaredField("cachedFuroReactions")
        field.isAccessible = true
        val cache = field.get(player) as Map<Any, Any?>
        return cache.size
    }

    private fun tiles(vararg tiles: MahjongTile): List<TileInstance> =
        tiles.mapIndexed { index, tile -> TileInstance(UUID(0L, index.toLong() + 1L), tile) }

    private fun physicalDeck(): List<MahjongTile> =
        buildList {
            MahjongTile.entries
                .take(MahjongTile.RED_DRAGON.ordinal + 1)
                .forEach { tile -> repeat(4) { add(tile) } }
            remove(MahjongTile.M5)
            remove(MahjongTile.P5)
            remove(MahjongTile.S5)
            add(MahjongTile.M5_RED)
            add(MahjongTile.P5_RED)
            add(MahjongTile.S5_RED)
        }

    private class ReferenceReactionPlayer(
        name: String,
        id: String,
    ) : RiichiPlayerState(name, id) {
        fun reactionOptionsWithoutStructuralGate(
            tile: TileInstance,
            allowChii: Boolean,
            canRon: Boolean,
        ): ReactionOptions? {
            if (riichi || doubleRiichi) {
                return if (canRon) {
                    ReactionOptions(
                        canRon = true,
                        canPon = false,
                        canMinkan = false,
                        chiiPairs = emptyList(),
                        suggestedResponse = ReactionResponse(ReactionType.RON, null),
                    )
                } else {
                    null
                }
            }

            val analysis = analyzeFuroReaction(tile, allowChii)
            val canPon = analysis?.pon != null
            val canMinkan = analysis?.minkan != null
            val chiiPairs = analysis?.chiChoices?.map { it.first } ?: emptyList()
            if (!canRon && !canPon && !canMinkan && chiiPairs.isEmpty()) {
                return null
            }
            val suggestion =
                when {
                    canRon -> ReactionResponse(ReactionType.RON, null)
                    analysis == null -> ReactionResponse(ReactionType.SKIP, null)
                    else -> suggestedReactionResponse(analysis)
                }
            return ReactionOptions(canRon, canPon, canMinkan, chiiPairs, suggestion)
        }
    }
}
