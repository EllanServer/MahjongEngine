package top.ellan.mahjong.table.core

import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.scene.MeldView
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot
import top.ellan.mahjong.riichi.model.ScoringStick
import java.util.EnumMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SessionRenderLayoutCacheTest {
    @Test
    fun `equivalent layout inputs reuse the immutable plan`() {
        val cache = SessionRenderLayoutCache()
        assertSame(cache.precompute(snapshot(version = 1)), cache.precompute(snapshot(version = 2)))
    }

    @Test
    fun `every geometry dependency invalidates the cached plan`() {
        val baseline = snapshot()
        val changed =
            listOf(
                snapshot(centerX = 4.0),
                snapshot(centerY = 65.0),
                snapshot(centerZ = -3.0),
                snapshot(started = false),
                snapshot(remainingWallCount = 69),
                snapshot(kanCount = 2),
                snapshot(dicePoints = 8),
                snapshot(breakDicePoints = 6),
                snapshot(dealerSeat = SeatWind.SOUTH),
                snapshot(openDoorSeat = SeatWind.NORTH),
                snapshot(doraIndicators = listOf(MahjongTile.P1)),
                snapshot(variant = MahjongVariant.GB),
                snapshot(hand = defaultHand() + MahjongTile.NORTH),
                snapshot(selectedIndices = listOf(3)),
                snapshot(riichiDiscardIndex = 2),
                snapshot(stickLayoutCount = 4),
                snapshot(discards = listOf(MahjongTile.EAST, MahjongTile.WEST)),
                snapshot(melds = listOf(defaultMeld(), defaultMeld())),
                snapshot(cornerSticks = listOf(ScoringStick.P1000)),
                snapshot(riichi = false),
                snapshot(occupied = false),
            )
        changed.forEachIndexed { index, candidate ->
            val cache = SessionRenderLayoutCache()
            val first = cache.precompute(baseline)
            val incremental = cache.precompute(candidate)
            assertNotSame(first, incremental, "dependency index=$index")
            assertEquals(
                top.ellan.mahjong.render.layout.TableRenderLayout
                    .precompute(candidate),
                incremental,
                "incremental layout dependency index=$index",
            )
        }
    }

    @Test
    fun `seat-only changes reuse immutable center and wall geometry`() {
        val cache = SessionRenderLayoutCache()
        val first = cache.precompute(snapshot(selectedIndices = listOf(4)))
        val second = cache.precompute(snapshot(version = 2, selectedIndices = listOf(3)))

        assertNotSame(first, second)
        assertSame(first.displayCenter(), second.displayCenter())
        assertSame(first.tableCenter(), second.tableCenter())
        assertSame(first.wallTiles(), second.wallTiles())
        SeatWind.values().forEach { wind -> assertNotSame(first.seat(wind), second.seat(wind)) }
    }

    @Test
    fun `wall-only changes reuse every immutable seat plan`() {
        val cache = SessionRenderLayoutCache()
        val first = cache.precompute(snapshot(remainingWallCount = 70))
        val second = cache.precompute(snapshot(version = 2, remainingWallCount = 69))

        assertNotSame(first.wallTiles(), second.wallTiles())
        SeatWind.values().forEach { wind -> assertSame(first.seat(wind), second.seat(wind)) }
    }

    @Test
    fun `clear forces recomputation`() {
        val cache = SessionRenderLayoutCache()
        val input = snapshot()
        val first = cache.precompute(input)
        cache.clear()
        assertNotSame(first, cache.precompute(input))
    }
}

private fun snapshot(
    version: Long = 1,
    centerX: Double = 3.0,
    centerY: Double = 64.0,
    centerZ: Double = -2.0,
    started: Boolean = true,
    remainingWallCount: Int = 70,
    kanCount: Int = 1,
    dicePoints: Int = 7,
    breakDicePoints: Int = 5,
    dealerSeat: SeatWind = SeatWind.EAST,
    openDoorSeat: SeatWind = SeatWind.WEST,
    doraIndicators: List<MahjongTile> = listOf(MahjongTile.M1),
    variant: MahjongVariant = MahjongVariant.RIICHI,
    hand: List<MahjongTile> = defaultHand(),
    selectedIndices: List<Int> = listOf(4),
    riichiDiscardIndex: Int = 1,
    stickLayoutCount: Int = 3,
    discards: List<MahjongTile> = listOf(MahjongTile.EAST, MahjongTile.SOUTH),
    melds: List<MeldView> = listOf(defaultMeld()),
    cornerSticks: List<ScoringStick> = listOf(ScoringStick.P100),
    riichi: Boolean = true,
    occupied: Boolean = true,
): TableRenderSnapshot {
    val seats = EnumMap<SeatWind, TableSeatRenderSnapshot>(SeatWind::class.java)
    SeatWind.values().forEach { wind ->
        seats[wind] =
            TableSeatRenderSnapshot(
                wind,
                if (occupied) UUID(0, wind.index().toLong() + 1) else null,
                wind.name,
                wind.name,
                25_000,
                riichi,
                true,
                false,
                true,
                "viewer-${wind.name}",
                selectedIndices.lastOrNull() ?: -1,
                selectedIndices,
                riichiDiscardIndex,
                stickLayoutCount,
                emptyList(),
                hand,
                discards,
                melds,
                emptyList(),
                cornerSticks,
            )
    }
    return TableRenderSnapshot(
        version,
        0,
        "world",
        centerX,
        centerY,
        centerZ,
        started,
        false,
        false,
        remainingWallCount,
        kanCount,
        dicePoints,
        breakDicePoints,
        1,
        0,
        dealerSeat,
        SeatWind.SOUTH,
        openDoorSeat,
        "waiting",
        "rules",
        "center-$version",
        null,
        null,
        doraIndicators,
        variant,
        seats,
    )
}

private fun defaultHand(): List<MahjongTile> =
    listOf(
        MahjongTile.M1,
        MahjongTile.M2,
        MahjongTile.M3,
        MahjongTile.P1,
        MahjongTile.P2,
        MahjongTile.P3,
        MahjongTile.S1,
        MahjongTile.S2,
        MahjongTile.S3,
        MahjongTile.EAST,
        MahjongTile.EAST,
    )

private fun defaultMeld(): MeldView =
    MeldView(
        listOf(MahjongTile.P7, MahjongTile.P7, MahjongTile.P7),
        listOf(false, false, false),
        1,
        90,
        null,
    )
