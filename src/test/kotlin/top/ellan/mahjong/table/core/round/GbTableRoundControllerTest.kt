package top.ellan.mahjong.table.core.round

import top.ellan.mahjong.gb.jni.GbFanEntry
import top.ellan.mahjong.gb.jni.GbFanRequest
import top.ellan.mahjong.gb.jni.GbFanResponse
import top.ellan.mahjong.gb.jni.GbScoreDelta
import top.ellan.mahjong.gb.jni.GbTingCandidate
import top.ellan.mahjong.gb.jni.GbTingRequest
import top.ellan.mahjong.gb.jni.GbTingResponse
import top.ellan.mahjong.gb.jni.GbWinRequest
import top.ellan.mahjong.gb.jni.GbWinResponse
import top.ellan.mahjong.gb.runtime.GbNativeRulesGateway
import top.ellan.mahjong.gb.runtime.GbTileEncoding
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.riichi.ReactionResponse
import top.ellan.mahjong.riichi.ReactionType
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.OpeningDiceRoll
import java.util.EnumMap
import java.util.UUID
import java.util.function.IntSupplier
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GbTableRoundControllerTest {
    @Test
    fun `gb round deals 14 tiles to dealer and 13 to others`() {
        val controller = controller()

        controller.startRound()

        assertTrue(controller.started())
        assertEquals(14, controller.hand(player(SeatWind.EAST)).size)
        assertEquals(13, controller.hand(player(SeatWind.SOUTH)).size)
        assertEquals(13, controller.hand(player(SeatWind.WEST)).size)
        assertEquals(13, controller.hand(player(SeatWind.NORTH)).size)
    }

    @Test
    fun `sichuan profile deals suited tiles only`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)

        controller.startRound()

        val allHands = SeatWind.values().flatMap { wind -> controller.hand(player(wind)) }
        assertTrue(allHands.all { !it.isFlower && !GbRoundSupport.isHonor(it) })
    }

    @Test
    fun `gb round rolls dice and breaks wall from the matching side`() {
        val sourceWall = deterministicWall()
        val controller = controller(wall = sourceWall)
        controller.setPendingDiceRoll(OpeningDiceRoll(3, 4, 2, 5))

        controller.startRound()

        assertEquals(7, controller.dicePoints())
        assertEquals(7, controller.dicePoints2())
        val expected = reorderWall(sourceWall, 7, 7, 0).drop(53)
        assertEquals(expected, currentWall(controller))
    }

    @Test
    fun `sichuan opening uses one dice pair and the smaller die for the break`() {
        val sourceWall = deterministicSichuanWall()
        val controller = controller(profile = GbRuleProfile.SICHUAN, wall = sourceWall)
        controller.setPendingDiceRoll(OpeningDiceRoll(2, 5, 6, 6))

        controller.startRound()

        assertEquals(7, controller.dicePoints())
        val expected = reorderSichuanWall(sourceWall, 7, 2, 0).drop(53)
        assertEquals(expected, currentWall(controller))
    }

    @Test
    fun `drawn flower stays concealed until the player declares it and then replaces from the back`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        forceHand(controller, east, listOf("NORTH", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, south, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, west, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, north, listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1", "P2", "P3", "P4"))
        forceWall(controller, listOf("PLUM", "S9"))

        assertTrue(controller.discard(east, 0))
        assertFalse(controller.hasPendingReaction())
        assertEquals(14, controller.hand(south).size)
        assertEquals(top.ellan.mahjong.model.MahjongTile.PLUM, controller.hand(south).last())
        assertTrue(controller.fuuro(south).isEmpty())
        assertTrue(controller.canDeclareFlower(south))
        assertEquals(listOf(13), controller.suggestedFlowerIndices(south))

        assertTrue(controller.declareFlower(south, 13))
        assertFalse(controller.hand(south).contains(top.ellan.mahjong.model.MahjongTile.PLUM))
        assertEquals(listOf(top.ellan.mahjong.model.MahjongTile.PLUM), controller.fuuro(south).single().tiles())
        assertEquals(top.ellan.mahjong.model.MahjongTile.S9, controller.hand(south).last())
    }

    @Test
    fun `player may retain and discard a flower without opening a reaction window`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        forceHand(controller, east, listOf("NORTH", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, south, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, west, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, north, listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1", "P2", "P3", "P4"))
        forceWall(controller, listOf("PLUM", "S9"))

        assertTrue(controller.discard(east, 0))
        assertEquals(MahjongTile.PLUM, controller.hand(south).last())
        assertTrue(controller.discard(south, controller.hand(south).lastIndex))
        assertFalse(controller.hasPendingReaction())
        assertEquals(MahjongTile.PLUM, controller.discards(south).last())
        assertTrue(controller.fuuro(south).isEmpty())
        assertEquals(MahjongTile.S9, controller.hand(west).last())
    }

    @Test
    fun `drawn tile stays on the right while the rest of gb hand is sorted`() {
        val controller = controller()
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        forceHand(
            controller,
            east,
            listOf(
                "M9",
                "P9",
                "S9",
                "EAST",
                "SOUTH",
                "WEST",
                "NORTH",
                "WHITE_DRAGON",
                "GREEN_DRAGON",
                "RED_DRAGON",
                "M1",
                "P1",
                "S1",
                "M2",
            ),
        )
        forceHand(controller, south, listOf("P3", "M3", "EAST", "S2", "M1", "RED_DRAGON", "P1", "S1", "M2", "P2", "S3", "M4", "P4"))
        forceWall(controller, listOf("M5"))

        assertTrue(controller.discard(east, 0))
        if (controller.availableReactions(south) != null) {
            assertTrue(controller.react(south, ReactionResponse(ReactionType.SKIP, null)))
        }
        if (controller.availableReactions(player(SeatWind.WEST)) != null) {
            assertTrue(controller.react(player(SeatWind.WEST), ReactionResponse(ReactionType.SKIP, null)))
        }
        if (controller.availableReactions(player(SeatWind.NORTH)) != null) {
            assertTrue(controller.react(player(SeatWind.NORTH), ReactionResponse(ReactionType.SKIP, null)))
        }
        assertEquals(
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.RED_DRAGON,
                MahjongTile.M5,
            ),
            controller.hand(south),
        )
    }

    @Test
    fun `discard opens ron window and resumes after all skips`() {
        val controller = controller()
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        assertTrue(controller.discard(east, 0))
        assertNotNull(controller.availableReactions(south))
        assertNotNull(controller.availableReactions(west))
        assertNotNull(controller.availableReactions(north))

        assertTrue(controller.react(south, ReactionResponse(ReactionType.SKIP, null)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))

        assertFalse(controller.hasPendingReaction())
        assertEquals(SeatWind.SOUTH, controller.currentSeat())
        assertEquals(14, controller.hand(south).size)
    }

    @Test
    fun `gb end to end flow settles after discard and reaction`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse =
                        if (request.seatWind == "SOUTH") {
                            GbFanResponse(true, 8, listOf(GbFanEntry("Mock Ron", 8, 1)), null)
                        } else {
                            GbFanResponse(false, 0, emptyList(), "cannot win")
                        }

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse =
                        GbWinResponse(
                            true,
                            "RON",
                            8,
                            listOf(GbFanEntry("Mock Ron", 8, 1)),
                            listOf(
                                GbScoreDelta(request.winnerSeat, 8),
                                GbScoreDelta(request.discarderSeat ?: "EAST", -8),
                            ),
                            null,
                        )
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5"))
        forceHand(controller, south, listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1", "P2", "P3", "P4"))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.hasPendingReaction())
        assertNotNull(controller.availableReactions(south))

        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))
        if (controller.availableReactions(west) != null) {
            assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        }
        if (controller.availableReactions(north) != null) {
            assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))
        }

        assertFalse(controller.started())
        assertFalse(controller.hasPendingReaction())
        assertEquals("RON", controller.lastResolution()?.title)
        assertEquals(1, controller.lastResolution()?.yakuSettlements?.size)
        assertEquals(
            "SOUTH",
            controller
                .lastResolution()
                ?.yakuSettlements
                ?.single()
                ?.displayName,
        )
        assertEquals(
            8,
            controller
                .lastResolution()
                ?.scoreSettlement
                ?.scoreList
                ?.first { it.stringUUID == south.toString() }
                ?.scoreChange,
        )
        assertEquals(
            -8,
            controller
                .lastResolution()
                ?.scoreSettlement
                ?.scoreList
                ?.first { it.stringUUID == east.toString() }
                ?.scoreChange,
        )
    }

    @Test
    fun `pon claim keeps claimant on discard turn without drawing`() {
        val controller = controller()
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5"))
        forceHand(controller, south, listOf("M1", "M1", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1", "P2"))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.PON, null)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))

        assertEquals(SeatWind.SOUTH, controller.currentSeat())
        assertEquals(11, controller.hand(south).size)
        assertEquals(1, controller.fuuro(south).size)
        assertEquals(0, controller.fuuro(south).single().claimTileIndex())
        assertEquals(0, controller.discards(east).size)
        assertEquals(
            listOf(
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
            ),
            controller.hand(south),
        )
        assertFalse(controller.canWinByTsumo(south))
    }

    @Test
    fun `gb claimant cannot declare self kan before discarding after pon`() {
        val controller = controller()
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5"))
        forceHand(controller, south, listOf("M1", "M1", "P2", "P2", "P2", "P2", "S1", "S2", "S3", "S4", "S5", "S6", "RED_DRAGON"))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.PON, null)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))

        assertEquals(SeatWind.SOUTH, controller.currentSeat())
        assertFalse(controller.canDeclareKan(south))
        assertTrue(controller.suggestedKanTiles(south).isEmpty())
        assertFalse(controller.declareKan(south, "p2"))
    }

    @Test
    fun `chii removes claimed discard from river`() {
        val controller = controller()
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        forceHand(controller, east, listOf("M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5", "P6"))
        forceHand(controller, south, listOf("M1", "M3", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1", "P2"))

        assertTrue(controller.discard(east, 0))
        val chiiPair = controller.availableReactions(south)?.chiiPairs?.single()
        assertNotNull(chiiPair)

        assertTrue(controller.react(south, ReactionResponse(ReactionType.CHII, chiiPair)))
        if (controller.availableReactions(west) != null) {
            assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        }
        if (controller.availableReactions(north) != null) {
            assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))
        }

        assertEquals(0, controller.discards(east).size)
        assertEquals(1, controller.fuuro(south).size)
        assertEquals(SeatWind.SOUTH, controller.currentSeat())
    }

    @Test
    fun `minkan takes priority over chii on the same discard`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5"))
        forceHand(controller, south, listOf("M2", "M3", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1", "P2"))
        forceHand(controller, west, listOf("M1", "M1", "M1", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1"))
        forceHand(controller, north, listOf("M2", "M3", "M4", "M5", "M6", "M7", "P1", "P2", "P3", "P4", "P5", "S1", "S2"))

        assertTrue(controller.discard(east, 0))
        val chiiPair = controller.availableReactions(south)?.chiiPairs?.single()
        assertNotNull(chiiPair)
        assertTrue(controller.availableReactions(west)?.canMinkan == true)

        assertTrue(controller.react(south, ReactionResponse(ReactionType.CHII, chiiPair)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.MINKAN, null)))

        assertEquals(SeatWind.WEST, controller.currentSeat())
        val southMelds = controller.fuuro(south).filter { it.claimTileIndex() >= 0 }
        val westMelds = controller.fuuro(west).filter { it.claimTileIndex() >= 0 }
        assertEquals(0, southMelds.size)
        assertEquals(1, westMelds.size)
    }

    @Test
    fun `gb tsumo uses native fan validation`() {
        val controller = controller()
        controller.startRound()

        assertTrue(controller.canWinByTsumo(player(SeatWind.EAST)))
        assertTrue(controller.declareTsumo(player(SeatWind.EAST)))
        assertFalse(controller.started())
        assertEquals("TSUMO", controller.lastResolution()?.title)
        assertEquals(1, controller.lastResolution()?.yakuSettlements?.size)
        assertEquals(
            24,
            controller
                .lastResolution()
                ?.scoreSettlement
                ?.scoreList
                ?.first {
                    it.stringUUID == player(SeatWind.EAST).toString()
                }?.scoreChange,
        )
    }
}

class SichuanTableRoundControllerRulesTest {
    @Test
    fun `sichuan tsumo uses local hu evaluation`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, east to "suo")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9", "P9"))

        assertTrue(controller.canWinByTsumo(east))
        assertTrue(controller.declareTsumo(east))
        assertTrue(controller.started())
        assertEquals(SeatWind.SOUTH, controller.currentSeat())
        assertFalse(controller.canSelectHandTile(east, 0))
        assertNull(controller.lastResolution())
    }

    @Test
    fun `sichuan blood battle ends after three winners`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, east to "suo", south to "suo", west to "suo", north to "suo")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9", "P9"))
        forceHand(controller, south, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9"))
        forceHand(controller, west, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9"))
        forceHand(controller, north, listOf("M2", "M3", "M4", "M5", "M6", "M7", "P1", "P2", "P3", "P4", "P5", "P6", "P7"))
        forceWall(controller, listOf("P9", "P9", "M1"))

        val eastBeforeWin = controller.points(east)
        assertTrue(controller.declareTsumo(east))
        val eastWinScore = controller.points(east) - eastBeforeWin
        assertTrue(controller.started())
        assertEquals(SeatWind.SOUTH, controller.currentSeat())
        assertTrue(controller.canDeclareTsumo(south))

        val southBeforeWin = controller.points(south)
        assertTrue(controller.declareTsumo(south))
        val southWinScore = controller.points(south) - southBeforeWin
        assertTrue(controller.started())
        assertEquals(SeatWind.WEST, controller.currentSeat())
        assertTrue(controller.canDeclareTsumo(west))

        val westBeforeWin = controller.points(west)
        assertTrue(controller.declareTsumo(west))
        val westWinScore = controller.points(west) - westBeforeWin
        assertFalse(controller.started())
        assertEquals("TSUMO", controller.lastResolution()?.title)
        assertEquals(3, controller.lastResolution()?.yakuSettlements?.size)
        assertEquals(
            mapOf("EAST" to eastWinScore, "SOUTH" to southWinScore, "WEST" to westWinScore),
            controller.lastResolution()?.yakuSettlements?.associate { it.displayName to it.score },
        )
    }

    @Test
    fun `sichuan discard win continues blood battle until third winner settles the hand`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, east to "suo", south to "suo", west to "suo", north to "suo")
        forceHand(controller, east, listOf("P9", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, south, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9"))
        forceHand(controller, west, listOf("M1", "M2", "M4", "M5", "M7", "M8", "P1", "P3", "P5", "S1", "S3", "S5", "S7"))
        forceHand(controller, north, listOf("M1", "M2", "M4", "M5", "M7", "M8", "P1", "P3", "P5", "S1", "S3", "S5", "S7"))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.availableReactions(south)?.canRon == true)
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))
        assertTrue(controller.started())
        assertEquals(SeatWind.WEST, controller.currentSeat())
        assertFalse(controller.canSelectHandTile(south, 0))

        val settledSouthPoints = controller.points(south)
        forceHand(controller, west, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9", "P9"))
        assertTrue(controller.declareTsumo(west))
        assertEquals(settledSouthPoints, controller.points(south))
        assertTrue(controller.started())
        assertEquals(SeatWind.NORTH, controller.currentSeat())

        forceHand(controller, north, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9", "P9"))
        assertTrue(controller.declareTsumo(north))

        assertFalse(controller.started())
        assertEquals("TSUMO", controller.lastResolution()?.title)
        assertEquals(listOf("SOUTH", "WEST", "NORTH"), controller.lastResolution()?.yakuSettlements?.map { it.displayName })
    }

    @Test
    fun `sichuan hand must be missing one suit to win`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, east to "wan")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "S9", "S9"))

        assertFalse(controller.canWinByTsumo(east))
        assertFalse(controller.declareTsumo(east))
    }

    @Test
    fun `sichuan does not allow chii reactions`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        activateSichuan(controller, east to "suo", south to "wan")
        forceHand(controller, east, listOf("M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5", "P6"))
        forceHand(controller, south, listOf("M1", "M3", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "P1", "P2"))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.availableReactions(south)?.chiiPairs?.isEmpty() ?: true)
    }

    @Test
    fun `sichuan qingyise and root fan score by powers of two`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, east to "tong")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M7", "M7", "M9", "M9"))

        assertTrue(controller.declareTsumo(east))
        assertEquals(27, controller.points(east) - 25000)
    }

    @Test
    fun `sichuan high fan hands score at the three fan cap`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, east to "tong")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "M2", "M2", "M2", "M2", "M3", "M3", "M3", "M3", "M4", "M4"))

        assertTrue(controller.declareTsumo(east))
        assertEquals(27, controller.points(east) - 25000)
    }

    @Test
    fun `sichuan concealed kan collects rain points from active opponents`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, east to "suo")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P1", "P2", "P3", "P4"))
        forceWall(controller, listOf("P5", "P6", "P7", "P8", "P9"))

        assertTrue(controller.declareKan(east, "m1"))
        assertEquals(6, controller.points(east) - 25000)
    }

    @Test
    fun `gb round advances dealer between hands and keeps east round wind`() {
        val controller = controller()
        controller.startRound()

        assertTrue(controller.declareTsumo(player(SeatWind.EAST)))
        assertFalse(controller.started())
        assertFalse(controller.gameFinished())
        assertEquals(SeatWind.SOUTH, controller.dealerSeat())
        assertEquals(SeatWind.EAST, controller.roundWind())
        assertEquals(1, controller.roundIndex())

        controller.startRound()

        assertEquals(14, controller.hand(player(SeatWind.SOUTH)).size)
        assertEquals(13, controller.hand(player(SeatWind.EAST)).size)
    }

    @Test
    fun `gb native requests use prevailing round wind after east cycle advances`() {
        val gateway = CapturingGateway()
        val controller = controller(gateway)

        controller.startRound()
        assertTrue(controller.declareTsumo(player(SeatWind.EAST)))
        controller.startRound()
        assertTrue(controller.declareTsumo(player(SeatWind.SOUTH)))
        controller.startRound()
        assertTrue(controller.declareTsumo(player(SeatWind.WEST)))
        controller.startRound()
        assertTrue(controller.declareTsumo(player(SeatWind.NORTH)))

        assertEquals(SeatWind.SOUTH, controller.roundWind())
        assertEquals(SeatWind.EAST, controller.dealerSeat())
        assertEquals(0, controller.roundIndex())

        controller.startRound()
        assertTrue(controller.canWinByTsumo(player(SeatWind.EAST)))
        assertEquals("EAST", gateway.lastFanRequest?.seatWind)
        assertEquals("SOUTH", gateway.lastFanRequest?.roundWind)
    }

    @Test
    fun `gb native fan requests include collected flower tiles`() {
        val gateway = CapturingGateway()
        val controller = controller(gateway)
        val east = player(SeatWind.EAST)

        controller.startRound()
        forceFlowers(controller, east, listOf("PLUM", "WINTER"))

        assertTrue(controller.canWinByTsumo(east))
        assertEquals(listOf("a", "h"), gateway.lastFanRequest?.flowerTiles)
    }

    @Test
    fun `gb native requests rotate logical seat wind with dealer`() {
        val gateway = CapturingGateway()
        val controller = controller(gateway)
        controller.startRound()

        assertTrue(controller.declareTsumo(player(SeatWind.EAST)))
        controller.startRound()
        val south = player(SeatWind.SOUTH)
        forceHand(
            controller,
            south,
            listOf("M1", "M2", "M3", "M4", "M5", "M6", "P1", "P2", "P3", "S1", "S2", "S3", "RED_DRAGON", "RED_DRAGON"),
        )

        assertEquals(SeatWind.SOUTH, controller.dealerSeat())
        assertTrue(controller.canWinByTsumo(south))
        assertTrue(controller.declareTsumo(south))
        assertEquals("EAST", gateway.lastFanRequest?.seatWind)
        assertEquals("EAST", gateway.lastWinRequest?.winnerSeat)
    }

    @Test
    fun `standard sichuan starts directly at dingque and disables exchange`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        assertFalse(controller.isSichuanExchangePhase(east))
        assertFalse(controller.submitSichuanExchangeSelection(east, listOf(0, 1, 2)))
        assertFalse(controller.handleHandTileClick(east, 0, false))
        assertTrue(controller.canChooseSichuanMissingSuit(east))
        assertFalse(controller.canSelectHandTile(east, 0))
    }

    @Test
    fun `four dingque declarations activate standard sichuan play`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        assertTrue(controller.chooseSichuanMissingSuit(east, "wan"))
        assertFalse(controller.isCurrentPlayer(east))
        assertTrue(controller.isCurrentPlayer(south))
        assertTrue(controller.chooseSichuanMissingSuit(south, "tong"))
        assertTrue(controller.chooseSichuanMissingSuit(west, "suo"))
        assertTrue(controller.chooseSichuanMissingSuit(north, "wan"))

        SeatWind.values().forEach { wind -> assertFalse(controller.canChooseSichuanMissingSuit(player(wind))) }
        assertTrue(controller.isCurrentPlayer(east))
        assertFalse(controller.isCurrentPlayer(south))
    }

    @Test
    fun `sichuan declared suit must be discarded first`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, east to "wan")
        forceHand(controller, east, listOf("M1", "P1", "P2", "P3", "P4", "P5", "P6", "S1", "S2", "S3", "S4", "S5", "S6", "S7"))

        assertTrue(controller.canSelectHandTile(east, 0))
        assertFalse(controller.canSelectHandTile(east, 1))
    }

    @Test
    fun `sichuan self draw requires missing the declared suit`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, east to "wan")
        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "P1", "P2", "P3", "P4", "P5", "P6", "P9", "P9"))
        assertFalse(controller.canWinByTsumo(east))

        forceHand(controller, east, listOf("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "S1", "S2", "S3", "S4", "S4"))
        assertTrue(controller.canWinByTsumo(east))
    }

    @Test
    fun `sichuan allows multiple ron winners on the same discard`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, east to "tong", south to "wan", west to "wan", north to "wan")
        forceHand(controller, east, listOf("P7", "M1", "M2", "M3", "M4", "M5", "M6", "P1", "P2", "P3", "S1", "S2", "S3", "S4"))
        forceHand(controller, south, listOf("P1", "P2", "P3", "P4", "P5", "P6", "P8", "P9", "S1", "S2", "S3", "S4", "S4"))
        forceHand(controller, west, listOf("P1", "P2", "P3", "P4", "P5", "P6", "P8", "P9", "S1", "S2", "S3", "S4", "S4"))
        forceHand(controller, north, listOf("M1", "M2", "M3", "M4", "M5", "M6", "P1", "P2", "P3", "S1", "S2", "S3", "S4"))

        assertTrue(controller.discard(east, 0))
        assertNull(controller.availableReactions(north))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.RON, null)))

        assertEquals(2, settledSichuanPlayers(controller).size)
        assertTrue(controller.started())
    }
}

