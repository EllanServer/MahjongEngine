package top.ellan.mahjong.render

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.TableRenderSubject
import top.ellan.mahjong.render.layout.TableRenderLayout
import top.ellan.mahjong.render.layout.WallLayout
import top.ellan.mahjong.render.scene.MeldView
import top.ellan.mahjong.render.scene.TableGeometry
import top.ellan.mahjong.render.scene.TableRenderConstants
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot
import java.util.EnumMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VariantWallRenderLayoutTest {
    @Test
    fun `each variant renders its exact wall capacity and dead wall policy`() {
        val riichi = TableRenderLayout.precompute(snapshot(MahjongVariant.RIICHI, 70, dora = listOf(MahjongTile.M1)))
        assertEquals(136, riichi.wallTiles().size)
        assertEquals(83, riichi.wallTiles().count { it != null })
        assertEquals(1, riichi.doraTiles().size)
        assertEquals(84, riichi.wallTiles().count { it != null } + riichi.doraTiles().size)

        val gbSnapshot = snapshot(MahjongVariant.GB, 91)
        val gb = TableRenderLayout.precompute(gbSnapshot)
        assertEquals(144, gbSnapshot.wallCapacity())
        assertEquals(36, gbSnapshot.wallTilesPerSide())
        assertEquals(144, gb.wallTiles().size)
        assertEquals(91, gb.wallTiles().count { it != null })
        assertTrue(gb.doraTiles().isEmpty())
        assertNotNull(gb.wallTiles()[1])
        assertNull(gb.wallTiles()[0])
        assertNull(gb.wallTiles()[92])

        val sichuanSnapshot = snapshot(MahjongVariant.SICHUAN, 55)
        val sichuan = TableRenderLayout.precompute(sichuanSnapshot)
        assertEquals(108, sichuanSnapshot.wallCapacity())
        assertEquals(27, sichuanSnapshot.wallTilesPerSide())
        assertEquals(108, sichuan.wallTiles().size)
        assertEquals(55, sichuan.wallTiles().count { it != null })
        assertTrue(sichuan.doraTiles().isEmpty())
        assertNotNull(sichuan.wallTiles()[19])
        assertNull(sichuan.wallTiles()[18])
        assertNull(sichuan.wallTiles()[74])
    }

    @Test
    fun `gb flower and kong supplements consume the rendered wall from the back`() {
        val flowers = listOf(MahjongTile.PLUM, MahjongTile.ORCHID, MahjongTile.SPRING, MahjongTile.SUMMER)
        val snapshot = snapshot(MahjongVariant.GB, remainingWallCount = 82, kanCount = 1, eastFlowers = flowers)
        val plan = TableRenderLayout.precompute(snapshot)

        assertEquals(82, plan.wallTiles().count { it != null })
        assertNotNull(plan.wallTiles()[5])
        assertNotNull(plan.wallTiles()[86])
        assertNull(plan.wallTiles()[4])
        assertNull(plan.wallTiles()[87])
    }

    @Test
    fun `unselected private hand points reuse public coordinates and layout lists remain immutable`() {
        val plan =
            TableRenderLayout.precompute(
                snapshot(
                    MahjongVariant.GB,
                    remainingWallCount = 91,
                    eastHand = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3),
                    eastSelectedHandTileIndices = listOf(1),
                ),
            )
        val east = plan.seat(SeatWind.EAST)

        assertSame(east.publicHandPoints()[0], east.privateHandPoints()[0])
        assertNotSame(east.publicHandPoints()[1], east.privateHandPoints()[1])
        assertEquals(
            east.publicHandPoints()[1].y() + 0.06,
            east.privateHandPoints()[1].y(),
            1.0e-9,
        )
        assertFailsWith<UnsupportedOperationException> {
            east.publicHandPoints().add(east.publicHandPoints()[0])
        }
    }

    @Test
    fun `wall slots use variant specific tiles per side`() {
        assertEquals(SeatWind.EAST, WallLayout.wallSeat(35, 36))
        assertEquals(SeatWind.SOUTH, WallLayout.wallSeat(36, 36))
        assertEquals(17, WallLayout.wallColumn(35, 36))

        assertEquals(SeatWind.EAST, WallLayout.wallSeat(26, 27))
        assertEquals(SeatWind.SOUTH, WallLayout.wallSeat(27, 27))
        assertEquals(13, WallLayout.wallColumn(26, 27))
        assertEquals(1, WallLayout.wallLayer(26, 27))
        assertEquals(1, WallLayout.wallLayer(27, 27))
    }

    @Test
    fun `an upper wall tile falls into the empty lower slot after a supplement draw`() {
        val plan = TableRenderLayout.precompute(snapshot(MahjongVariant.GB, remainingWallCount = 143, kanCount = 1))
        val unsupportedUpperSlots =
            plan.wallTiles().indices.filter { wallSlot ->
                val placement = plan.wallTiles()[wallSlot] ?: return@filter false
                if (WallLayout.wallLayer(wallSlot, 36) != 1) {
                    return@filter false
                }
                val supportingSlot = WallLayout.supportingLowerSlot(wallSlot, 36)
                supportingSlot < 0 || plan.wallTiles()[supportingSlot] == null
            }

        assertEquals(1, unsupportedUpperSlots.size)
        val fallenTile = plan.wallTiles()[unsupportedUpperSlots.single()]!!
        assertEquals(
            plan.displayCenter().y() + TableRenderConstants.FLAT_TILE_Y,
            fallenTile.point().y(),
            1.0e-9,
        )
    }

    @Test
    fun `layout and geometry wall breaks agree for every dealer and valid first dice total`() {
        val subject = mock(TableRenderSubject::class.java)
        val breakDicePoints = 7
        SeatWind.values().forEach { dealer ->
            for (dicePoints in 2..12) {
                val openDoorIndex = Math.floorMod(dealer.index() + dicePoints - 1, SeatWind.values().size)
                val cases =
                    listOf(
                        Triple(MahjongVariant.GB, 144, openDoorIndex * 36 + 2 * (dicePoints + breakDicePoints)),
                        Triple(MahjongVariant.SICHUAN, 108, openDoorIndex * 27 + 2 * (dicePoints + breakDicePoints)),
                        Triple(MahjongVariant.RIICHI, 136, openDoorIndex * 34 + 2 * breakDicePoints),
                    )

                cases.forEach { (variant, capacity, rawBreakIndex) ->
                    val expectedBreakIndex = Math.floorMod(rawBreakIndex, capacity)
                    val remainingWallCount = if (variant == MahjongVariant.RIICHI) 121 else capacity - 1
                    val plan =
                        TableRenderLayout.precompute(
                            snapshot(
                                variant,
                                remainingWallCount,
                                dealerSeat = dealer,
                                roundIndex = Math.floorMod(dealer.index() + 2, SeatWind.values().size),
                                dicePoints = dicePoints,
                                breakDicePoints = breakDicePoints,
                            ),
                        )
                    val emptySlots = plan.wallTiles().indices.filter { plan.wallTiles()[it] == null }
                    assertEquals(
                        listOf(expectedBreakIndex),
                        emptySlots,
                        "layout variant=$variant, dealer=$dealer, dicePoints=$dicePoints",
                    )

                    `when`(subject.currentVariant()).thenReturn(variant)
                    `when`(subject.dicePoints()).thenReturn(dicePoints)
                    `when`(subject.breakDicePoints()).thenReturn(breakDicePoints)
                    `when`(subject.roundIndex()).thenReturn(Math.floorMod(dealer.index() + 2, SeatWind.values().size))
                    `when`(subject.dealerSeat()).thenReturn(dealer)
                    assertEquals(
                        expectedBreakIndex,
                        TableGeometry.wallBreakTileIndex(subject),
                        "geometry variant=$variant, dealer=$dealer, dicePoints=$dicePoints",
                    )
                }
            }
        }
    }

    private fun snapshot(
        variant: MahjongVariant,
        remainingWallCount: Int,
        kanCount: Int = 0,
        dora: List<MahjongTile> = emptyList(),
        eastFlowers: List<MahjongTile> = emptyList(),
        eastHand: List<MahjongTile> = emptyList(),
        eastSelectedHandTileIndices: List<Int> = emptyList(),
        dealerSeat: SeatWind = SeatWind.EAST,
        roundIndex: Int = 0,
        dicePoints: Int = 3,
        breakDicePoints: Int = 7,
    ): TableRenderSnapshot {
        val seats = EnumMap<SeatWind, TableSeatRenderSnapshot>(SeatWind::class.java)
        SeatWind.values().forEach { wind ->
            val melds =
                if (wind == SeatWind.EAST) {
                    eastFlowers.map { flower -> MeldView(listOf(flower), listOf(false), -1, 0, null) }
                } else {
                    emptyList()
                }
            seats[wind] =
                TableSeatRenderSnapshot(
                    wind,
                    UUID.nameUUIDFromBytes(wind.name.toByteArray()),
                    wind.name,
                    "",
                    if (variant == MahjongVariant.GB) 500 else 25000,
                    false,
                    false,
                    false,
                    true,
                    "",
                    -1,
                    if (wind == SeatWind.EAST) eastSelectedHandTileIndices else emptyList(),
                    -1,
                    0,
                    emptyList(),
                    if (wind == SeatWind.EAST) eastHand else emptyList(),
                    emptyList(),
                    melds,
                    emptyList(),
                    emptyList(),
                )
        }
        return TableRenderSnapshot(
            1L,
            0L,
            "world",
            0.0,
            64.0,
            0.0,
            true,
            false,
            false,
            remainingWallCount,
            kanCount,
            dicePoints,
            breakDicePoints,
            roundIndex,
            0,
            dealerSeat,
            SeatWind.EAST,
            SeatWind.EAST,
            "",
            "",
            "",
            null,
            null,
            dora,
            variant,
            seats,
        )
    }
}
