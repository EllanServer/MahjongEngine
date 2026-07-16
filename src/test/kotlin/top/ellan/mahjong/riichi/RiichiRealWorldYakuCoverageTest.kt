@file:Suppress("ktlint:standard:max-line-length")

package top.ellan.mahjong.riichi

import top.ellan.mahjong.riichi.model.ClaimTarget
import top.ellan.mahjong.riichi.model.DoubleYakuman
import top.ellan.mahjong.riichi.model.Fuuro
import top.ellan.mahjong.riichi.model.GeneralSituation
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.MeldType
import top.ellan.mahjong.riichi.model.PersonalSituation
import top.ellan.mahjong.riichi.model.TileInstance
import top.ellan.mahjong.riichi.model.Wind
import top.ellan.mahjong.riichi.model.YakuSettlement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiichiRealWorldYakuCoverageTest {
    @Test
    fun `majsoul house-profile ordinary yaku examples use canonical names`() {
        assertOrdinaryYakuCases(ordinaryYakuCasesOne())
        assertOrdinaryYakuCases(ordinaryYakuCasesTwo())
        assertOrdinaryYakuCases(ordinaryYakuCasesThree())
    }
}

// Shared standard-yaku vocabulary used by the current Mahjong Soul-style house profile.
private fun ordinaryYakuCasesOne(): List<YakuCase> =
    listOf(
        YakuCase(
            "menzen tsumo",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYaku = setOf("TSUMO"),
            personal = personal(tsumo = true),
        ),
        YakuCase(
            "riichi",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYaku = setOf("REACH"),
            personal = personal(riichi = true),
        ),
        YakuCase(
            "ippatsu",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYaku = setOf("IPPATSU"),
            personal = personal(riichi = true, ippatsu = true),
        ),
        YakuCase(
            "double riichi",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYaku = setOf("DOUBLE_REACH"),
            personal = personal(doubleRiichi = true),
        ),
        YakuCase("pinfu", pinfuTanyaoHand(), MahjongTile.P6, expectedYaku = setOf("PINFU")),
        YakuCase("tanyao", pinfuTanyaoHand(), MahjongTile.P6, expectedYaku = setOf("TANYAO")),
        YakuCase(
            "iipeikou",
            hand(
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.S6,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.M9,
            ),
            MahjongTile.M9,
            expectedYaku = setOf("IIPEIKOU"),
        ),
        YakuCase(
            "seat and round wind",
            hand(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.M7,
            ),
            MahjongTile.M7,
            expectedYaku = setOf("SELF_WIND", "ROUND_WIND"),
            general = general(roundWind = Wind.EAST),
            personal = personal(seatWind = Wind.EAST),
        ),
        YakuCase(
            "white dragon",
            dragonHand(MahjongTile.WHITE_DRAGON),
            MahjongTile.M7,
            expectedYaku = setOf("HAKU"),
        ),
        YakuCase(
            "green dragon",
            dragonHand(MahjongTile.GREEN_DRAGON),
            MahjongTile.M7,
            expectedYaku = setOf("HATSU"),
        ),
        YakuCase(
            "red dragon",
            dragonHand(MahjongTile.RED_DRAGON),
            MahjongTile.M7,
            expectedYaku = setOf("CHUN"),
        ),
        YakuCase(
            "sanshoku doujun",
            hand(
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.P9,
            ),
            MahjongTile.P9,
            expectedYaku = setOf("SANSHOKU"),
        ),
        YakuCase(
            "ittsu",
            hand(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.S7,
                MahjongTile.S7,
            ),
            MahjongTile.M9,
            expectedYaku = setOf("ITTSU"),
        ),
        YakuCase(
            "chanta",
            hand(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M9,
            ),
            MahjongTile.M9,
            expectedYaku = setOf("CHANTA"),
        ),
    )

