package top.ellan.mahjong.table.core.round

import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.riichi.ReactionOptions
import top.ellan.mahjong.riichi.ReactionResponse
import top.ellan.mahjong.riichi.ReactionType
import java.util.EnumMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class GbReactionResolverTest {
    @Test
    fun `nearer pung beats farther exposed kong because both calls have equal priority`() {
        val east = player("east")
        val south = player("south")
        val west = player("west")
        val seats =
            EnumMap<SeatWind, UUID>(SeatWind::class.java).apply {
                put(SeatWind.EAST, east)
                put(SeatWind.SOUTH, south)
                put(SeatWind.WEST, west)
            }
        val options =
            linkedMapOf(
                south to ReactionOptions(false, true, false, emptyList()),
                west to ReactionOptions(false, false, true, emptyList()),
            )
        val responses =
            hashMapOf(
                south to ReactionResponse(ReactionType.PON, null),
                west to ReactionResponse(ReactionType.MINKAN, null),
            )
        val pending =
            GbReactionResolver.PendingReactionWindow(
                east,
                MahjongTile.M1,
                options,
                responses,
                emptyList(),
                false,
                null,
            )

        val resolution =
            GbReactionResolver.resolvePendingReactions(
                pending,
                SeatWind.EAST,
                seats::get,
                false,
            ) { _, _, _, _ -> null }

        assertEquals(south, resolution.claim().playerId())
        assertEquals(ReactionType.PON, resolution.claim().response().type)
    }

    private fun player(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())
}