class GbTableRoundControllerBotAndKanTest {
    @Test
    fun `gb bot discard suggestion prefers discard that keeps eight fan waits`() {
        val targetHand = encodedTiles("M1", "M2", "M3", "M4", "M5", "M6", "P1", "P2", "P3", "S1", "S2", "S3", "RED_DRAGON")
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse =
                        if (request.handTiles == targetHand) {
                            GbTingResponse(true, listOf(GbTingCandidate("W9", 8, listOf(GbFanEntry("Mock Fan", 8, 1)))), null)
                        } else {
                            GbTingResponse(true, emptyList(), null)
                        }

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "P1", "P2", "P3", "S1", "S2", "S3", "EAST", "RED_DRAGON"))

        assertEquals(12, controller.suggestedBotDiscardIndex(east))
    }

    @Test
    fun `gb bot reaction prefers pon when it creates eight fan ready shape`() {
        val targetHand = encodedTiles("P1", "P2", "P3", "P4", "S1", "S2", "S3", "S4", "S5", "S6")
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse =
                        if (request.melds.any { it.type == "PUNG" } && request.handTiles == targetHand) {
                            GbTingResponse(true, listOf(GbTingCandidate("B9", 8, listOf(GbFanEntry("Mock Fan", 8, 1)))), null)
                        } else {
                            GbTingResponse(true, emptyList(), null)
                        }

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5"))
        forceHand(controller, south, listOf("M1", "M1", "P1", "P2", "P3", "P4", "S1", "S2", "S3", "S4", "S5", "S6", "RED_DRAGON"))

        assertTrue(controller.discard(east, 0))
        assertEquals(ReactionType.PON, controller.suggestedBotReaction(south).type)
    }

    @Test
    fun `gb bot reaction skips pon when it does not improve to qualified waits`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4", "P5"))
        forceHand(controller, south, listOf("M1", "M1", "P1", "P2", "P3", "P4", "S1", "S2", "S3", "S4", "S5", "S6", "RED_DRAGON"))

        assertTrue(controller.discard(east, 0))
        assertEquals(ReactionType.SKIP, controller.suggestedBotReaction(south).type)
    }

    @Test
    fun `gb bot kan suggestion only keeps kong when it leads to qualified waits`() {
        val targetHand = encodedTiles("P1", "P2", "P3", "S1", "S2", "S3", "S4", "S5", "S6", "RED_DRAGON")
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse =
                        if (request.melds.any { it.type == "CONCEALED_KONG" } && request.handTiles == targetHand) {
                            GbTingResponse(true, listOf(GbTingCandidate("T9", 8, listOf(GbFanEntry("Mock Fan", 8, 1)))), null)
                        } else {
                            GbTingResponse(true, emptyList(), null)
                        }

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)

        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "P1", "P2", "P3", "S1", "S2", "S3", "S4", "S5", "S6", "RED_DRAGON"))

        assertEquals("m1", controller.suggestedBotKanTile(east))
    }

    @Test
    fun `gb kan suggestions include concealed and added kong tiles`() {
        val controller = controller()
        controller.startRound()
        val east = player(SeatWind.EAST)

        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "P3", "P3", "P3", "S1", "S2", "S3", "S4", "S5", "S6", "S7"))
        assertTrue(controller.suggestedKanTiles(east).contains("m1"))

        addPung(controller, east, "P3")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "P3", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9"))
        assertTrue(controller.suggestedKanTiles(east).contains("p3"))
    }

    @Test
    fun `concealed kan is rejected when no replacement tile remains`() {
        val controller = controller()
        controller.startRound()
        val east = player(SeatWind.EAST)

        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "P1", "P2", "P3", "S1", "S2", "S3", "S4", "S5", "S6", "S7"))
        forceWall(controller, emptyList())

        assertFalse(controller.declareKan(east, "m1"))
        assertTrue(controller.started())
        assertNull(controller.lastResolution())
        assertEquals(0, controller.kanCount())
    }

    @Test
    fun `gb ron uses intercept priority when multiple players call on one discard`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse =
                        if (request.seatWind in setOf("SOUTH", "WEST")) {
                            GbFanResponse(true, 8, listOf(GbFanEntry("Mock Ron", 8, 1)), null)
                        } else {
                            GbFanResponse(false, 0, emptyList(), "cannot win")
                        }

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse =
                        GbWinResponse(
                            true,
                            "RON",
                            8,
                            listOf(GbFanEntry("Mock Ron", 8, 1)),
                            listOf(GbScoreDelta(request.winnerSeat, 8), GbScoreDelta(request.discarderSeat ?: "EAST", -8)),
                            null,
                        )
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.RON, null)))
        if (controller.availableReactions(north) != null) {
            assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))
        }

        assertFalse(controller.started())
        assertEquals(1, controller.lastResolution()?.yakuSettlements?.size)
        assertEquals(
            "SOUTH",
            controller
                .lastResolution()
                ?.yakuSettlements
                ?.single()
                ?.displayName,
        )
        assertEquals(
            -8,
            controller
                .lastResolution()
                ?.scoreSettlement
                ?.scoreList
                ?.first { it.stringUUID == east.toString() }
                ?.scoreChange,
        )
    }

    @Test
    fun `added kong exposes robbing kong window`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse =
                        if ("ROBBING_KONG" in request.flags && request.seatWind == "SOUTH") {
                            GbFanResponse(true, 8, listOf(GbFanEntry("QIANGGANGHU", 8, 1)), null)
                        } else {
                            GbFanResponse(false, 0, emptyList(), "cannot win")
                        }

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse =
                        GbWinResponse(
                            true,
                            "RON",
                            8,
                            listOf(GbFanEntry("QIANGGANGHU", 8, 1)),
                            listOf(GbScoreDelta(request.winnerSeat, 8), GbScoreDelta(request.discarderSeat ?: "EAST", -8)),
                            null,
                        )
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        addPung(controller, east, "P3")
        forceHand(controller, east, listOf("P3", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "S1", "S2", "S3", "S4"))

        assertTrue(controller.declareKan(east, "p3"))
        assertNotNull(controller.availableReactions(south))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))
        if (controller.availableReactions(west) != null) {
            assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        }
        if (controller.availableReactions(north) != null) {
            assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))
        }

        assertFalse(controller.started())
        assertEquals("RON", controller.lastResolution()?.title)
    }

    @Test
    fun `added kong displays as three base tiles plus one stacked tile`() {
        val controller =
            controller(
                object : GbNativeRulesGateway() {
                    override fun isAvailable(): Boolean = true

                    override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(false, 0, emptyList(), "cannot win")

                    override fun evaluateTing(request: GbTingRequest): GbTingResponse = GbTingResponse(true, emptyList(), null)

                    override fun evaluateWin(request: GbWinRequest): GbWinResponse = GbWinResponse(false, error = "cannot win")
                },
            )
        controller.startRound()
        val east = player(SeatWind.EAST)

        addPung(controller, east, "P3")
        forceHand(controller, east, listOf("P3", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "S1", "S2", "S3", "S4"))

        assertTrue(controller.declareKan(east, "p3"))
        val meld = controller.fuuro(east).first { it.addedKanTile() != null }
        assertEquals(3, meld.tiles().size)
        assertEquals(MahjongTile.P3, meld.addedKanTile())
    }
}

