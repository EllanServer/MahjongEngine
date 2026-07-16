package top.ellan.mahjong.render

import org.bukkit.Location
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.display.DisplayEntities
import top.ellan.mahjong.render.layout.DiscardLayout
import top.ellan.mahjong.render.layout.TableRenderLayout
import top.ellan.mahjong.render.layout.WallLayout
import top.ellan.mahjong.render.scene.MeldView
import top.ellan.mahjong.render.scene.TableGeometry
import top.ellan.mahjong.render.scene.TableRenderConstants
import top.ellan.mahjong.render.scene.TableRenderer
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot
import top.ellan.mahjong.riichi.model.ScoringStick
import top.ellan.mahjong.table.core.MahjongTableSession
import java.util.EnumMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableRendererTest {
    private val tileWidth = 0.1125
    private val tileHeight = 0.15
    private val tilePadding = 0.01

    @Test
    fun `wall indexing stays within four seat winds`() {
        assertEquals(SeatWind.EAST, WallLayout.wallSeat(0))
        assertEquals(SeatWind.EAST, WallLayout.wallSeat(33))
        assertEquals(SeatWind.SOUTH, WallLayout.wallSeat(34))
        assertEquals(SeatWind.WEST, WallLayout.wallSeat(68))
        assertEquals(SeatWind.NORTH, WallLayout.wallSeat(102))
        assertEquals(SeatWind.NORTH, WallLayout.wallSeat(135))
    }

    @Test
    fun `wall indexing computes expected column and layer`() {
        assertEquals(0, WallLayout.wallColumn(0))
        assertEquals(0, WallLayout.wallColumn(1))
        assertEquals(8, WallLayout.wallColumn(16))
        assertEquals(8, WallLayout.wallColumn(17))
        assertEquals(16, WallLayout.wallColumn(32))
        assertEquals(16, WallLayout.wallColumn(33))
        assertEquals(0, WallLayout.wallColumn(34))
        assertEquals(16, WallLayout.wallColumn(135))
        assertEquals(1, WallLayout.wallLayer(0))
        assertEquals(0, WallLayout.wallLayer(1))
        assertEquals(1, WallLayout.wallLayer(34))
        assertEquals(0, WallLayout.wallLayer(135))
    }

    @Test
    fun `supported upper wall tiles keep the normal stacked layer offset`() {
        val stackedOffset = TableRenderConstants.TILE_DEPTH + TableRenderConstants.TILE_PADDING
        assertEquals(stackedOffset, TableGeometry.wallLayerYOffset(1), 1.0e-9)

        val plan = TableRenderLayout.precompute(startedSnapshot())
        val upperIndex =
            (0 until plan.wallTiles().lastIndex step 2).first { index ->
                plan.wallTiles()[index] != null && plan.wallTiles()[index + 1] != null
            }
        val upper = plan.wallTiles()[upperIndex]!!
        val lower = plan.wallTiles()[upperIndex + 1]!!
        assertEquals(stackedOffset, upper.point().y() - lower.point().y(), 1.0e-9)
    }

    @Test
    fun `riichi discard uses sideways footprint and yaw`() {
        assertTrue(
            DiscardLayout.discardFootprint(tileWidth, tileHeight, true) > DiscardLayout.discardFootprint(tileWidth, tileHeight, false),
        )
        assertEquals(-180.0f, DiscardLayout.discardYaw(SeatWind.EAST, true))
        assertEquals(90.0f, DiscardLayout.discardYaw(SeatWind.SOUTH, true))
        assertEquals(0.0f, DiscardLayout.discardYaw(SeatWind.WEST, true))
        assertEquals(-90.0f, DiscardLayout.discardYaw(SeatWind.NORTH, true))
    }

    @Test
    fun `discard row footprint accounts for sideways riichi tile`() {
        val plainRow = DiscardLayout.rowFootprint(tileWidth, tileHeight, tilePadding, 0, 6, -1)
        val riichiRow = DiscardLayout.rowFootprint(tileWidth, tileHeight, tilePadding, 0, 6, 2)
        assertTrue(riichiRow > plainRow)
    }

    @Test
    fun `async layout plan precomputes wall and hand placements`() {
        val snapshot = startedSnapshot()

        val plan = TableRenderLayout.precompute(snapshot)

        assertEquals(136, plan.wallTiles().size)
        assertEquals(70 + 14 - 2, plan.wallTiles().count { it != null })
        assertEquals(2, plan.doraTiles().size)
        assertEquals(13, plan.seat(SeatWind.EAST).publicHandPoints().size)
        assertEquals(13, plan.seat(SeatWind.EAST).privateHandPoints().size)
        assertEquals(2, plan.seat(SeatWind.EAST).stickPlacements().size)
        assertTrue(plan.borderSpanX() > 0.0)
        assertTrue(plan.borderSpanZ() > 0.0)
    }

    @Test
    fun `table layout follows upstream seat sides`() {
        val plan = TableRenderLayout.precompute(startedSnapshot())

        assertTrue(
            plan
                .seat(SeatWind.EAST)
                .privateHandPoints()
                .first()
                .x() > 0.0,
        )
        assertTrue(plan.seat(SeatWind.SOUTH).handBase().z() < 0.0)
        assertTrue(plan.seat(SeatWind.WEST).handBase().x() < 0.0)
        assertTrue(plan.seat(SeatWind.NORTH).handBase().z() > 0.0)

        val eastWall =
            plan
                .wallTiles()
                .withIndex()
                .first { it.value != null && WallLayout.wallSeat(it.index) == SeatWind.EAST }
                .value!!
        val southWall =
            plan
                .wallTiles()
                .withIndex()
                .first { it.value != null && WallLayout.wallSeat(it.index) == SeatWind.SOUTH }
                .value!!
        val westWall =
            plan
                .wallTiles()
                .withIndex()
                .first { it.value != null && WallLayout.wallSeat(it.index) == SeatWind.WEST }
                .value!!
        val northWall =
            plan
                .wallTiles()
                .withIndex()
                .first { it.value != null && WallLayout.wallSeat(it.index) == SeatWind.NORTH }
                .value!!

        assertTrue(eastWall.point().x() > 0.0)
        assertTrue(southWall.point().z() < 0.0)
        assertTrue(westWall.point().x() < 0.0)
        assertTrue(northWall.point().z() > 0.0)
    }

    @Test
    fun `waiting snapshot skips live wall and dora layout`() {
        val base = startedSnapshot()
        val snapshot =
            TableRenderSnapshot(
                base.version(),
                base.cancellationNonce(),
                base.worldName(),
                base.centerX(),
                base.centerY(),
                base.centerZ(),
                false,
                false,
                false,
                0,
                0,
                0,
                0,
                0,
                0,
                SeatWind.EAST,
                SeatWind.EAST,
                SeatWind.EAST,
                base.waitingDisplaySummary(),
                base.ruleDisplaySummary(),
                "waiting",
                null,
                null,
                emptyList(),
                base.seats(),
            )

        val plan = TableRenderLayout.precompute(snapshot)

        assertTrue(plan.wallTiles().isEmpty())
        assertTrue(plan.doraTiles().isEmpty())
    }

    @Test
    fun `wall layout keeps physical slots stable when one live wall tile is drawn`() {
        val base = startedSnapshot()
        val before = TableRenderLayout.precompute(base)
        val after =
            TableRenderLayout.precompute(
                TableRenderSnapshot(
                    base.version(),
                    base.cancellationNonce(),
                    base.worldName(),
                    base.centerX(),
                    base.centerY(),
                    base.centerZ(),
                    base.started(),
                    base.gameFinished(),
                    base.roundStartInProgress(),
                    69,
                    base.kanCount(),
                    base.dicePoints(),
                    base.breakDicePoints(),
                    base.roundIndex(),
                    base.honbaCount(),
                    base.dealerSeat(),
                    base.currentSeat(),
                    base.openDoorSeat(),
                    base.waitingDisplaySummary(),
                    base.ruleDisplaySummary(),
                    base.publicCenterText(),
                    base.lastPublicDiscardPlayerId(),
                    base.lastPublicDiscardTile(),
                    base.doraIndicators(),
                    base.seats(),
                ),
            )

        val changedSlots =
            before.wallTiles().indices.count { index ->
                !samePlacement(before.wallTiles()[index], after.wallTiles()[index])
            }

        assertEquals(1, changedSlots)
    }

    @Test
    fun `wall layout never contains concealed tile identities`() {
        val wallTiles = TableRenderLayout.precompute(startedSnapshot()).wallTiles().filterNotNull()

        assertTrue(wallTiles.isNotEmpty())
        wallTiles.forEach { placement ->
            assertEquals(MahjongTile.UNKNOWN, placement.tile())
            assertEquals(DisplayEntities.TileRenderPose.FLAT_FACE_DOWN, placement.pose())
        }
    }

    @Test
    fun `public hand specs hide owner while remaining public to others`() {
        val renderer = TableRenderer()
        val snapshot = startedSnapshot()
        val seat = snapshot.seat(SeatWind.EAST)
        val plan = TableRenderLayout.precompute(snapshot).seat(SeatWind.EAST)
        val session = mock(MahjongTableSession::class.java)

        `when`(session.center()).thenReturn(Location(null, snapshot.centerX(), snapshot.centerY(), snapshot.centerZ()))
        `when`(session.currentVariant()).thenReturn(top.ellan.mahjong.model.MahjongVariant.RIICHI)

        val spec = renderer.renderHandPublicTileSpecs(session, snapshot, seat, plan, 0).single() as DisplayEntities.TileDisplaySpec

        assertEquals(MahjongTile.UNKNOWN, spec.tile())
        assertEquals(null, spec.privateViewers())
        assertEquals(listOf(seat.playerId()), spec.hiddenViewers())
    }

    @Test
    fun `kakan tile stays on table plane and moves inward`() {
        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val seats = EnumMap<SeatWind, TableSeatRenderSnapshot>(SeatWind::class.java)
        seats[SeatWind.EAST] =
            seatSnapshot(
                wind = SeatWind.EAST,
                playerId = eastId,
                melds =
                    listOf(
                        MeldView(
                            listOf(MahjongTile.M5, MahjongTile.M5, MahjongTile.M5),
                            listOf(false, false, false),
                            1,
                            90,
                            MahjongTile.M5,
                        ),
                    ),
            )
        seats[SeatWind.SOUTH] = seatSnapshot(SeatWind.SOUTH, UUID.fromString("00000000-0000-0000-0000-000000000002"))
        seats[SeatWind.WEST] = seatSnapshot(SeatWind.WEST, UUID.fromString("00000000-0000-0000-0000-000000000003"))
        seats[SeatWind.NORTH] = seatSnapshot(SeatWind.NORTH, UUID.fromString("00000000-0000-0000-0000-000000000004"))
        val snapshot =
            TableRenderSnapshot(
                1L,
                0L,
                "world",
                0.0,
                64.0,
                0.0,
                true,
                false,
                false,
                70,
                0,
                6,
                6,
                0,
                1,
                SeatWind.EAST,
                SeatWind.SOUTH,
                SeatWind.EAST,
                "waiting",
                "rules",
                "center",
                null,
                null,
                listOf(MahjongTile.M1, MahjongTile.P1),
                seats,
            )

        val meldPlacements = TableRenderLayout.precompute(snapshot).seat(SeatWind.EAST).meldPlacements()
        assertEquals(4, meldPlacements.size)
        val claimTile = meldPlacements[1]
        val addedKanTile = meldPlacements[3]
        assertEquals(MahjongTile.M5, addedKanTile.tile())
        assertEquals(claimTile.point().y(), addedKanTile.point().y(), 1e-9)
        assertTrue(centerDistanceSquared(addedKanTile.point(), 0.0, 0.0) <= centerDistanceSquared(claimTile.point(), 0.0, 0.0) + 1e-9)
    }

    @Test
    fun `claim tile index maps to left middle right slots visually`() {
        val left = horizontalTileRankForClaimIndex(SeatWind.EAST, 0)
        val middle = horizontalTileRankForClaimIndex(SeatWind.EAST, 1)
        val right = horizontalTileRankForClaimIndex(SeatWind.EAST, 2)
        assertEquals(2, left)
        assertEquals(1, middle)
        assertEquals(0, right)
    }

    private fun startedSnapshot(): TableRenderSnapshot {
        val seats = EnumMap<SeatWind, TableSeatRenderSnapshot>(SeatWind::class.java)
        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        seats[SeatWind.EAST] =
            seatSnapshot(
                wind = SeatWind.EAST,
                playerId = eastId,
                hand = List(13) { MahjongTile.M1 },
                discards = listOf(MahjongTile.EAST, MahjongTile.SOUTH),
                riichi = true,
                riichiDiscardIndex = 1,
                scoringSticks = listOf(ScoringStick.P1000),
                cornerSticks = listOf(ScoringStick.P100),
            )
        seats[SeatWind.SOUTH] = seatSnapshot(SeatWind.SOUTH, UUID.fromString("00000000-0000-0000-0000-000000000002"))
        seats[SeatWind.WEST] = seatSnapshot(SeatWind.WEST, UUID.fromString("00000000-0000-0000-0000-000000000003"))
        seats[SeatWind.NORTH] = seatSnapshot(SeatWind.NORTH, UUID.fromString("00000000-0000-0000-0000-000000000004"))
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
            70,
            0,
            6,
            6,
            0,
            1,
            SeatWind.EAST,
            SeatWind.SOUTH,
            SeatWind.EAST,
            "waiting",
            "rules",
            "center",
            null,
            null,
            listOf(MahjongTile.M1, MahjongTile.P1),
            seats,
        )
    }

    private fun seatSnapshot(
        wind: SeatWind,
        playerId: UUID,
        hand: List<MahjongTile> = emptyList(),
        discards: List<MahjongTile> = emptyList(),
        riichi: Boolean = false,
        riichiDiscardIndex: Int = -1,
        melds: List<MeldView> = emptyList(),
        scoringSticks: List<ScoringStick> = emptyList(),
        cornerSticks: List<ScoringStick> = emptyList(),
    ) = TableSeatRenderSnapshot(
        wind,
        playerId,
        wind.name.lowercase(),
        wind.name,
        25000,
        riichi,
        true,
        false,
        true,
        "",
        -1,
        emptyList(),
        riichiDiscardIndex,
        scoringSticks.size + cornerSticks.size,
        emptyList(),
        hand,
        discards,
        melds,
        scoringSticks,
        cornerSticks,
    )

    private fun samePlacement(
        left: TableRenderLayout.TilePlacement?,
        right: TableRenderLayout.TilePlacement?,
    ): Boolean {
        if (left == null || right == null) {
            return left == right
        }
        return left.point() == right.point() &&
            left.yaw() == right.yaw() &&
            left.tile() == right.tile() &&
            left.pose() == right.pose()
    }

    private fun horizontalTileRankForClaimIndex(
        wind: SeatWind,
        claimIndex: Int,
    ): Int {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val seats = EnumMap<SeatWind, TableSeatRenderSnapshot>(SeatWind::class.java)
        seats[wind] =
            seatSnapshot(
                wind = wind,
                playerId = playerId,
                melds =
                    listOf(
                        MeldView(
                            listOf(MahjongTile.M5, MahjongTile.M5, MahjongTile.M5),
                            listOf(false, false, false),
                            claimIndex,
                            90,
                            null,
                        ),
                    ),
            )
        for (other in SeatWind.values()) {
            if (other != wind) {
                seats[other] = seatSnapshot(other, UUID.randomUUID())
            }
        }
        val snapshot =
            TableRenderSnapshot(
                1L,
                0L,
                "world",
                0.0,
                64.0,
                0.0,
                true,
                false,
                false,
                70,
                0,
                6,
                6,
                0,
                1,
                SeatWind.EAST,
                SeatWind.SOUTH,
                SeatWind.EAST,
                "waiting",
                "rules",
                "center",
                null,
                null,
                listOf(MahjongTile.M1),
                seats,
            )
        val placements =
            TableRenderLayout
                .precompute(snapshot)
                .seat(wind)
                .meldPlacements()
                .take(3)
        val horizontal = placements.first { it.yaw() != seatYawFor(wind) }
        val ordered = placements.sortedBy { leftToRightScalar(it.point(), wind) }
        return ordered.indexOf(horizontal)
    }

    private fun leftToRightScalar(
        point: TableRenderLayout.Point,
        wind: SeatWind,
    ): Double =
        when (wind) {
            SeatWind.EAST -> -point.z()
            SeatWind.SOUTH -> -point.x()
            SeatWind.WEST -> point.z()
            SeatWind.NORTH -> point.x()
        }

    private fun seatYawFor(wind: SeatWind): Float =
        when (wind) {
            SeatWind.EAST -> -90.0f
            SeatWind.SOUTH -> 180.0f
            SeatWind.WEST -> 90.0f
            SeatWind.NORTH -> 0.0f
        }

    private fun centerDistanceSquared(
        point: TableRenderLayout.Point,
        centerX: Double,
        centerZ: Double,
    ): Double {
        val dx = point.x() - centerX
        val dz = point.z() - centerZ
        return dx * dx + dz * dz
    }
}
