package top.ellan.mahjong.table.core.round

import top.ellan.mahjong.gb.jni.GbFanEntry
import top.ellan.mahjong.gb.jni.GbFanRequest
import top.ellan.mahjong.gb.jni.GbFanResponse
import top.ellan.mahjong.gb.jni.GbScoreDelta
import top.ellan.mahjong.gb.jni.GbTingRequest
import top.ellan.mahjong.gb.jni.GbTingResponse
import top.ellan.mahjong.gb.jni.GbWinRequest
import top.ellan.mahjong.gb.jni.GbWinResponse
import top.ellan.mahjong.gb.runtime.GbNativeRulesGateway
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.OpeningDiceRoll
import java.util.EnumMap
import java.util.UUID
import java.util.function.IntSupplier
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GbOfficialRulesRegressionTest {
    @Test
    fun `gb opening uses both dice totals deals four tile blocks and leaves one exhaustible wall`() {
        val tileCycle = MahjongTile.values().filter { it != MahjongTile.UNKNOWN && !it.isFlower && !it.isRedFive }
        val sourceWall = List(144) { tileCycle[it % tileCycle.size] }
        val controller = controller(CapturingGateway(), sourceWall)
        controller.setPendingDiceRoll(OpeningDiceRoll(1, 2, 3, 4))
        controller.startRound()

        val reordered = GbRoundSupport.reorderWallForDice(sourceWall, 3, 7, 0)
        assertEquals(sourceWall[92], reordered.first())
        assertEquals(
            multiset(reordered.slice((0..3) + (16..19) + (32..35) + listOf(48, 52))),
            multiset(controller.hand(player(SeatWind.EAST))),
        )
        assertEquals(
            multiset(reordered.slice((4..7) + (20..23) + (36..39) + listOf(49))),
            multiset(controller.hand(player(SeatWind.SOUTH))),
        )
        assertEquals(
            multiset(reordered.slice((8..11) + (24..27) + (40..43) + listOf(50))),
            multiset(controller.hand(player(SeatWind.WEST))),
        )
        assertEquals(
            multiset(reordered.slice((12..15) + (28..31) + (44..47) + listOf(51))),
            multiset(controller.hand(player(SeatWind.NORTH))),
        )
        assertEquals(91, controller.remainingWallCount())
        assertEquals(reordered.drop(53), currentWall(controller))
    }

    @Test
    fun `gb wall opening follows every dealer and valid first dice total`() {
        val breakDicePoints = 7
        SeatWind.values().forEach { dealer ->
            for (directionDicePoints in 2..12) {
                val openDoorIndex = Math.floorMod(dealer.index() + directionDicePoints - 1, SeatWind.values().size)
                val expectedFirstTileIndex =
                    Math.floorMod(
                        openDoorIndex * 36 + 2 * (directionDicePoints + breakDicePoints),
                        144,
                    )
                val sourceWall = MutableList(144) { MahjongTile.M1 }
                sourceWall[expectedFirstTileIndex] = MahjongTile.P9
                sourceWall[Math.floorMod(expectedFirstTileIndex + 1, sourceWall.size)] = MahjongTile.S9

                val reordered =
                    GbRoundSupport.reorderWallForDice(
                        sourceWall,
                        directionDicePoints,
                        breakDicePoints,
                        dealer.index(),
                    )

                assertEquals(
                    listOf(MahjongTile.P9, MahjongTile.S9),
                    reordered.take(2),
                    "dealer=$dealer, directionDicePoints=$directionDicePoints",
                )
            }
        }
    }

    @Test
    fun `initial flowers remain concealed until their owners choose to expose them`() {
        val ordinaryTiles = MahjongTile.values().filter { it != MahjongTile.UNKNOWN && !it.isFlower && !it.isRedFive }
        val reordered = MutableList(144) { ordinaryTiles[it % ordinaryTiles.size] }
        reordered[0] = MahjongTile.PLUM
        reordered[16] = MahjongTile.ORCHID
        reordered[4] = MahjongTile.BAMBOO
        reordered[8] = MahjongTile.CHRYSANTHEMUM
        reordered[143] = MahjongTile.SPRING
        reordered[142] = MahjongTile.M9
        reordered[141] = MahjongTile.SUMMER
        reordered[140] = MahjongTile.P9
        reordered[139] = MahjongTile.AUTUMN
        reordered[138] = MahjongTile.S9
        reordered[137] = MahjongTile.WINTER
        reordered[136] = MahjongTile.M8

        val sourceWall = sourceWallForReordered(reordered, 2, 2, 0)
        val controller = controller(CapturingGateway(), sourceWall)
        controller.setPendingDiceRoll(OpeningDiceRoll(1, 1, 1, 1))
        controller.startRound()

        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)
        SeatWind.values().forEach { wind -> assertEquals(emptyList(), currentFlowers(controller, player(wind))) }
        assertTrue(controller.hand(east).containsAll(listOf(MahjongTile.PLUM, MahjongTile.ORCHID)))
        assertTrue(controller.hand(south).contains(MahjongTile.BAMBOO))
        assertTrue(controller.hand(west).contains(MahjongTile.CHRYSANTHEMUM))
        assertFalse(controller.hand(north).any { it.isFlower })
        assertEquals(14, controller.hand(east).size)
        assertEquals(13, controller.hand(south).size)
        assertEquals(13, controller.hand(west).size)
        assertEquals(13, controller.hand(north).size)
        assertEquals(reordered.drop(53), currentWall(controller))
        assertEquals(91, controller.remainingWallCount())
    }

    @Test
    fun `official gb match advances exactly sixteen hands and conserves net zero points`() {
        val rule =
            MahjongRule(
                length = MahjongRule.GameLength.FOUR_WIND,
                startingPoints = 0,
                minPointsToWin = 0,
            )
        val controller = controller(CapturingGateway(), rule = rule)

        repeat(16) { handIndex ->
            controller.startRound()
            assertEquals(handIndex / 4, controller.roundWind().index())
            assertEquals(handIndex % 4, controller.dealerSeat().index())
            assertEquals(0, SeatWind.values().sumOf { wind -> controller.points(player(wind)) })

            assertTrue(controller.declareTsumo(player(controller.dealerSeat())))

            assertEquals(0, SeatWind.values().sumOf { wind -> controller.points(player(wind)) })
            assertEquals(handIndex == 15, controller.gameFinished())
        }
    }

    @Test
    fun `gb match honors configured starting points and one game length`() {
        val rule =
            MahjongRule(
                length = MahjongRule.GameLength.ONE_GAME,
                startingPoints = 700,
                minPointsToWin = 700,
            )
        val controller = controller(CapturingGateway(), rule = rule)

        controller.startRound()
        SeatWind.values().forEach { wind -> assertEquals(700, controller.points(player(wind))) }

        assertTrue(controller.declareTsumo(player(controller.dealerSeat())))
        assertTrue(controller.gameFinished())
    }

    @Test
    fun `gb match extends a configured base length until its cap when target is unmet`() {
        val rule =
            MahjongRule(
                length = MahjongRule.GameLength.ONE_GAME,
                startingPoints = 1_000,
                minPointsToWin = 100_000,
            )
        val controller = controller(CapturingGateway(), rule = rule)

        repeat(4) { handIndex ->
            controller.startRound()
            assertTrue(controller.declareTsumo(player(controller.dealerSeat())))
            assertEquals(handIndex == 3, controller.gameFinished())
        }
    }

    @Test
    fun `flower replacement comes from the tail while normal draws come from the front`() {
        val gateway = CapturingGateway(fanResponse = GbFanResponse(false, 0, emptyList(), "cannot win"))
        val controller = controller(gateway)
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        controller.startRound()
        forceHand(
            controller,
            east,
            listOf(
                "NORTH",
                "M2",
                "M3",
                "M4",
                "M5",
                "M6",
                "M7",
                "P1",
                "P2",
                "P3",
                "S1",
                "S2",
                "S3",
                "EAST",
            ),
        )
        forceNonDealerHandsWithoutNorth(controller)
        forceWall(controller, listOf(MahjongTile.PLUM, MahjongTile.M8, MahjongTile.S8, MahjongTile.P9))

        assertTrue(controller.discard(east, 0))
        assertEquals(MahjongTile.PLUM, controller.hand(south).last())
        assertEquals(emptyList(), currentFlowers(controller, south))
        val flowerIndex = controller.hand(south).indexOf(MahjongTile.PLUM)
        assertTrue(controller.declareFlower(south, flowerIndex))
        assertEquals(MahjongTile.P9, controller.hand(south).last())
        assertEquals(listOf(MahjongTile.PLUM), currentFlowers(controller, south))
        assertEquals(listOf(MahjongTile.M8, MahjongTile.S8), currentWall(controller))
    }

    @Test
    fun `a retained flower blocks winning until it is exposed or discarded`() {
        val gateway = CapturingGateway()
        val controller = controller(gateway)
        val east = player(SeatWind.EAST)
        controller.startRound()
        forceHand(
            controller,
            east,
            listOf(
                "PLUM",
                "M1",
                "M1",
                "M1",
                "M2",
                "M3",
                "M4",
                "P2",
                "P3",
                "P4",
                "S2",
                "S3",
                "S4",
                "EAST",
            ),
        )

        assertFalse(controller.canWinByTsumo(east))
        assertFalse(controller.declareTsumo(east))
        assertTrue(gateway.fanRequests.isEmpty())
    }

    @Test
    fun `the final wall tile remains drawable before the hand becomes exhaustive`() {
        val gateway = CapturingGateway(fanResponse = GbFanResponse(false, 0, emptyList(), "cannot win"))
        val controller = controller(gateway)
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        controller.startRound()
        forceHand(
            controller,
            east,
            listOf(
                "NORTH",
                "M2",
                "M3",
                "M4",
                "M5",
                "M6",
                "M7",
                "P1",
                "P2",
                "P3",
                "S1",
                "S2",
                "S3",
                "EAST",
            ),
        )
        forceNonDealerHandsWithoutNorth(controller)
        forceWall(controller, listOf(MahjongTile.P9))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.started())
        assertEquals(MahjongTile.P9, controller.hand(south).last())
        assertEquals(0, controller.remainingWallCount())

        assertTrue(controller.discard(south, controller.hand(south).lastIndex))
        assertFalse(controller.started())
        assertEquals("DRAW", controller.lastResolution()?.title)
    }

    @Test
    fun `flower fan does not satisfy the eight fan winning requirement`() {
        val sevenFanPlusFlower =
            listOf(
                GbFanEntry("BASE_SEVEN", 7, 1),
                GbFanEntry("HUAPAI", 1, 1),
            )
        val gateway =
            CapturingGateway(
                fanResponse = GbFanResponse(true, 8, sevenFanPlusFlower, null),
                winResponse = { request ->
                    GbWinResponse(
                        true,
                        if (request.winType == "SELF_DRAW") "TSUMO" else "RON",
                        8,
                        sevenFanPlusFlower,
                        officialScoreDeltas(request, 8),
                        null,
                    )
                },
            )
        val controller = controller(gateway)
        val east = player(SeatWind.EAST)
        controller.startRound()

        assertFalse(controller.canWinByTsumo(east))
        assertFalse(controller.declareTsumo(east))
        assertTrue(controller.started())
    }

    @Test
    fun `logical native score seats are applied to the rotated physical players`() {
        val gateway = CapturingGateway()
        val controller = controller(gateway)
        controller.startRound()
        assertTrue(controller.declareTsumo(player(SeatWind.EAST)))

        controller.startRound()
        val before = SeatWind.values().associateWith { wind -> controller.points(player(wind)) }
        assertEquals(SeatWind.SOUTH, controller.dealerSeat())
        assertTrue(controller.declareTsumo(player(SeatWind.SOUTH)))

        assertEquals(48, controller.points(player(SeatWind.SOUTH)) - before.getValue(SeatWind.SOUTH))
        assertEquals(-16, controller.points(player(SeatWind.EAST)) - before.getValue(SeatWind.EAST))
        assertEquals(-16, controller.points(player(SeatWind.WEST)) - before.getValue(SeatWind.WEST))
        assertEquals(-16, controller.points(player(SeatWind.NORTH)) - before.getValue(SeatWind.NORTH))
    }

    @Test
    fun `score deltas aggregate repeated seats before an atomic zero sum update`() {
        val deltas =
            listOf(
                GbScoreDelta("EAST", 10),
                GbScoreDelta("EAST", 20),
                GbScoreDelta("SOUTH", -10),
                GbScoreDelta("WEST", -10),
                GbScoreDelta("NORTH", -10),
            )
        val gateway = CapturingGateway(winResponse = { request -> validWinResponse(request, deltas) })
        val controller = controller(gateway)
        val before = SeatWind.values().associateWith { wind -> controller.points(player(wind)) }
        controller.startRound()

        assertTrue(controller.declareTsumo(player(SeatWind.EAST)))

        assertEquals(30, controller.points(player(SeatWind.EAST)) - before.getValue(SeatWind.EAST))
        assertEquals(-10, controller.points(player(SeatWind.SOUTH)) - before.getValue(SeatWind.SOUTH))
        assertEquals(-10, controller.points(player(SeatWind.WEST)) - before.getValue(SeatWind.WEST))
        assertEquals(-10, controller.points(player(SeatWind.NORTH)) - before.getValue(SeatWind.NORTH))
    }

    @Test
    fun `invalid or non conserving score responses cannot partially mutate table points`() {
        val invalidResponses =
            listOf(
                listOf(GbScoreDelta("EAST", 1)),
                listOf(GbScoreDelta("EAST", 1), GbScoreDelta("NOT_A_SEAT", -1)),
            )
        for (deltas in invalidResponses) {
            val gateway = CapturingGateway(winResponse = { request -> validWinResponse(request, deltas) })
            val controller = controller(gateway)
            controller.startRound()
            val before = SeatWind.values().associateWith { wind -> controller.points(player(wind)) }

            assertFailsWith<IllegalStateException> { controller.declareTsumo(player(SeatWind.EAST)) }

            SeatWind.values().forEach { wind -> assertEquals(before.getValue(wind), controller.points(player(wind))) }
            assertTrue(controller.started())
        }
    }

    @Test
    fun `unknown player ids do not resolve to east seat`() {
        val controller = controller(CapturingGateway())
        val seatOf = GbTableRoundController::class.java.getDeclaredMethod("seatOf", UUID::class.java)
        seatOf.isAccessible = true

        assertNull(seatOf.invoke(controller, UUID.randomUUID()))
        assertNull(seatOf.invoke(controller, null as Any?))
    }

    @Test
    fun `discard after kong replacement is not marked as robbing kong in gb`() {
        val gateway = CapturingGateway(fanResponse = GbFanResponse(false, 0, emptyList(), "cannot win"))
        val controller = controller(gateway)
        val east = player(SeatWind.EAST)
        controller.startRound()
        forceHand(
            controller,
            east,
            listOf(
                "M1",
                "M1",
                "M1",
                "M1",
                "M2",
                "M3",
                "M4",
                "P2",
                "P3",
                "P4",
                "S2",
                "S3",
                "S4",
                "EAST",
            ),
        )
        forceReplacementTile(controller, MahjongTile.P9)

        assertTrue(controller.declareKan(east, "m1"))
        gateway.fanRequests.clear()
        val replacementIndex = controller.hand(east).indexOf(MahjongTile.P9)
        assertTrue(replacementIndex >= 0)
        assertTrue(controller.discard(east, replacementIndex))

        val discardRequests = gateway.fanRequests.filter { it.winType == "DISCARD" }
        assertTrue(discardRequests.isNotEmpty())
        assertTrue(discardRequests.all { "AFTER_KONG" !in it.flags && "ROBBING_KONG" !in it.flags })
    }

    @Test
    fun `last of kind on discard requires three copies visible before the winning discard`() {
        val onlyTwoPreviouslyVisible = discardWinFlagsWithPriorCopies(2)
        val threePreviouslyVisible = discardWinFlagsWithPriorCopies(3)

        assertTrue(onlyTwoPreviouslyVisible.isNotEmpty())
        assertTrue(onlyTwoPreviouslyVisible.all { "LAST_OF_KIND" !in it })
        assertTrue(threePreviouslyVisible.isNotEmpty())
        assertTrue(threePreviouslyVisible.all { "LAST_OF_KIND" in it })
    }

    private fun discardWinFlagsWithPriorCopies(priorCopies: Int): List<List<String>> {
        val gateway = CapturingGateway(fanResponse = GbFanResponse(false, 0, emptyList(), "cannot win"))
        val controller = controller(gateway)
        val east = player(SeatWind.EAST)
        controller.startRound()
        forceHand(
            controller,
            east,
            listOf(
                "M1",
                "M2",
                "M3",
                "M4",
                "M5",
                "M6",
                "M7",
                "P1",
                "P2",
                "P3",
                "S1",
                "S2",
                "S3",
                "EAST",
            ),
        )
        forceDiscards(controller, player(SeatWind.WEST), List(priorCopies) { MahjongTile.M1 })
        gateway.fanRequests.clear()

        assertTrue(controller.discard(east, 0))
        return gateway.fanRequests.filter { it.winType == "DISCARD" }.map { it.flags }
    }

    private fun controller(
        gateway: GbNativeRulesGateway,
        wall: List<MahjongTile>? = null,
        rule: MahjongRule = MahjongRule(),
    ): GbTableRoundController {
        val seats = EnumMap<SeatWind, UUID>(SeatWind::class.java)
        val names = mutableMapOf<UUID, String>()
        SeatWind.values().forEach { wind ->
            val playerId = player(wind)
            seats[wind] = playerId
            names[playerId] = wind.name
        }
        val testWall = wall ?: flowerFreeTestWall()
        return GbTableRoundController(rule, seats, names, gateway, IntSupplier { 7 }, Supplier { testWall })
    }

    private fun flowerFreeTestWall(): List<MahjongTile> {
        val tiles = MahjongTile.values().filter { it != MahjongTile.UNKNOWN && !it.isFlower && !it.isRedFive }
        return List(144) { tiles[it % tiles.size] }
    }

    private fun forceHand(
        controller: GbTableRoundController,
        playerId: UUID,
        tiles: List<String>,
    ) {
        val handsField = GbTableRoundController::class.java.getDeclaredField("hands")
        handsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val hands = handsField.get(controller) as MutableMap<UUID, MutableList<MahjongTile>>
        hands[playerId] = tiles.map(MahjongTile::valueOf).toMutableList()
    }

    private fun forceNonDealerHandsWithoutNorth(controller: GbTableRoundController) {
        val safeHand = listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "P1", "P2", "P3", "S1", "S2", "S3")
        for (wind in listOf(SeatWind.SOUTH, SeatWind.WEST, SeatWind.NORTH)) {
            forceHand(controller, player(wind), safeHand)
        }
    }

    private fun forceDiscards(
        controller: GbTableRoundController,
        playerId: UUID,
        tiles: List<MahjongTile>,
    ) {
        val discardsField = GbTableRoundController::class.java.getDeclaredField("discards")
        discardsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val discards = discardsField.get(controller) as MutableMap<UUID, MutableList<MahjongTile>>
        discards[playerId] = tiles.toMutableList()
    }

    private fun forceReplacementTile(
        controller: GbTableRoundController,
        tile: MahjongTile,
    ) {
        forceWall(controller, listOf(tile))
    }

    private fun forceWall(
        controller: GbTableRoundController,
        tiles: List<MahjongTile>,
    ) {
        val wallField = GbTableRoundController::class.java.getDeclaredField("wall")
        wallField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val wall = wallField.get(controller) as MutableCollection<MahjongTile>
        wall.clear()
        wall.addAll(tiles)
    }

    private fun currentWall(controller: GbTableRoundController): List<MahjongTile> {
        val wallField = GbTableRoundController::class.java.getDeclaredField("wall")
        wallField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (wallField.get(controller) as Collection<MahjongTile>).toList()
    }

    private fun currentFlowers(
        controller: GbTableRoundController,
        playerId: UUID,
    ): List<MahjongTile> {
        val flowersField = GbTableRoundController::class.java.getDeclaredField("flowers")
        flowersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flowers = flowersField.get(controller) as Map<UUID, List<MahjongTile>>
        return flowers.getValue(playerId).toList()
    }

    private fun sourceWallForReordered(
        reordered: List<MahjongTile>,
        directionDicePoints: Int,
        breakDicePoints: Int,
        dealerIndex: Int,
    ): List<MahjongTile> {
        val seatCount = SeatWind.values().size
        val openDoorIndex = Math.floorMod(dealerIndex + directionDicePoints - 1, seatCount)
        val offset = openDoorIndex * (reordered.size / seatCount) + 2 * (directionDicePoints + breakDicePoints)
        val source = MutableList(reordered.size) { MahjongTile.UNKNOWN }
        reordered.indices.forEach { index -> source[Math.floorMod(offset + index, reordered.size)] = reordered[index] }
        return source
    }

    private class CapturingGateway(
        private val fanResponse: GbFanResponse =
            GbFanResponse(
                true,
                8,
                listOf(GbFanEntry("MOCK_EIGHT_FAN", 8, 1)),
                null,
            ),
        private val winResponse: ((GbWinRequest) -> GbWinResponse)? = null,
    ) : GbNativeRulesGateway() {
        val fanRequests = mutableListOf<GbFanRequest>()

        override fun isAvailable(): Boolean = true

        override fun evaluateFan(request: GbFanRequest): GbFanResponse {
            fanRequests += request
            return fanResponse
        }

        override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

        override fun evaluateWin(request: GbWinRequest): GbWinResponse =
            winResponse?.invoke(request)
                ?: GbWinResponse(
                    true,
                    if (request.winType == "SELF_DRAW") "TSUMO" else "RON",
                    8,
                    listOf(GbFanEntry("MOCK_EIGHT_FAN", 8, 1)),
                    officialScoreDeltas(request, 8),
                    null,
                )
    }

    private fun player(wind: SeatWind): UUID = UUID.nameUUIDFromBytes(wind.name.toByteArray())

    private fun multiset(tiles: List<MahjongTile>): Map<MahjongTile, Int> = tiles.groupingBy { it }.eachCount()
}

private fun validWinResponse(
    request: GbWinRequest,
    deltas: List<GbScoreDelta>,
): GbWinResponse =
    GbWinResponse(
        true,
        if (request.winType == "SELF_DRAW") "TSUMO" else "RON",
        8,
        listOf(GbFanEntry("MOCK_EIGHT_FAN", 8, 1)),
        deltas,
        null,
    )

private fun officialScoreDeltas(
    request: GbWinRequest,
    totalFan: Int,
): List<GbScoreDelta> {
    val opponents = request.seatPoints.map { it.seat }.filter { it != request.winnerSeat }
    if (request.winType == "SELF_DRAW") {
        val payment = 8 + totalFan
        return opponents.map { GbScoreDelta(it, -payment) } +
            GbScoreDelta(request.winnerSeat, payment * opponents.size)
    }
    val discarder = request.discarderSeat ?: return emptyList()
    val payments = opponents.associateWith { seat -> 8 + if (seat == discarder) totalFan else 0 }
    return payments.map { (seat, payment) -> GbScoreDelta(seat, -payment) } +
        GbScoreDelta(request.winnerSeat, payments.values.sum())
}