class SichuanTableRoundControllerReactionTest {
    @Test
    fun `sichuan passed win blocks the same payment tier until the player draws`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, east to "suo", south to "suo", west to "suo", north to "suo")
        forceHand(controller, east, listOf("P9", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, south, nonWinningP9DiscardHand())
        forceHand(controller, west, lowP9Wait())
        forceHand(controller, north, disconnectedHand())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P7", "P8", "M2", "M3"))

        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P9)))
        assertTrue(controller.availableReactions(west)?.canRon == true)
        assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        assertNotNull(passedWinUnit(controller, west))
        assertEquals(SeatWind.SOUTH, controller.currentSeat())

        assertTrue(controller.discard(south, controller.hand(south).indexOf(MahjongTile.P9)))
        assertFalse(settledSichuanPlayers(controller).contains(west))
        assertEquals(SeatWind.WEST, controller.currentSeat())
        assertNull(passedWinUnit(controller, west), "The restriction must clear only after West actually draws")
    }

    @Test
    fun `standard sichuan allows passing ron in the final four draws`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("P9") + disconnectedHand())
        forceHand(controller, south, sequenceP9Wait())
        forceHand(controller, player(SeatWind.WEST), disconnectedHand())
        forceHand(controller, player(SeatWind.NORTH), disconnectedHand())
        forceWall(controller, listOf("M2", "M4", "P6", "P8"))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.availableReactions(south)?.canRon == true)
        assertTrue(controller.react(south, ReactionResponse(ReactionType.SKIP, null)))
        assertFalse(settledSichuanPlayers(controller).contains(south))
    }

    @Test
    fun `standard sichuan allows discarding a winning hand in the final four draws`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, sequenceP9Wait() + "P9")
        forceWall(controller, listOf("M2", "M4", "P6", "P8"))

        assertTrue(controller.canWinByTsumo(east))
        assertTrue(controller.canSelectHandTile(east, 0))
    }

    @Test
    fun `standard sichuan keeps pung and kong reactions in the final four draws`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("M1") + disconnectedHand())
        forceHand(controller, south, listOf("M1", "M1", "M1", "M2", "M4", "M6", "M8", "P1", "P3", "P5", "P6", "P7", "P8"))
        forceHand(controller, player(SeatWind.WEST), disconnectedHand())
        forceHand(controller, player(SeatWind.NORTH), disconnectedHand())
        forceWall(controller, listOf("M2", "M4", "P6", "P8"))

        assertTrue(controller.discard(east, 0))
        val options = assertNotNull(controller.availableReactions(south))
        assertTrue(options.canPon)
        assertTrue(options.canMinkan)
    }

    @Test
    fun `passing zero fan ping hu still allows a one fan win before drawing`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, east to "suo", south to "suo", west to "suo", north to "suo")
        forceHand(controller, east, listOf("P9", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, south, nonWinningP9DiscardHand())
        forceHand(controller, west, lowP9Wait())
        forceHand(controller, north, disconnectedHand())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P7", "P8", "M2", "M3"))

        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P9)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        assertEquals(1, passedWinUnit(controller, west), "A passed zero-fan hand must retain its one-point tier")
        forceHand(controller, west, listOf("P1", "P1", "P1", "P2", "P3", "P4", "P5", "P6", "P7", "P7", "P7", "P7", "P9"))

        assertTrue(controller.discard(south, controller.hand(south).indexOf(MahjongTile.P9)))
        assertTrue(controller.availableReactions(west)?.canRon == true)
        assertTrue(controller.react(west, ReactionResponse(ReactionType.RON, null)))
        assertTrue(settledSichuanPlayers(controller).contains(west))
    }

    @Test
    fun `a player cannot overwrite an already submitted reaction in the same window`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("P9", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, player(SeatWind.SOUTH), disconnectedHand())
        forceHand(controller, west, lowP9Wait())
        forceHand(controller, north, lowP9Wait())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P7", "P8", "M2", "M3"))

        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P9)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.SKIP, null)))
        assertFalse(controller.react(west, ReactionResponse(ReactionType.RON, null)))
        assertTrue(controller.react(north, ReactionResponse(ReactionType.SKIP, null)))
        assertFalse(settledSichuanPlayers(controller).contains(west))
    }

    @Test
    fun `choosing pung instead of a Sichuan win keeps the passed win restriction until a draw`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val west = player(SeatWind.WEST)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("P9", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, player(SeatWind.SOUTH), disconnectedHand())
        forceHand(controller, west, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P2", "P9", "P9"))
        forceHand(controller, player(SeatWind.NORTH), disconnectedHand())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P7", "P8", "M2", "M3"))

        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P9)))
        val options = assertNotNull(controller.availableReactions(west))
        assertTrue(options.canRon)
        assertTrue(options.canPon)
        assertTrue(controller.react(west, ReactionResponse(ReactionType.PON, null)))
        assertNotNull(passedWinUnit(controller, west))
        assertEquals(SeatWind.WEST, controller.currentSeat())

        assertTrue(controller.discard(west, 0))
        assertNotNull(passedWinUnit(controller, west), "Calling pung does not draw, so the restriction must remain")
    }

    @Test
    fun `sichuan gang shang pao transfers kong income without refunding original payers`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, east to "suo", south to "suo", west to "suo", north to "suo")
        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P1", "P2", "P3", "P4"))
        forceHand(controller, south, lowP9Wait())
        forceHand(controller, west, disconnectedHand())
        forceHand(controller, north, disconnectedHand())
        forceWall(controller, listOf("M8", "P5", "P6", "P7", "P8", "P9"))

        assertTrue(controller.declareKan(east, "m1"))
        assertEquals(25006, controller.points(east))
        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P9)))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))

        assertEquals(24998, controller.points(east))
        assertEquals(25006, controller.points(south))
        assertEquals(24998, controller.points(west))
        assertEquals(24998, controller.points(north))
    }

    @Test
    fun `sichuan gang shang pao transfers every consecutive kong income`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(
            controller,
            east,
            listOf("M1", "M1", "M1", "M1", "M2", "M2", "M2", "M2", "M3", "M4", "M5", "P1", "P2", "P3"),
        )
        forceHand(controller, south, sequenceP9Wait())
        forceHand(controller, player(SeatWind.WEST), disconnectedHand())
        forceHand(controller, player(SeatWind.NORTH), disconnectedHand())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P8", "P9"))

        assertTrue(controller.declareKan(east, "m1"))
        assertTrue(controller.declareKan(east, "m2"))
        assertEquals(25012, controller.points(east))
        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P9)))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))

        assertEquals(24998, controller.points(east))
        assertEquals(25010, controller.points(south))
        assertEquals(24996, controller.points(player(SeatWind.WEST)))
        assertEquals(24996, controller.points(player(SeatWind.NORTH)))
    }

    @Test
    fun `sichuan multi ron call transfer rounds each share up and discarder pays the difference`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("M1") + sequenceP9Wait())
        forceHand(
            controller,
            south,
            listOf("M1", "M1", "M1", "M2", "M4", "M6", "M8", "P1", "P3", "P5", "P6", "P7", "P8"),
        )
        forceHand(controller, west, sequenceP9Wait())
        forceHand(controller, north, sequenceP9Wait())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P9"))

        assertTrue(controller.discard(east, 0))
        assertTrue(controller.availableReactions(south)?.canMinkan == true)
        assertTrue(controller.react(south, ReactionResponse(ReactionType.MINKAN, null)))
        assertEquals(25002, controller.points(south))
        assertTrue(controller.discard(south, controller.hand(south).indexOf(MahjongTile.P9)))
        assertTrue(controller.react(east, ReactionResponse(ReactionType.RON, null)))
        assertTrue(controller.react(west, ReactionResponse(ReactionType.RON, null)))
        assertTrue(controller.react(north, ReactionResponse(ReactionType.RON, null)))

        assertEquals(25001, controller.points(east))
        assertEquals(24993, controller.points(south))
        assertEquals(25003, controller.points(west))
        assertEquals(25003, controller.points(north))
    }

    @Test
    fun `sichuan call transfer chain clears when the kong discard is not won`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P1", "P2", "P3", "P4"))
        SeatWind.values().filter { it != SeatWind.EAST }.forEach { forceHand(controller, player(it), disconnectedHand()) }
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P8"))

        assertTrue(controller.declareKan(east, "m1"))
        assertEquals(1, pendingSichuanCallTransferCount(controller))
        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P8)))
        assertEquals(0, pendingSichuanCallTransferCount(controller))
    }

    @Test
    fun `sichuan exhaustive draw refunds every kong payment earned by a not ready player`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(
            controller,
            east to "wan",
            player(SeatWind.SOUTH) to "suo",
            player(SeatWind.WEST) to "suo",
            player(SeatWind.NORTH) to "suo",
        )
        forceHand(controller, east, listOf("M1", "M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P1", "P2", "P3", "P4"))
        forceWall(controller, listOf("M8", "P5", "P6", "P7", "P8", "P9"))
        assertTrue(controller.declareKan(east, "m1"))
        assertEquals(25006, controller.points(east))

        forceHand(controller, east, listOf("M2", "M4", "M6", "M8", "M9", "P1", "P3", "P5", "P7", "P9"))
        SeatWind.values().filter { it != SeatWind.EAST }.forEach { forceHand(controller, player(it), disconnectedHand()) }
        SeatWind.values().forEach { forceHasDrawn(controller, player(it), false) }
        invokeNoArg(controller, "applySichuanExhaustiveDrawSettlement")

        SeatWind.values().forEach { assertEquals(25000, controller.points(player(it))) }
    }

    @Test
    fun `natural flower pig pays ordinary cha jiao without a fixed transfer`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)
        val west = player(SeatWind.WEST)
        val north = player(SeatWind.NORTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("S1", "M1", "M3", "M5", "M7", "M9", "P1", "P3", "P5", "P7", "P9", "M2", "P4"))
        forceHand(controller, south, sequenceP9Wait())
        forceHand(controller, west, disconnectedHand())
        forceHand(controller, north, disconnectedHand())
        SeatWind.values().forEach { forceHasDrawn(controller, player(it), false) }

        invokeNoArg(controller, "applySichuanExhaustiveDrawSettlement")

        assertEquals(24999, controller.points(east))
        assertEquals(25003, controller.points(south))
        assertEquals(24999, controller.points(west))
        assertEquals(24999, controller.points(north))
    }
}

