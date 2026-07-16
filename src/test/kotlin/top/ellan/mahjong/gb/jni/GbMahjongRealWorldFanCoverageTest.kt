package top.ellan.mahjong.gb.jni

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GbMahjongRealWorldFanCoverageTest {
    @Test
    fun `MCR source-backed official fan catalog matches JNI fan keys`() {
        // Source baseline: Chinese Official Mahjong Competition Rules - MCR fan names.
        val jniKeys = extractJniFanKeys()

        assertEquals(MCR_OFFICIAL_FAN_KEYS + MCR_INTERNAL_COMPOSITE_FAN_KEYS, jniKeys)
    }

    @Test
    fun `MCR source-backed representative hands score through the native bridge`() {
        val bridge = GbMahjongNativeBridge()
        requireGbNativeBridge(bridge)

        assertFan(
            bridge,
            "big four winds",
            hand = listOf("F1", "F1", "F1", "F2", "F2", "F2", "F3", "F3", "F3", "F4", "F4", "F4", "J1"),
            winningTile = "J1",
            expected = "DASIXI",
        )
        assertFan(
            bridge,
            "big three dragons",
            hand = listOf("J1", "J1", "J1", "J2", "J2", "J2", "J3", "J3", "J3", "W2", "W3", "W4", "B9"),
            winningTile = "B9",
            expected = "DASANYUAN",
        )
        assertFan(
            bridge,
            "thirteen orphans",
            hand = listOf("W1", "W9", "T1", "T9", "B1", "B9", "F1", "F2", "F3", "F4", "J1", "J2", "J3"),
            winningTile = "W1",
            expected = "SHISANYAO",
        )
        assertFan(
            bridge,
            "seven pairs",
            hand = listOf("W1", "W1", "W2", "W2", "W3", "W3", "B4", "B4", "B5", "B5", "T6", "T6", "T7"),
            winningTile = "T7",
            expected = "QIDUI",
        )
        assertFan(
            bridge,
            "pure straight",
            hand = listOf("W1", "W2", "W3", "W4", "W5", "W6", "W7", "W8", "B1", "B1", "B1", "T2", "T2"),
            winningTile = "W9",
            expected = "QINGLONG",
        )
        assertFan(
            bridge,
            "all green",
            hand = listOf("T2", "T3", "T4", "T2", "T3", "T4", "T6", "T6", "T6", "T8", "T8", "T8", "J2"),
            winningTile = "J2",
            expected = "LVYISE",
        )
        assertFan(
            bridge,
            "self draw",
            hand = listOf("W2", "W3", "W4", "B2", "B3", "B4", "T2", "T3", "T4", "W6", "W7", "W8", "B9"),
            winningTile = "B9",
            expected = "BUQIUREN",
            winType = "SELF_DRAW",
        )
    }

    @Test
    fun `one melded and one concealed kong score the official six points`() {
        val bridge = GbMahjongNativeBridge()
        requireGbNativeBridge(bridge)

        val result =
            bridge.evaluateFan(
                GbFanRequest(
                    handTiles = listOf("W2", "W3", "W4", "B2", "B3", "B4", "T5"),
                    melds =
                        listOf(
                            GbMeldInput(
                                type = "CONCEALED_KONG",
                                tiles = listOf("W1", "W1", "W1", "W1"),
                                claimedTile = null,
                                fromSeat = null,
                                open = false,
                            ),
                            GbMeldInput(
                                type = "OPEN_KONG",
                                tiles = listOf("B9", "B9", "B9", "B9"),
                                claimedTile = "B9",
                                fromSeat = "LEFT",
                                open = true,
                            ),
                        ),
                    winningTile = "T5",
                    winType = "DISCARD",
                    seatWind = "EAST",
                    roundWind = "EAST",
                ),
            )

        assertTrue(result.valid, result.error)
        val mixedKong = result.fans.single { it.name == "MINGANGANG" }
        assertEquals(6, mixedKong.fan)
        assertEquals(1, mixedKong.count)
    }

    @Test
    fun `formal multi wait does not gain single wait when one wait is exhausted`() {
        val bridge = GbMahjongNativeBridge()
        requireGbNativeBridge(bridge)

        val result =
            bridge.evaluateFan(
                GbFanRequest(
                    handTiles =
                        listOf(
                            "W1",
                            "W2",
                            "W3",
                            "W4",
                            "W4",
                            "W4",
                            "W4",
                            "W6",
                            "W7",
                            "W8",
                            "B4",
                            "B5",
                            "B6",
                        ),
                    winningTile = "W1",
                    winType = "SELF_DRAW",
                    seatWind = "EAST",
                    roundWind = "NORTH",
                ),
            )

        assertTrue(result.valid, result.error)
        assertEquals(8, result.totalFan)
        assertFalse(result.fans.any { it.name == "DANDIAOJIANG" })
    }

    @Test
    fun `MCR win settlement includes the eight point basic payment from every opponent`() {
        val bridge = GbMahjongNativeBridge()
        requireGbNativeBridge(bridge)
        val hand = listOf("F1", "F1", "F1", "F2", "F2", "F2", "F3", "F3", "F3", "F4", "F4", "F4", "J1")
        val seatPoints =
            listOf(
                GbSeatPointsInput("EAST", 500),
                GbSeatPointsInput("SOUTH", 500),
                GbSeatPointsInput("WEST", 500),
                GbSeatPointsInput("NORTH", 500),
            )

        val ron =
            bridge.evaluateWin(
                GbWinRequest(
                    handTiles = hand,
                    winningTile = "J1",
                    winType = "DISCARD",
                    winnerSeat = "EAST",
                    discarderSeat = "SOUTH",
                    seatWind = "EAST",
                    roundWind = "EAST",
                    seatPoints = seatPoints,
                ),
            )
        assertTrue(ron.valid, ron.error)
        val ronDeltas = ron.scoreDeltas.associate { it.seat to it.delta }
        assertEquals(ron.totalFan + 24, ronDeltas.getValue("EAST"))
        assertEquals(-(ron.totalFan + 8), ronDeltas.getValue("SOUTH"))
        assertEquals(-8, ronDeltas.getValue("WEST"))
        assertEquals(-8, ronDeltas.getValue("NORTH"))
        assertEquals(0, ronDeltas.values.sum())
        assertEquals(2000, seatPoints.sumOf { it.points } + ronDeltas.values.sum())

        val selfDraw =
            bridge.evaluateWin(
                GbWinRequest(
                    handTiles = hand,
                    winningTile = "J1",
                    winType = "SELF_DRAW",
                    winnerSeat = "EAST",
                    seatWind = "EAST",
                    roundWind = "EAST",
                    seatPoints = seatPoints,
                ),
            )
        assertTrue(selfDraw.valid, selfDraw.error)
        val selfDrawDeltas = selfDraw.scoreDeltas.associate { it.seat to it.delta }
        val payment = selfDraw.totalFan + 8
        assertEquals(payment * 3, selfDrawDeltas.getValue("EAST"))
        assertEquals(-payment, selfDrawDeltas.getValue("SOUTH"))
        assertEquals(-payment, selfDrawDeltas.getValue("WEST"))
        assertEquals(-payment, selfDrawDeltas.getValue("NORTH"))
        assertEquals(0, selfDrawDeltas.values.sum())
        assertEquals(2000, seatPoints.sumOf { it.points } + selfDrawDeltas.values.sum())
    }

    @Test
    fun `flower points do not qualify a sub eight point hand to win or ting`() {
        val bridge = GbMahjongNativeBridge()
        requireGbNativeBridge(bridge)
        val hand = listOf("B2", "B2", "B2", "W2", "W2", "W2", "B4", "B5", "B6", "B6")
        val melds =
            listOf(
                GbMeldInput(
                    type = "CHOW",
                    tiles = listOf("T1", "T2", "T3"),
                    claimedTile = "T1",
                    fromSeat = "LEFT",
                    open = true,
                ),
            )
        val flowers = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val fanRequest =
            GbFanRequest(
                handTiles = hand,
                melds = melds,
                winningTile = "B6",
                winType = "DISCARD",
                seatWind = "EAST",
                roundWind = "EAST",
                flowerTiles = flowers,
            )
        val fan = bridge.evaluateFan(fanRequest)
        assertTrue(fan.valid, fan.error)
        val flowerFan = fan.fans.filter { it.name == "HUAPAI" }.sumOf { it.fan * it.count }
        assertTrue(fan.totalFan >= 8)
        assertTrue(fan.totalFan - flowerFan < 8)

        val win =
            bridge.evaluateWin(
                GbWinRequest(
                    handTiles = hand,
                    melds = melds,
                    winningTile = "B6",
                    winType = "DISCARD",
                    winnerSeat = "EAST",
                    discarderSeat = "SOUTH",
                    seatWind = "EAST",
                    roundWind = "EAST",
                    seatPoints =
                        listOf(
                            GbSeatPointsInput("EAST", 0),
                            GbSeatPointsInput("SOUTH", 0),
                            GbSeatPointsInput("WEST", 0),
                            GbSeatPointsInput("NORTH", 0),
                        ),
                    flowerTiles = flowers,
                ),
            )
        assertFalse(win.valid)

        val ting =
            bridge.evaluateTing(
                GbTingRequest(
                    handTiles = hand,
                    melds = melds,
                    seatWind = "EAST",
                    roundWind = "EAST",
                    flowerTiles = flowers,
                ),
            )
        assertTrue(ting.valid, ting.error)
        assertTrue(ting.waits.none { it.tile == "B6" })
    }

    @Test
    fun `ting includes a seven fan open hand that reaches eight only by self draw`() {
        val bridge = GbMahjongNativeBridge()
        requireGbNativeBridge(bridge)
        val hand = listOf("B2", "B2", "B2", "W2", "W2", "W2", "B4", "B5", "B6", "B6")
        val melds =
            listOf(
                GbMeldInput(
                    type = "CHOW",
                    tiles = listOf("T4", "T5", "T6"),
                    claimedTile = "T4",
                    fromSeat = "LEFT",
                    open = true,
                ),
            )
        val seatPoints =
            listOf(
                GbSeatPointsInput("EAST", 500),
                GbSeatPointsInput("SOUTH", 500),
                GbSeatPointsInput("WEST", 500),
                GbSeatPointsInput("NORTH", 500),
            )

        val ting =
            bridge.evaluateTing(
                GbTingRequest(
                    handTiles = hand,
                    melds = melds,
                    seatWind = "EAST",
                    roundWind = "EAST",
                ),
            )
        assertTrue(ting.valid, ting.error)
        val selfDrawOnlyWait = ting.waits.single { it.tile == "B6" }
        assertEquals(8, selfDrawOnlyWait.totalFan)
        assertContains(selfDrawOnlyWait.fans.map { it.name }, "ZIMO")

        val ron =
            bridge.evaluateWin(
                GbWinRequest(
                    handTiles = hand,
                    melds = melds,
                    winningTile = "B6",
                    winType = "DISCARD",
                    winnerSeat = "EAST",
                    discarderSeat = "SOUTH",
                    seatWind = "EAST",
                    roundWind = "EAST",
                    seatPoints = seatPoints,
                ),
            )
        assertFalse(ron.valid)

        val selfDraw =
            bridge.evaluateWin(
                GbWinRequest(
                    handTiles = hand,
                    melds = melds,
                    winningTile = "B6",
                    winType = "SELF_DRAW",
                    winnerSeat = "EAST",
                    seatWind = "EAST",
                    roundWind = "EAST",
                    seatPoints = seatPoints,
                ),
            )
        assertTrue(selfDraw.valid, selfDraw.error)
        assertEquals(8, selfDraw.totalFan)
        assertContains(selfDraw.fans.map { it.name }, "ZIMO")
    }

    private fun assertFan(
        bridge: GbMahjongNativeBridge,
        caseName: String,
        hand: List<String>,
        winningTile: String,
        expected: String,
        winType: String = "DISCARD",
        flags: List<String> = emptyList(),
    ) {
        val response =
            bridge.evaluateFan(
                GbFanRequest(
                    handTiles = hand,
                    winningTile = winningTile,
                    winType = winType,
                    seatWind = "EAST",
                    roundWind = "EAST",
                    flags = flags,
                ),
            )

        assertTrue(response.valid, "$caseName should be a valid MCR hand: ${response.error}")
        assertContains(response.fans.map { it.name }, expected, "$caseName should include $expected")
    }

    private fun extractJniFanKeys(): List<String> {
        val source = Files.readString(Path.of("native/gbmahjong/src/gbmahjong_jni.cpp"))
        val arrayBody =
            source
                .substringAfter("static const char* KEYS[] = {")
                .substringBefore("};")
        return arrayBody
            .lineSequence()
            .map { it.trim().removeSuffix(",") }
            .filter { it.startsWith("\"") }
            .map { it.trim('"') }
            .filter { it != "INVALID" }
            .toList()
    }

    private companion object {
        val MCR_OFFICIAL_FAN_KEYS =
            listOf(
                "DASIXI",
                "DASANYUAN",
                "LVYISE",
                "JIULIANBAODENG",
                "SIGANG",
                "LIANQIDUI",
                "SHISANYAO",
                "QINGYAOJIU",
                "XIAOSIXI",
                "XIAOSANYUAN",
                "ZIYISE",
                "SIANKE",
                "YISESHUANGLONGHUI",
                "YISESITONGSHUN",
                "YISESIJIEGAO",
                "YISESIBUGAO",
                "SANGANG",
                "HUNYAOJIU",
                "QIDUI",
                "QIXINGBUKAO",
                "QUANSHUANGKE",
                "QINGYISE",
                "YISESANTONGSHUN",
                "YISESANJIEGAO",
                "QUANDA",
                "QUANZHONG",
                "QUANXIAO",
                "QINGLONG",
                "SANSESHUANGLONGHUI",
                "YISESANBUGAO",
                "QUANDAIWU",
                "SANTONGKE",
                "SANANKE",
                "QUANBUKAO",
                "ZUHELONG",
                "DAYUWU",
                "XIAOYUWU",
                "SANFENGKE",
                "HUALONG",
                "TUIBUDAO",
                "SANSESANTONGSHUN",
                "SANSESANJIEGAO",
                "WUFANHU",
                "MIAOSHOUHUICHUN",
                "HAIDILAOYUE",
                "GANGSHANGKAIHUA",
                "QIANGGANGHU",
                "PENGPENGHU",
                "HUNYISE",
                "SANSESANBUGAO",
                "WUMENQI",
                "QUANQIUREN",
                "SHUANGANGANG",
                "SHUANGJIANKE",
                "QUANDAIYAO",
                "BUQIUREN",
                "SHUANGMINGGANG",
                "HUJUEZHANG",
                "JIANKE",
                "QUANFENGKE",
                "MENFENGKE",
                "MENQIANQING",
                "PINGHU",
                "SIGUIYI",
                "SHUANGTONGKE",
                "SHUANGANKE",
                "ANGANG",
                "DUANYAO",
                "YIBANGAO",
                "XIXIANGFENG",
                "LIANLIU",
                "LAOSHAOFU",
                "YAOJIUKE",
                "MINGGANG",
                "QUEYIMEN",
                "WUZI",
                "BIANZHANG",
                "KANZHANG",
                "DANDIAOJIANG",
                "ZIMO",
                "HUAPAI",
            )

        val MCR_INTERNAL_COMPOSITE_FAN_KEYS = listOf("MINGANGANG")
    }
}