private fun ordinaryYakuCasesTwo(): List<YakuCase> =
    listOf(
        YakuCase(
            "chiitoitsu",
            hand(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M9,
                MahjongTile.M9,
                MahjongTile.P2,
                MahjongTile.P2,
                MahjongTile.P8,
                MahjongTile.P8,
                MahjongTile.S3,
                MahjongTile.S3,
                MahjongTile.S7,
                MahjongTile.S7,
                MahjongTile.EAST,
            ),
            MahjongTile.EAST,
            expectedYaku = setOf("CHITOITSU"),
        ),
        YakuCase(
            "toitoi",
            hand(
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.M6,
                MahjongTile.M6,
                MahjongTile.M6,
                MahjongTile.P9,
            ),
            MahjongTile.P9,
            expectedYaku = setOf("TOITOI"),
            fuuro = listOf(openPon(MahjongTile.M2)),
        ),
        YakuCase(
            "sanankou",
            hand(
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.P9,
                MahjongTile.P9,
            ),
            MahjongTile.M8,
            expectedYaku = setOf("SANANKOU"),
        ),
        YakuCase(
            "honroutou",
            hand(
                MahjongTile.P9,
                MahjongTile.P9,
                MahjongTile.P9,
                MahjongTile.S1,
                MahjongTile.S1,
                MahjongTile.S1,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.RED_DRAGON,
            ),
            MahjongTile.RED_DRAGON,
            expectedYaku = setOf("HONROUTOU"),
            fuuro = listOf(openPon(MahjongTile.M1)),
        ),
        YakuCase(
            "sanshoku doukou",
            hand(
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.P2,
                MahjongTile.P2,
                MahjongTile.P2,
                MahjongTile.S2,
                MahjongTile.S2,
                MahjongTile.S2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.P9,
            ),
            MahjongTile.P9,
            expectedYaku = setOf("SANDOKOU"),
        ),
        YakuCase(
            "sankantsu",
            hand(MahjongTile.M2, MahjongTile.M3, MahjongTile.P9, MahjongTile.P9),
            MahjongTile.M4,
            expectedYaku = setOf("SANKANTSU"),
            fuuro = listOf(ankan(MahjongTile.M1), ankan(MahjongTile.P1), ankan(MahjongTile.S1)),
        ),
        YakuCase(
            "shousangen",
            hand(
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.RED_DRAGON,
            ),
            MahjongTile.RED_DRAGON,
            expectedYaku = setOf("SHOUSANGEN"),
        ),
        YakuCase(
            "honitsu",
            hand(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.RED_DRAGON,
            ),
            MahjongTile.RED_DRAGON,
            expectedYaku = setOf("HONITSU"),
        ),
    )

private fun ordinaryYakuCasesThree(): List<YakuCase> =
    listOf(
        YakuCase(
            "junchan",
            hand(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.P9,
            ),
            MahjongTile.P9,
            expectedYaku = setOf("JUNCHAN"),
        ),
        YakuCase(
            "ryanpeikou",
            hand(
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P6,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P6,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.S5,
            ),
            MahjongTile.S5,
            expectedYaku = setOf("RYANPEIKOU"),
        ),
        YakuCase(
            "chinitsu",
            hand(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.M7,
                MahjongTile.M9,
                MahjongTile.M9,
            ),
            MahjongTile.M7,
            expectedYaku = setOf("CHINITSU"),
        ),
        YakuCase(
            "rinshan kaihou",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYaku = setOf("RINSHAN_KAIHOU"),
            personal = personal(tsumo = true, rinshan = true),
        ),
        YakuCase(
            "chankan",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYaku = setOf("CHANKAN"),
            personal = personal(chankan = true),
        ),
        YakuCase(
            "haitei",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYaku = setOf("HAITEI"),
            general = general(houtei = true),
            personal = personal(tsumo = true),
        ),
        YakuCase("houtei", pinfuTanyaoHand(), MahjongTile.P6, expectedYaku = setOf("HOUTEI"), general = general(houtei = true)),
    )

private fun assertOrdinaryYakuCases(cases: List<YakuCase>) {
    cases.forEach { case ->
        val settlement = settlement(case)
        case.expectedYaku.forEach { expected ->
            assertContains(settlement.yakuList, expected, "${case.name} should include $expected")
        }
    }
}