class SichuanTableRoundControllerProgressionTest {
    @Test
    fun `first Sichuan winner becomes dealer of the next hand`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        forceHand(controller, east, listOf("P9", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P1", "P2", "P3", "P4"))
        forceHand(controller, south, lowP9Wait())
        forceHand(controller, player(SeatWind.WEST), disconnectedHand())
        forceHand(controller, player(SeatWind.NORTH), disconnectedHand())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P7", "P8", "M2", "M3"))

        assertTrue(controller.discard(east, controller.hand(east).indexOf(MahjongTile.P9)))
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))
        invokeStringArg(controller, "finishSichuanBloodBattle", "RON")
        assertEquals(SeatWind.EAST, controller.dealerSeat(), "Settlement keeps showing the dealer of the completed hand")

        controller.startRound()
        assertEquals(SeatWind.SOUTH, controller.dealerSeat())
        assertEquals(14, controller.hand(south).size)
    }

    @Test
    fun `Sichuan added kong requires the fourth tile to be the current draw`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        addPung(controller, east, "P3")
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P7", "P8"))
        forceHand(controller, east, listOf("P3", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P9"))
        assertFalse(controller.suggestedAddedKanTiles(east).contains("p3"))
        assertFalse(controller.declareKan(east, "p3"))

        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P9", "P3"))
        assertTrue(controller.suggestedAddedKanTiles(east).contains("p3"))
    }

    @Test
    fun `robbing an added kong consumes only the fourth tile and keeps the pung`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)
        val south = player(SeatWind.SOUTH)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        addPung(controller, east, "P3")
        forceHand(controller, east, listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P9", "P3"))
        forceHand(controller, south, listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P4", "P5", "P6", "P3"))
        forceHand(controller, player(SeatWind.WEST), disconnectedHand())
        forceHand(controller, player(SeatWind.NORTH), disconnectedHand())
        forceWall(controller, listOf("M8", "M9", "P5", "P6", "P7", "P8"))

        assertTrue(controller.declareKan(east, "p3"))
        assertTrue(controller.availableReactions(south)?.canRon == true)
        assertTrue(controller.react(south, ReactionResponse(ReactionType.RON, null)))

        assertEquals(10, controller.hand(east).size)
        val pung = controller.fuuro(east).single()
        assertEquals(3, pung.tiles().size)
        assertNull(pung.addedKanTile())
        assertEquals(0, controller.kanCount())
    }

    @Test
    fun `Sichuan ting never offers a fifth physical copy across a pung`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)
        controller.startRound()
        val east = player(SeatWind.EAST)

        activateSichuan(controller, *SeatWind.values().map { player(it) to "suo" }.toTypedArray())
        addPung(controller, east, "P3")
        forceHand(controller, east, listOf("P3", "M1", "M2", "M3", "M4", "M5", "M6", "P7", "P8", "P9"))
        forceHasDrawn(controller, east, false)
        invalidateTing(controller, east)

        assertFalse(controller.tingOptions(east).waits.any { it.tile == "P3" })
    }

    @Test
    fun `Sichuan match ends after exactly eight hands without a return point extension`() {
        val controller = controller(profile = GbRuleProfile.SICHUAN)

        repeat(8) { handIndex ->
            controller.startRound()
            invokeNoArg(controller, "finishExhaustiveDraw")
            if (handIndex < 7) {
                assertFalse(controller.gameFinished(), "hand ${handIndex + 1} must not end the match")
            }
        }

        assertTrue(controller.gameFinished())
        assertEquals(SeatWind.SOUTH, controller.roundWind())
        assertEquals(3, controller.roundIndex())
    }

    @Test
    fun `Sichuan match honors configured starting points and one game length`() {
        val rule =
            MahjongRule(
                length = MahjongRule.GameLength.ONE_GAME,
                startingPoints = 12_345,
                minPointsToWin = 99_999,
            )
        val controller = controller(profile = GbRuleProfile.SICHUAN, rule = rule)

        controller.startRound()
        SeatWind.values().forEach { wind -> assertEquals(12_345, controller.points(player(wind))) }
        invokeNoArg(controller, "finishExhaustiveDraw")

        assertTrue(controller.gameFinished())
    }
}

