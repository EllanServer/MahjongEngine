package top.ellan.mahjong.table.core.round

import top.ellan.mahjong.gb.jni.GbFanRequest
import top.ellan.mahjong.gb.jni.GbFanResponse
import top.ellan.mahjong.gb.jni.GbTingRequest
import top.ellan.mahjong.gb.jni.GbTingResponse
import top.ellan.mahjong.gb.jni.GbWinRequest
import top.ellan.mahjong.gb.jni.GbWinResponse
import top.ellan.mahjong.gb.runtime.GbNativeRulesGateway
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.riichi.model.MahjongRule
import java.util.EnumMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SichuanRealWorldFanCoverageTest {
    @Test
    fun `TFMJ source-backed fan examples cover the standard Sichuan list`() {
        // Source baseline: T/TFMJ 01—2024, table 1 and sections 10.1-10.2.
        val exercised = linkedSetOf<String>()

        fun assertFans(
            caseName: String,
            expected: Set<String>,
            response: GbFanResponse,
        ) {
            assertTrue(response.valid, "$caseName should be a valid Sichuan win: ${response.error}")
            val names = response.fans.map { it.name }
            expected.forEach { fan ->
                assertContains(names, fan, "$caseName should include $fan")
                exercised += fan
            }
        }

        assertFans(
            "root",
            setOf("GEN"),
            evaluateDiscard(rootController(), winningTile = MahjongTile.M9),
        )
        assertFans(
            "all pungs",
            setOf("DUI_DUI_HU"),
            evaluateDiscard(controllerWithHand(allPungsHand()), winningTile = MahjongTile.P9),
        )
        assertFans(
            "full flush",
            setOf("QING_YI_SE"),
            evaluateDiscard(controllerWithHand(fullFlushHand()), winningTile = MahjongTile.M9),
        )
        assertFans(
            "seven pairs",
            setOf("QI_DUI"),
            evaluateDiscard(controllerWithHand(sevenPairsHand()), winningTile = MahjongTile.P6),
        )
        assertFans(
            "golden single wait",
            setOf("JIN_GOU_DIAO", "DUI_DUI_HU"),
            evaluateDiscard(goldenSingleWaitController(), winningTile = MahjongTile.P2),
        )
        assertFans(
            "win after kong",
            setOf("GANG_SHANG_HUA"),
            evaluateSelfDraw(controllerWithHand(pingHuSelfDrawHand(), drawn = true), MahjongTile.P9, listOf("AFTER_KONG")),
        )
        assertFans(
            "shoot after kong",
            setOf("GANG_SHANG_PAO"),
            evaluateDiscard(controllerWithHand(pingHuDiscardHand()), winningTile = MahjongTile.P9, flags = listOf("AFTER_KONG")),
        )
        assertFans(
            "robbing kong",
            setOf("QIANG_GANG_HU"),
            evaluateDiscard(controllerWithHand(pingHuDiscardHand()), winningTile = MahjongTile.P9, flags = listOf("ROBBING_KONG")),
        )
        assertFans(
            "under the sea",
            setOf("HAI_DI"),
            evaluateSelfDraw(controllerWithHand(pingHuSelfDrawHand(), drawn = true), MahjongTile.P9, listOf("LAST_TILE")),
        )
        assertFans(
            "last discard",
            setOf("HAI_DI"),
            evaluateDiscard(controllerWithHand(pingHuDiscardHand()), winningTile = MahjongTile.P9, flags = listOf("LAST_TILE")),
        )

        assertEquals(
            setOf(
                "GEN",
                "DUI_DUI_HU",
                "QING_YI_SE",
                "QI_DUI",
                "JIN_GOU_DIAO",
                "GANG_SHANG_HUA",
                "GANG_SHANG_PAO",
                "QIANG_GANG_HU",
                "HAI_DI",
            ),
            exercised,
        )
    }

    @Test
    fun `nonstandard dragon seven pairs and jiang dui are not emitted by TFMJ`() {
        val rootedSevenPairs = evaluateDiscard(controllerWithHand(longSevenPairsHand()), winningTile = MahjongTile.P6)
        assertFans("rooted seven pairs", setOf("QI_DUI", "GEN"), rootedSevenPairs)
        assertFalse(rootedSevenPairs.fans.any { it.name == "LONG_QI_DUI" })
        assertEquals(3, rootedSevenPairs.totalFan)

        val all258Pungs = evaluateDiscard(controllerWithHand(jiangDuiHand()), winningTile = MahjongTile.M8)
        assertFans("all 2-5-8 pungs", setOf("DUI_DUI_HU"), all258Pungs)
        assertFalse(all258Pungs.fans.any { it.name == "JIANG_DUI" })
        assertEquals(1, all258Pungs.totalFan)

        val pingHu = evaluateDiscard(controllerWithHand(pingHuDiscardHand()), winningTile = MahjongTile.P9)
        assertFans("base ping hu", setOf("PING_HU"), pingHu)
        assertEquals(0, pingHu.totalFan)
    }

    private fun assertFans(
        caseName: String,
        expected: Set<String>,
        response: GbFanResponse,
    ) {
        assertTrue(response.valid, "$caseName should be a valid Sichuan win: ${response.error}")
        val names = response.fans.map { it.name }
        expected.forEach { fan -> assertContains(names, fan, "$caseName should include $fan") }
    }

    private fun evaluateDiscard(
        context: ControllerContext,
        winningTile: MahjongTile,
        flags: List<String> = emptyList(),
    ): GbFanResponse {
        setDrawn(context.controller, context.east, false)
        return evaluateFan(context.controller, context.east, winningTile, "DISCARD", flags)
    }

    private fun evaluateSelfDraw(
        context: ControllerContext,
        winningTile: MahjongTile,
        flags: List<String> = emptyList(),
    ): GbFanResponse = evaluateFan(context.controller, context.east, winningTile, "SELF_DRAW", flags)

    private fun evaluateFan(
        controller: GbTableRoundController,
        playerId: UUID,
        winningTile: MahjongTile,
        winType: String,
        flags: List<String>,
    ): GbFanResponse {
        val method =
            GbTableRoundController::class.java.getDeclaredMethod(
                "evaluateFanResponse",
                UUID::class.java,
                MahjongTile::class.java,
                String::class.java,
                SeatWind::class.java,
                List::class.java,
            )
        method.isAccessible = true
        return method.invoke(controller, playerId, winningTile, winType, SeatWind.SOUTH, flags) as GbFanResponse
    }

    private fun controllerWithHand(
        hand: List<String>,
        drawn: Boolean = false,
    ): ControllerContext =
        controller().also { context ->
            activateSichuan(context.controller, context.east to "suo")
            forceHand(context.controller, context.east, hand)
            setDrawn(context.controller, context.east, drawn)
        }

    private fun rootController(): ControllerContext = controllerWithHand(rootHand())

    private fun goldenSingleWaitController(): ControllerContext =
        controller().also { context ->
            activateSichuan(context.controller, context.east to "suo")
            forceHand(context.controller, context.east, listOf("P2"))
            setDrawn(context.controller, context.east, false)
            addPung(context.controller, context.east, "M2")
            addPung(context.controller, context.east, "M5")
            addPung(context.controller, context.east, "P3")
            addPung(context.controller, context.east, "P8")
        }

    private fun controller(): ControllerContext {
        val seats = EnumMap<SeatWind, UUID>(SeatWind::class.java)
        val names = mutableMapOf<UUID, String>()
        SeatWind.values().forEach { wind ->
            val playerId = player(wind)
            seats[wind] = playerId
            names[playerId] = wind.name
        }
        val controller = GbTableRoundController(MahjongRule(), seats, names, noNativeGateway(), GbRuleProfile.SICHUAN)
        controller.startRound()
        return ControllerContext(controller, player(SeatWind.EAST))
    }

    private fun noNativeGateway(): GbNativeRulesGateway =
        object : GbNativeRulesGateway() {
            override fun isAvailable(): Boolean = true

            override fun evaluateFan(request: GbFanRequest): GbFanResponse =
                GbFanResponse(false, 0, emptyList(), "native gateway should not be used for Sichuan tests")

            override fun evaluateTing(request: GbTingRequest): GbTingResponse =
                GbTingResponse(false, emptyList(), "native gateway should not be used for Sichuan tests")

            override fun evaluateWin(request: GbWinRequest): GbWinResponse =
                GbWinResponse(false, error = "native gateway should not be used for Sichuan tests")
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

    private fun setDrawn(
        controller: GbTableRoundController,
        playerId: UUID,
        drawn: Boolean,
    ) {
        val field = GbTableRoundController::class.java.getDeclaredField("hasDrawnTile")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val drawnMap = field.get(controller) as MutableMap<UUID, Boolean>
        drawnMap[playerId] = drawn
    }

    private fun addPung(
        controller: GbTableRoundController,
        playerId: UUID,
        tile: String,
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
        melds.getValue(playerId).add(pung.invoke(null, MahjongTile.valueOf(tile), SeatWind.SOUTH, SeatWind.EAST))
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

    private data class ControllerContext(
        val controller: GbTableRoundController,
        val east: UUID,
    )

    private companion object {
        fun pingHuDiscardHand(): List<String> =
            listOf(
                "M1",
                "M1",
                "M1",
                "M2",
                "M3",
                "M4",
                "M5",
                "M6",
                "M7",
                "P2",
                "P3",
                "P4",
                "P9",
            )

        fun pingHuSelfDrawHand(): List<String> = pingHuDiscardHand() + "P9"

        fun rootHand(): List<String> =
            listOf(
                "M1",
                "M1",
                "M1",
                "M1",
                "M2",
                "M3",
                "P2",
                "P3",
                "P4",
                "P5",
                "P6",
                "P7",
                "M9",
            )

        fun allPungsHand(): List<String> =
            listOf(
                "M2",
                "M2",
                "M2",
                "M5",
                "M5",
                "M5",
                "P3",
                "P3",
                "P3",
                "P5",
                "P5",
                "P5",
                "P9",
            )

        fun fullFlushHand(): List<String> =
            listOf(
                "M1",
                "M1",
                "M1",
                "M2",
                "M3",
                "M4",
                "M5",
                "M6",
                "M7",
                "M7",
                "M8",
                "M9",
                "M9",
            )

        fun sevenPairsHand(): List<String> =
            listOf(
                "M1",
                "M1",
                "M2",
                "M2",
                "M3",
                "M3",
                "P4",
                "P4",
                "P5",
                "P5",
                "M6",
                "M6",
                "P6",
            )

        fun longSevenPairsHand(): List<String> =
            listOf(
                "M1",
                "M1",
                "M1",
                "M1",
                "M2",
                "M2",
                "M3",
                "M3",
                "P4",
                "P4",
                "P5",
                "P5",
                "P6",
            )

        fun jiangDuiHand(): List<String> =
            listOf(
                "M2",
                "M2",
                "M2",
                "M5",
                "M5",
                "M5",
                "P2",
                "P2",
                "P2",
                "P5",
                "P5",
                "P5",
                "M8",
            )

        fun player(wind: SeatWind): UUID = UUID.nameUUIDFromBytes(wind.name.toByteArray())
    }
}