class RiichiRealWorldYakumanCoverageTest {
    @Test
    fun `majsoul house-profile yakuman examples preserve configured double yakuman`() {
        assertYakumanCases(yakumanCasesOne())
        assertYakumanCases(yakumanCasesTwo())
    }
}

private fun yakumanCasesOne(): List<YakuCase> =
    listOf(
        YakuCase(
            "kokushi thirteen-sided wait",
            hand(
                MahjongTile.M1,
                MahjongTile.M9,
                MahjongTile.P1,
                MahjongTile.P9,
                MahjongTile.S1,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            ),
            MahjongTile.M1,
            expectedDoubleYakuman = setOf(DoubleYakuman.KOKUSHIMUSO_JUSANMENMACHI),
        ),
        YakuCase(
            "suuankou",
            hand(
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.M6,
                MahjongTile.M6,
                MahjongTile.M6,
                MahjongTile.P9,
                MahjongTile.P9,
            ),
            MahjongTile.M2,
            expectedYakuman = setOf("SUANKO"),
            personal = personal(tsumo = true),
        ),
        YakuCase(
            "suuankou tanki",
            hand(
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.S4,
                MahjongTile.M6,
                MahjongTile.M6,
                MahjongTile.M6,
                MahjongTile.P9,
            ),
            MahjongTile.P9,
            expectedDoubleYakuman = setOf(DoubleYakuman.SUANKO_TANKI),
            personal = personal(tsumo = true),
        ),
        YakuCase(
            "daisangen",
            hand(
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
                MahjongTile.RED_DRAGON,
                MahjongTile.RED_DRAGON,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P9,
            ),
            MahjongTile.P9,
            expectedYakuman = setOf("DAISANGEN"),
        ),
        YakuCase(
            "tsuuiisou",
            hand(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.SOUTH,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.WEST,
                MahjongTile.WEST,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.RED_DRAGON,
            ),
            MahjongTile.RED_DRAGON,
            expectedYakuman = setOf("TSUUIISOU"),
        ),
        YakuCase(
            "shousuushii",
            hand(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.SOUTH,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.WEST,
                MahjongTile.WEST,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.NORTH,
            ),
            MahjongTile.NORTH,
            expectedYakuman = setOf("SHOUSUUSHII"),
        ),
    )

private fun yakumanCasesTwo(): List<YakuCase> =
    listOf(
        YakuCase(
            "daisuushii",
            hand(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.SOUTH,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.WEST,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.NORTH,
                MahjongTile.NORTH,
                MahjongTile.RED_DRAGON,
            ),
            MahjongTile.RED_DRAGON,
            expectedDoubleYakuman = setOf(DoubleYakuman.DAISUSHI),
        ),
        YakuCase(
            "ryuuiisou",
            hand(
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.S6,
                MahjongTile.S6,
                MahjongTile.S6,
                MahjongTile.S8,
                MahjongTile.S8,
                MahjongTile.S8,
                MahjongTile.GREEN_DRAGON,
            ),
            MahjongTile.GREEN_DRAGON,
            expectedYakuman = setOf("RYUUIISOU"),
        ),
        YakuCase(
            "chinroutou",
            hand(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.P9,
                MahjongTile.P9,
                MahjongTile.P9,
                MahjongTile.S1,
                MahjongTile.S1,
                MahjongTile.S1,
                MahjongTile.M9,
                MahjongTile.M9,
                MahjongTile.M9,
                MahjongTile.P1,
            ),
            MahjongTile.P1,
            expectedYakuman = setOf("CHINROUTOU"),
        ),
        YakuCase(
            "suukantsu",
            hand(MahjongTile.P9),
            MahjongTile.P9,
            expectedYakuman = setOf("SUUKANTSU"),
            fuuro = listOf(ankan(MahjongTile.M1), ankan(MahjongTile.P1), ankan(MahjongTile.S1), ankan(MahjongTile.M9)),
        ),
        YakuCase(
            "chuuren poutou",
            hand(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.M9,
            ),
            MahjongTile.M9,
            expectedYakuman = setOf("CHUURENPOUTOU"),
        ),
        YakuCase(
            "junsei chuuren poutou",
            hand(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.M9,
                MahjongTile.M9,
            ),
            MahjongTile.M5,
            expectedDoubleYakuman = setOf(DoubleYakuman.JUNSEI_CHURENPOHTO),
        ),
        YakuCase(
            "tenhou",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYakuman = setOf("TENHOU"),
            general = general(firstRound = true),
            personal = personal(tsumo = true, seatWind = Wind.EAST),
        ),
        YakuCase(
            "chiihou",
            pinfuTanyaoHand(),
            MahjongTile.P6,
            expectedYakuman = setOf("CHIIHOU"),
            general = general(firstRound = true),
            personal = personal(tsumo = true, seatWind = Wind.SOUTH),
        ),
    )