private fun controller(
    gateway: GbNativeRulesGateway = defaultGateway(),
    profile: GbRuleProfile = GbRuleProfile.GB,
    rule: MahjongRule = MahjongRule(),
    dicePoints: Int? = null,
    wall: List<MahjongTile>? = null,
): GbTableRoundController {
    val seats = EnumMap<SeatWind, UUID>(SeatWind::class.java)
    val names = mutableMapOf<UUID, String>()
    SeatWind.values().forEach { wind ->
        val playerId = player(wind)
        seats[wind] = playerId
        names[playerId] = wind.name
    }
    val testWall = wall ?: if (profile == GbRuleProfile.SICHUAN) deterministicSichuanWall() else deterministicWall()
    return GbTableRoundController(
        rule,
        seats,
        names,
        gateway,
        profile,
        IntSupplier { dicePoints ?: 7 },
        Supplier { testWall },
    )
}

private fun defaultGateway(): GbNativeRulesGateway =
    object : GbNativeRulesGateway() {
        override fun isAvailable(): Boolean = true

        override fun evaluateFan(request: GbFanRequest): GbFanResponse = GbFanResponse(true, 8, listOf(GbFanEntry("Mock Fan", 8, 1)), null)

        override fun evaluateTing(request: GbTingRequest): GbTingResponse =
            GbTingResponse(true, listOf(GbTingCandidate("W1", 8, listOf(GbFanEntry("Mock Fan", 8, 1)))), null)

        override fun evaluateWin(request: GbWinRequest): GbWinResponse {
            val winnerDelta = 24
            val loserSeat = request.discarderSeat ?: "SOUTH"
            return GbWinResponse(
                true,
                if (request.winType == "SELF_DRAW") "TSUMO" else "RON",
                8,
                listOf(GbFanEntry("Mock Fan", 8, 1)),
                listOf(GbScoreDelta(request.winnerSeat, winnerDelta), GbScoreDelta(loserSeat, -winnerDelta)),
                null,
            )
        }
    }

private fun forceHand(
    controller: GbTableRoundController,
    playerId: UUID,
    tiles: List<String>,
) {
    val handsField = GbTableRoundController::class.java.getDeclaredField("hands")
    handsField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val hands = handsField.get(controller) as MutableMap<UUID, MutableList<top.ellan.mahjong.model.MahjongTile>>
    hands[playerId] = tiles.map(top.ellan.mahjong.model.MahjongTile::valueOf).toMutableList()
    forceFlowers(controller, playerId, emptyList())
}

private fun addPung(
    controller: GbTableRoundController,
    playerId: UUID,
    tile: String,
    selfSeat: SeatWind = SeatWind.EAST,
) {
    val meldsField = GbTableRoundController::class.java.getDeclaredField("melds")
    meldsField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val melds = meldsField.get(controller) as MutableMap<UUID, MutableList<Any>>
    val meldClass = GbMeldState::class.java
    val pung =
        meldClass.getDeclaredMethod(
            "pung",
            MahjongTile::class.java,
            SeatWind::class.java,
            SeatWind::class.java,
        )
    pung.isAccessible = true
    melds.getValue(playerId).add(
        pung.invoke(
            null,
            MahjongTile.valueOf(tile),
            SeatWind.SOUTH,
            selfSeat,
        ),
    )
}