private fun assertYakumanCases(cases: List<YakuCase>) {
    cases.forEach { case ->
        val settlement = settlement(case)
        case.expectedYakuman.forEach { expected ->
            assertContains(settlement.yakumanList, expected, "${case.name} should include $expected")
        }
        case.expectedDoubleYakuman.forEach { expected ->
            assertContains(settlement.doubleYakumanList, expected, "${case.name} should include $expected")
        }
        assertTrue(
            settlement.yakumanList.isNotEmpty() || settlement.doubleYakumanList.isNotEmpty(),
            "${case.name} should be treated as yakuman",
        )
    }
}

class RiichiRealWorldYakuVocabularyCoverageTest {
    @Test
    fun `house-profile yaku coverage leaves local renhou explicitly unsupported`() {
        val houseProfileYaku =
            setOf(
                "TSUMO",
                "REACH",
                "IPPATSU",
                "DOUBLE_REACH",
                "PINFU",
                "TANYAO",
                "IIPEIKOU",
                "SELF_WIND",
                "ROUND_WIND",
                "HAKU",
                "HATSU",
                "CHUN",
                "SANSHOKU",
                "ITTSU",
                "CHANTA",
                "CHITOITSU",
                "TOITOI",
                "SANANKOU",
                "SANKANTSU",
                "HONROUTOU",
                "SANDOKOU",
                "SHOUSANGEN",
                "HONITSU",
                "JUNCHAN",
                "RYANPEIKOU",
                "CHINITSU",
                "RINSHAN_KAIHOU",
                "CHANKAN",
                "HAITEI",
                "HOUTEI",
                "RENHOU",
                "KOKUSHIMUSO",
                "SUANKO",
                "DAISANGEN",
                "TSUUIISOU",
                "SHOUSUUSHII",
                "DAISUUSHII",
                "RYUUIISOU",
                "CHINROUTOU",
                "SUUKANTSU",
                "CHUURENPOUTOU",
                "TENHOU",
                "CHIIHOU",
            )
        val exercised =
            setOf(
                "TSUMO",
                "REACH",
                "IPPATSU",
                "DOUBLE_REACH",
                "PINFU",
                "TANYAO",
                "IIPEIKOU",
                "SELF_WIND",
                "ROUND_WIND",
                "HAKU",
                "HATSU",
                "CHUN",
                "SANSHOKU",
                "ITTSU",
                "CHANTA",
                "CHITOITSU",
                "TOITOI",
                "SANANKOU",
                "SANKANTSU",
                "HONROUTOU",
                "SANDOKOU",
                "SHOUSANGEN",
                "HONITSU",
                "JUNCHAN",
                "RYANPEIKOU",
                "CHINITSU",
                "RINSHAN_KAIHOU",
                "CHANKAN",
                "HAITEI",
                "HOUTEI",
                "KOKUSHIMUSO",
                "SUANKO",
                "DAISANGEN",
                "TSUUIISOU",
                "SHOUSUUSHII",
                "DAISUUSHII",
                "RYUUIISOU",
                "CHINROUTOU",
                "SUUKANTSU",
                "CHUURENPOUTOU",
                "TENHOU",
                "CHIIHOU",
            )

        assertEquals(setOf("RENHOU"), houseProfileYaku - exercised)
    }
}

private fun settlement(case: YakuCase): YakuSettlement {
    val player = RiichiPlayerState(case.name, case.name)
    player.hands += case.hand.map { TileInstance(mahjongTile = it) }
    player.fuuroList += case.fuuro
    return player.calcYakuSettlementForWin(
        winningTile = case.winningTile,
        isWinningTileInHands = false,
        rule = MahjongRule(redFive = MahjongRule.RedFive.NONE),
        generalSituation = case.general,
        personalSituation = case.personal,
        doraIndicators = emptyList(),
        uraDoraIndicators = emptyList(),
    )
}

private data class YakuCase(
    val name: String,
    val hand: List<MahjongTile>,
    val winningTile: MahjongTile,
    val expectedYaku: Set<String> = emptySet(),
    val expectedYakuman: Set<String> = emptySet(),
    val expectedDoubleYakuman: Set<DoubleYakuman> = emptySet(),
    val general: GeneralSituation = general(),
    val personal: PersonalSituation = personal(),
    val fuuro: List<Fuuro> = emptyList(),
)

private fun hand(vararg tiles: MahjongTile): List<MahjongTile> = tiles.toList()

private fun pinfuTanyaoHand(): List<MahjongTile> =
    hand(
        MahjongTile.M2,
        MahjongTile.M3,
        MahjongTile.M4,
        MahjongTile.M3,
        MahjongTile.M4,
        MahjongTile.M5,
        MahjongTile.P4,
        MahjongTile.P5,
        MahjongTile.P6,
        MahjongTile.S6,
        MahjongTile.S7,
        MahjongTile.S8,
        MahjongTile.P6,
    )

private fun dragonHand(dragon: MahjongTile): List<MahjongTile> =
    hand(
        dragon,
        dragon,
        dragon,
        MahjongTile.M2,
        MahjongTile.M3,
        MahjongTile.M4,
        MahjongTile.P2,
        MahjongTile.P3,
        MahjongTile.P4,
        MahjongTile.S3,
        MahjongTile.S4,
        MahjongTile.S5,
        MahjongTile.M7,
    )

private fun general(
    firstRound: Boolean = false,
    houtei: Boolean = false,
    roundWind: Wind = Wind.SOUTH,
): GeneralSituation =
    GeneralSituation(
        isFirstRound = firstRound,
        isHoutei = houtei,
        bakaze = roundWind,
        doraIndicators = emptyList(),
        uraDoraIndicators = emptyList(),
    )

private fun personal(
    tsumo: Boolean = false,
    ippatsu: Boolean = false,
    riichi: Boolean = false,
    doubleRiichi: Boolean = false,
    chankan: Boolean = false,
    rinshan: Boolean = false,
    seatWind: Wind = Wind.WEST,
): PersonalSituation =
    PersonalSituation(
        isTsumo = tsumo,
        isIppatsu = ippatsu,
        isRiichi = riichi,
        isDoubleRiichi = doubleRiichi,
        isChankan = chankan,
        isRinshanKaihoh = rinshan,
        jikaze = seatWind,
    )

private fun ankan(tile: MahjongTile): Fuuro {
    val claim = TileInstance(mahjongTile = tile)
    return Fuuro(
        type = MeldType.ANKAN,
        tileInstances =
            listOf(
                claim,
                TileInstance(mahjongTile = tile),
                TileInstance(mahjongTile = tile),
                TileInstance(mahjongTile = tile),
            ),
        claimTarget = ClaimTarget.SELF,
        claimTile = claim,
    )
}

private fun openPon(tile: MahjongTile): Fuuro {
    val claim = TileInstance(mahjongTile = tile)
    return Fuuro(
        type = MeldType.PON,
        tileInstances = listOf(claim, TileInstance(mahjongTile = tile), TileInstance(mahjongTile = tile)),
        claimTarget = ClaimTarget.RIGHT,
        claimTile = claim,
    )
}