private fun forceWall(
    controller: GbTableRoundController,
    tiles: List<String>,
) {
    val wallField = GbTableRoundController::class.java.getDeclaredField("wall")
    wallField.isAccessible = true
    val parsed = tiles.map(top.ellan.mahjong.model.MahjongTile::valueOf)

    @Suppress("UNCHECKED_CAST")
    val wall = wallField.get(controller) as MutableCollection<top.ellan.mahjong.model.MahjongTile>
    wall.clear()
    wall.addAll(parsed)
}

private fun forceFlowers(
    controller: GbTableRoundController,
    playerId: UUID,
    tiles: List<String>,
) {
    val flowersField = GbTableRoundController::class.java.getDeclaredField("flowers")
    flowersField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flowers = flowersField.get(controller) as MutableMap<UUID, MutableList<top.ellan.mahjong.model.MahjongTile>>
    flowers[playerId] = tiles.map(top.ellan.mahjong.model.MahjongTile::valueOf).toMutableList()
}

private fun activateSichuan(
    controller: GbTableRoundController,
    vararg declarations: Pair<UUID, String>,
) {
    val phaseClass = Class.forName("${GbTableRoundController::class.java.name}\$SichuanPreparationPhase")
    val activePhase = phaseClass.enumConstants.first { (it as Enum<*>).name == "ACTIVE" }
    val phaseField = GbTableRoundController::class.java.getDeclaredField("sichuanPreparationPhase")
    phaseField.isAccessible = true
    phaseField.set(controller, activePhase)

    val missingSuitsField = GbTableRoundController::class.java.getDeclaredField("chosenMissingSuits")
    missingSuitsField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val chosenMissingSuits = missingSuitsField.get(controller) as MutableMap<UUID, Any>
    chosenMissingSuits.clear()
    val suitClass = Class.forName("top.ellan.mahjong.table.core.round.SichuanSuit")
    declarations.forEach { (playerId, suitKey) ->
        val suit = suitClass.enumConstants.first { (it as Enum<*>).name == suitKey.uppercase() }
        chosenMissingSuits[playerId] = suit
    }
}

private fun lowP9Wait(): List<String> = listOf("M1", "M1", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "P2", "P3", "P4", "P9")

private fun sequenceP9Wait(): List<String> = listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "P2", "P3", "P4", "P9")

private fun nonWinningP9DiscardHand(): List<String> = listOf("P9", "M2", "M2", "M4", "M4", "M6", "M6", "M8", "M8", "P1", "P3", "P5", "P7")

private fun disconnectedHand(): List<String> = listOf("M1", "M3", "M5", "M7", "M9", "P1", "P3", "P5", "P7", "P9", "M2", "P4", "P6")

private fun passedWinUnit(
    controller: GbTableRoundController,
    playerId: UUID,
): Int? {
    val field = GbTableRoundController::class.java.getDeclaredField("sichuanPassedWinUnits")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return (field.get(controller) as Map<UUID, Int>)[playerId]
}

private fun pendingSichuanCallTransferCount(controller: GbTableRoundController): Int {
    val field = GbTableRoundController::class.java.getDeclaredField("pendingSichuanCallTransferEvents")
    field.isAccessible = true
    return (field.get(controller) as Collection<*>).size
}

private fun forceHasDrawn(
    controller: GbTableRoundController,
    playerId: UUID,
    hasDrawn: Boolean,
) {
    val field = GbTableRoundController::class.java.getDeclaredField("hasDrawnTile")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    (field.get(controller) as MutableMap<UUID, Boolean>)[playerId] = hasDrawn
}

private fun invalidateTing(
    controller: GbTableRoundController,
    playerId: UUID,
) {
    val field = GbTableRoundController::class.java.getDeclaredField("dirtyTingPlayers")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    (field.get(controller) as MutableSet<UUID>).add(playerId)
}

private fun invokeNoArg(
    controller: GbTableRoundController,
    methodName: String,
) {
    val method = GbTableRoundController::class.java.getDeclaredMethod(methodName)
    method.isAccessible = true
    method.invoke(controller)
}

private fun invokeStringArg(
    controller: GbTableRoundController,
    methodName: String,
    argument: String,
) {
    val method = GbTableRoundController::class.java.getDeclaredMethod(methodName, String::class.java)
    method.isAccessible = true
    method.invoke(controller, argument)
}

private fun settledSichuanPlayers(controller: GbTableRoundController): Set<UUID> {
    val field = GbTableRoundController::class.java.getDeclaredField("settledSichuanPlayers")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(controller) as Set<UUID>
}

private fun currentWall(controller: GbTableRoundController): List<MahjongTile> {
    val wallField = GbTableRoundController::class.java.getDeclaredField("wall")
    wallField.isAccessible = true
    val wall = wallField.get(controller)
    return when (wall) {
        is List<*> -> wall.filterIsInstance<MahjongTile>()
        is Collection<*> -> wall.filterIsInstance<MahjongTile>().toList()
        else -> emptyList()
    }
}

private fun deterministicWall(): List<MahjongTile> {
    val sequence =
        MahjongTile.values().filter { tile ->
            tile != MahjongTile.UNKNOWN && !tile.isRedFive && !tile.isFlower
        }
    return List(144) { sequence[it % sequence.size] }
}

private fun deterministicSichuanWall(): List<MahjongTile> {
    val sequence =
        MahjongTile.values().filter { tile ->
            tile != MahjongTile.UNKNOWN && !tile.isRedFive && !tile.isFlower && !GbRoundSupport.isHonor(tile)
        }
    return List(108) { sequence[it % sequence.size] }
}

private fun reorderSichuanWall(
    wall: List<MahjongTile>,
    dicePoints: Int,
    smallerDie: Int,
    dealerIndex: Int,
): List<MahjongTile> {
    val seatCount = SeatWind.values().size
    val wallTilesPerSide = wall.size / seatCount
    val openDoorIndex = Math.floorMod(dealerIndex + dicePoints - 1, seatCount)
    val breakIndex = (openDoorIndex * wallTilesPerSide + smallerDie * 2).mod(wall.size)
    return List(wall.size) { offset -> wall[(breakIndex + offset) % wall.size] }
}

private fun reorderWall(
    wall: List<MahjongTile>,
    dicePoints: Int,
    dealerIndex: Int,
): List<MahjongTile> = reorderWall(wall, dicePoints, dicePoints, dealerIndex)

private fun reorderWall(
    wall: List<MahjongTile>,
    directionDicePoints: Int,
    breakDicePoints: Int,
    dealerIndex: Int,
): List<MahjongTile> {
    val seatCount = SeatWind.values().size
    val wallTilesPerSide = wall.size / seatCount
    val openDoorIndex = Math.floorMod(dealerIndex + directionDicePoints - 1, seatCount)
    val breakIndex = (openDoorIndex * wallTilesPerSide + (directionDicePoints + breakDicePoints) * 2).mod(wall.size)
    return List(wall.size) { offset -> wall[(breakIndex + offset) % wall.size] }
}

private fun encodedTiles(vararg names: String): List<String> = names.map { GbTileEncoding.encode(MahjongTile.valueOf(it)) }

private class CapturingGateway : GbNativeRulesGateway() {
    var lastFanRequest: GbFanRequest? = null
    var lastWinRequest: GbWinRequest? = null

    override fun isAvailable(): Boolean = true

    override fun evaluateFan(request: GbFanRequest): GbFanResponse {
        lastFanRequest = request
        return GbFanResponse(true, 8, listOf(GbFanEntry("Mock Fan", 8, 1)), null)
    }

    override fun evaluateTing(request: GbTingRequest): GbTingResponse =
        GbTingResponse(true, listOf(GbTingCandidate("W1", 8, listOf(GbFanEntry("Mock Fan", 8, 1)))), null)

    override fun evaluateWin(request: GbWinRequest): GbWinResponse {
        lastWinRequest = request
        val winnerDelta = 24
        val loserSeat = request.discarderSeat ?: "SOUTH"
        return GbWinResponse(
            true,
            if (request.winType == "SELF_DRAW") "TSUMO" else "RON",
            8,
            listOf(GbFanEntry("Mock Fan", 8, 1)),
            listOf(GbScoreDelta(request.winnerSeat, winnerDelta), GbScoreDelta(loserSeat, -winnerDelta)),
            null,
        )
    }
}

private fun player(wind: SeatWind): UUID = UUID.nameUUIDFromBytes(wind.name.toByteArray())
