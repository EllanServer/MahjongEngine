package top.ellan.mahjong.riichi.model

import mahjongutils.models.Furo
import mahjongutils.models.Tile
import mahjongutils.models.TileType
import java.util.UUID
import mahjongutils.models.Wind as UtilsWind

enum class MahjongTile {
    M1,
    M2,
    M3,
    M4,
    M5,
    M6,
    M7,
    M8,
    M9,
    P1,
    P2,
    P3,
    P4,
    P5,
    P6,
    P7,
    P8,
    P9,
    S1,
    S2,
    S3,
    S4,
    S5,
    S6,
    S7,
    S8,
    S9,
    EAST,
    SOUTH,
    WEST,
    NORTH,
    WHITE_DRAGON,
    GREEN_DRAGON,
    RED_DRAGON,
    M5_RED,
    P5_RED,
    S5_RED,
    PLUM,
    ORCHID,
    BAMBOO,
    CHRYSANTHEMUM,
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER,
    UNKNOWN,
    ;

    val isRed: Boolean
        get() = this == M5_RED || this == P5_RED || this == S5_RED

    val isFlower: Boolean
        get() =
            when (this) {
                PLUM, ORCHID, BAMBOO, CHRYSANTHEMUM, SPRING, SUMMER, AUTUMN, WINTER -> true
                else -> false
            }

    val code: Int = ordinal

    val baseTile: MahjongTile
        get() =
            when (this) {
                M5_RED -> M5
                P5_RED -> P5
                S5_RED -> S5
                else -> this
            }

    val utilsTile: Tile
        get() =
            when (this) {
                M1 -> {
                    Tile.get(TileType.M, 1)
                }

                M2 -> {
                    Tile.get(TileType.M, 2)
                }

                M3 -> {
                    Tile.get(TileType.M, 3)
                }

                M4 -> {
                    Tile.get(TileType.M, 4)
                }

                M5 -> {
                    Tile.get(TileType.M, 5)
                }

                M6 -> {
                    Tile.get(TileType.M, 6)
                }

                M7 -> {
                    Tile.get(TileType.M, 7)
                }

                M8 -> {
                    Tile.get(TileType.M, 8)
                }

                M9 -> {
                    Tile.get(TileType.M, 9)
                }

                P1 -> {
                    Tile.get(TileType.P, 1)
                }

                P2 -> {
                    Tile.get(TileType.P, 2)
                }

                P3 -> {
                    Tile.get(TileType.P, 3)
                }

                P4 -> {
                    Tile.get(TileType.P, 4)
                }

                P5 -> {
                    Tile.get(TileType.P, 5)
                }

                P6 -> {
                    Tile.get(TileType.P, 6)
                }

                P7 -> {
                    Tile.get(TileType.P, 7)
                }

                P8 -> {
                    Tile.get(TileType.P, 8)
                }

                P9 -> {
                    Tile.get(TileType.P, 9)
                }

                S1 -> {
                    Tile.get(TileType.S, 1)
                }

                S2 -> {
                    Tile.get(TileType.S, 2)
                }

                S3 -> {
                    Tile.get(TileType.S, 3)
                }

                S4 -> {
                    Tile.get(TileType.S, 4)
                }

                S5 -> {
                    Tile.get(TileType.S, 5)
                }

                S6 -> {
                    Tile.get(TileType.S, 6)
                }

                S7 -> {
                    Tile.get(TileType.S, 7)
                }

                S8 -> {
                    Tile.get(TileType.S, 8)
                }

                S9 -> {
                    Tile.get(TileType.S, 9)
                }

                EAST -> {
                    Tile.get(TileType.Z, 1)
                }

                SOUTH -> {
                    Tile.get(TileType.Z, 2)
                }

                WEST -> {
                    Tile.get(TileType.Z, 3)
                }

                NORTH -> {
                    Tile.get(TileType.Z, 4)
                }

                WHITE_DRAGON -> {
                    Tile.get(TileType.Z, 5)
                }

                GREEN_DRAGON -> {
                    Tile.get(TileType.Z, 6)
                }

                RED_DRAGON -> {
                    Tile.get(TileType.Z, 7)
                }

                M5_RED -> {
                    Tile.get(TileType.M, 0)
                }

                P5_RED -> {
                    Tile.get(TileType.P, 0)
                }

                S5_RED -> {
                    Tile.get(TileType.S, 0)
                }

                PLUM, ORCHID, BAMBOO, CHRYSANTHEMUM, SPRING, SUMMER, AUTUMN, WINTER -> {
                    Tile.get(TileType.M, 1)
                }

                UNKNOWN -> {
                    Tile.get(TileType.M, 1)
                }
            }

    val scoringTile: Tile
        get() = baseTile.utilsTile

    val sortOrder: Int
        get() =
            when (this) {
                M5_RED -> 4
                P5_RED -> 13
                S5_RED -> 22
                else -> code
            }

    val nextTile: MahjongTile
        get() =
            if (isFlower) {
                this
            } else {
                when (baseTile) {
                    EAST -> {
                        SOUTH
                    }

                    SOUTH -> {
                        WEST
                    }

                    WEST -> {
                        NORTH
                    }

                    NORTH -> {
                        EAST
                    }

                    WHITE_DRAGON -> {
                        GREEN_DRAGON
                    }

                    GREEN_DRAGON -> {
                        RED_DRAGON
                    }

                    RED_DRAGON -> {
                        WHITE_DRAGON
                    }

                    else -> {
                        val tile = baseTile.utilsTile
                        if (tile.type == TileType.Z) {
                            baseTile
                        } else {
                            val nextNumber = if (tile.realNum == 9) 1 else tile.realNum + 1
                            fromUtilsTile(Tile.get(tile.type, nextNumber))
                        }
                    }
                }
            }

    val previousTile: MahjongTile
        get() =
            if (isFlower) {
                this
            } else {
                when (baseTile) {
                    EAST -> {
                        NORTH
                    }

                    SOUTH -> {
                        EAST
                    }

                    WEST -> {
                        SOUTH
                    }

                    NORTH -> {
                        WEST
                    }

                    WHITE_DRAGON -> {
                        RED_DRAGON
                    }

                    GREEN_DRAGON -> {
                        WHITE_DRAGON
                    }

                    RED_DRAGON -> {
                        GREEN_DRAGON
                    }

                    else -> {
                        val tile = baseTile.utilsTile
                        if (tile.type == TileType.Z) {
                            baseTile
                        } else {
                            val previousNumber = if (tile.realNum == 1) 9 else tile.realNum - 1
                            fromUtilsTile(Tile.get(tile.type, previousNumber))
                        }
                    }
                }
            }

    fun sameKind(other: MahjongTile): Boolean = if (isFlower || other.isFlower) this == other else scoringTile == other.scoringTile

    fun itemModelPath(): String = "mahjong_tile/${name.lowercase()}"

    companion object {
        val normalWall =
            buildList {
                entries.forEach { tile ->
                    repeat(4) { add(tile) }
                    if (tile == RED_DRAGON) return@buildList
                }
            }

        val redFive3Wall =
            normalWall.toMutableList().apply {
                remove(M5)
                remove(P5)
                remove(S5)
                add(M5_RED)
                add(P5_RED)
                add(S5_RED)
            }

        val redFive4Wall =
            redFive3Wall.toMutableList().apply {
                remove(P5)
                add(P5_RED)
            }

        private val manzuByNumber = listOf(M5_RED, M1, M2, M3, M4, M5, M6, M7, M8, M9)
        private val pinzuByNumber = listOf(P5_RED, P1, P2, P3, P4, P5, P6, P7, P8, P9)
        private val souzuByNumber = listOf(S5_RED, S1, S2, S3, S4, S5, S6, S7, S8, S9)
        private val honorsByNumber = listOf(EAST, SOUTH, WEST, NORTH, WHITE_DRAGON, GREEN_DRAGON, RED_DRAGON)

        fun fromUtilsTile(tile: Tile): MahjongTile =
            when (tile.type) {
                TileType.M -> manzuByNumber.getOrElse(tile.num) { UNKNOWN }
                TileType.P -> pinzuByNumber.getOrElse(tile.num) { UNKNOWN }
                TileType.S -> souzuByNumber.getOrElse(tile.num) { UNKNOWN }
                TileType.Z -> honorsByNumber.getOrElse(tile.realNum - 1) { UNKNOWN }
            }
    }
}

enum class Wind(
    val utilsWind: UtilsWind,
) {
    EAST(UtilsWind.East),
    SOUTH(UtilsWind.South),
    WEST(UtilsWind.West),
    NORTH(UtilsWind.North),
}

enum class ClaimTarget {
    SELF,
    RIGHT,
    LEFT,
    ACROSS,
}

enum class MeldType {
    CHII,
    PON,
    MINKAN,
    ANKAN,
    KAKAN,
}

enum class DoubleYakuman {
    DAISUSHI,
    SUANKO_TANKI,
    JUNSEI_CHURENPOHTO,
    KOKUSHIMUSO_JUSANMENMACHI,
}

enum class ScoringStick(
    val point: Int,
) {
    P100(100),
    P1000(1000),
    P5000(5000),
    P10000(10000),
}

enum class ExhaustiveDraw {
    NORMAL,
    KYUUSHU_KYUUHAI,
    SUUFON_RENDA,
    SUUCHA_RIICHI,
    SUUKAIKAN,
}

data class MahjongRound(
    var wind: Wind = Wind.EAST,
    var round: Int = 0,
    var honba: Int = 0,
) {
    private var spentRounds = 0

    fun nextRound() {
        val nextRound = (round + 1) % 4
        honba = 0
        if (nextRound == 0) {
            val nextWindNum = (wind.ordinal + 1) % 4
            wind = Wind.entries[nextWindNum]
        }
        round = nextRound
        spentRounds++
    }

    fun isAllLast(rule: MahjongRule): Boolean = (spentRounds + 1) >= rule.length.rounds

    fun isExtension(rule: MahjongRule): Boolean = spentRounds >= rule.length.rounds
}

data class MahjongRule(
    var length: GameLength = GameLength.TWO_WIND,
    var thinkingTime: ThinkingTime = ThinkingTime.NORMAL,
    var startingPoints: Int = 25000,
    var minPointsToWin: Int = 30000,
    var minimumHan: MinimumHan = MinimumHan.ONE,
    var spectate: Boolean = true,
    var redFive: RedFive = RedFive.THREE,
    var openTanyao: Boolean = true,
    var localYaku: Boolean = false,
    var ronMode: RonMode = RonMode.MULTI_RON,
    var riichiProfile: RiichiProfile = RiichiProfile.MAJSOUL,
) {
    enum class GameLength(
        private val startingWind: Wind,
        val rounds: Int,
        val finalRound: Pair<Wind, Int>,
    ) {
        ONE_GAME(Wind.EAST, 1, Wind.EAST to 3),
        EAST(Wind.EAST, 4, Wind.SOUTH to 3),
        SOUTH(Wind.SOUTH, 4, Wind.WEST to 3),
        TWO_WIND(Wind.EAST, 8, Wind.WEST to 3),
        FOUR_WIND(Wind.EAST, 16, Wind.NORTH to 3),
        ;

        fun getStartingRound(): MahjongRound = MahjongRound(wind = startingWind)
    }

    enum class MinimumHan(
        val han: Int,
    ) {
        ONE(1),
        TWO(2),
        FOUR(4),
        YAKUMAN(13),
    }

    enum class ThinkingTime(
        val base: Int,
        val extra: Int,
    ) {
        VERY_SHORT(3, 5),
        SHORT(5, 10),
        NORMAL(5, 20),
        LONG(60, 0),
        VERY_LONG(300, 0),
    }

    enum class RedFive(
        val quantity: Int,
    ) {
        NONE(0),
        THREE(3),
        FOUR(4),
    }

    enum class RonMode {
        HEAD_BUMP,
        MULTI_RON,
    }

    enum class RiichiProfile {
        MAJSOUL,
        EARLY_KAN_DORA,

        /** Legacy persisted name; this was never a complete tournament ruleset. */
        @Deprecated("Use EARLY_KAN_DORA; this value only changes kan-dora timing")
        TOURNAMENT,
    }
}

data class TileInstance(
    val id: UUID = UUID.randomUUID(),
    var mahjongTile: MahjongTile,
) {
    val code: Int
        get() = mahjongTile.code

    val baseTile: MahjongTile
        get() = mahjongTile.baseTile

    val utilsTile: Tile
        get() = mahjongTile.utilsTile

    val scoringTile: Tile
        get() = mahjongTile.scoringTile
}

fun List<TileInstance>.toMahjongTileList(): List<MahjongTile> = map { it.mahjongTile }

data class Fuuro(
    val type: MeldType,
    val tileInstances: List<TileInstance>,
    val claimTarget: ClaimTarget,
    val claimTile: TileInstance,
) {
    val isOpen: Boolean
        get() = type != MeldType.ANKAN

    val isKan: Boolean
        get() = type == MeldType.MINKAN || type == MeldType.ANKAN || type == MeldType.KAKAN

    val isPon: Boolean
        get() = type == MeldType.PON

    val utilsFuro: Furo
        get() {
            val baseTiles = tileInstances.map { it.scoringTile }.sorted()
            return when (type) {
                MeldType.CHII -> Furo(baseTiles)
                MeldType.PON -> Furo(baseTiles)
                MeldType.MINKAN, MeldType.KAKAN -> Furo(baseTiles)
                MeldType.ANKAN -> Furo(baseTiles, ankan = true)
            }
        }
}

data class GeneralSituation(
    val isFirstRound: Boolean,
    val isHoutei: Boolean,
    val bakaze: Wind,
    val doraIndicators: List<MahjongTile>,
    val uraDoraIndicators: List<MahjongTile>,
)

data class PersonalSituation(
    val isTsumo: Boolean,
    val isIppatsu: Boolean,
    val isRiichi: Boolean,
    val isDoubleRiichi: Boolean,
    val isChankan: Boolean,
    val isRinshanKaihoh: Boolean,
    val jikaze: Wind,
)

enum class SettlementPaymentType {
    RON,
    TSUMO,
    PAO,
    HONBA,
    RIICHI_POOL,
}

data class SettlementPayment
    @JvmOverloads
    constructor(
        val payerUuid: String,
        val amount: Int,
        val type: SettlementPaymentType,
        val note: String = "",
    )

data class YakuSettlement
    @JvmOverloads
    constructor(
        val displayName: String,
        val uuid: String,
        val yakuList: List<String>,
        val yakumanList: List<String>,
        val doubleYakumanList: List<DoubleYakuman>,
        val nagashiMangan: Boolean = false,
        val redFiveCount: Int = 0,
        val riichi: Boolean,
        val winningTile: MahjongTile,
        val hands: List<MahjongTile>,
        val fuuroList: List<Pair<Boolean, List<MahjongTile>>>,
        val doraIndicators: List<MahjongTile>,
        val uraDoraIndicators: List<MahjongTile>,
        val fu: Int,
        val han: Int,
        val score: Int,
        val paymentBreakdown: List<SettlementPayment> = emptyList(),
    ) {
        companion object {
            val NO_YAKU =
                YakuSettlement(
                    displayName = "",
                    uuid = "",
                    yakuList = emptyList(),
                    yakumanList = emptyList(),
                    doubleYakumanList = emptyList(),
                    riichi = false,
                    winningTile = MahjongTile.UNKNOWN,
                    hands = emptyList(),
                    fuuroList = emptyList(),
                    doraIndicators = emptyList(),
                    uraDoraIndicators = emptyList(),
                    fu = 0,
                    han = 0,
                    score = 0,
                )

            fun nagashiMangan(
                displayName: String,
                uuid: String,
                doraIndicators: List<MahjongTile>,
                uraDoraIndicators: List<MahjongTile>,
                isDealer: Boolean,
            ): YakuSettlement =
                YakuSettlement(
                    displayName = displayName,
                    uuid = uuid,
                    yakuList = emptyList(),
                    yakumanList = emptyList(),
                    doubleYakumanList = emptyList(),
                    nagashiMangan = true,
                    riichi = false,
                    winningTile = MahjongTile.UNKNOWN,
                    hands = emptyList(),
                    fuuroList = emptyList(),
                    doraIndicators = doraIndicators,
                    uraDoraIndicators = uraDoraIndicators,
                    fu = 30,
                    han = 5,
                    score = if (isDealer) 12000 else 8000,
                )
        }
    }

data class ScoreItem(
    val displayName: String,
    val stringUUID: String,
    val scoreOrigin: Int,
    val scoreChange: Int,
)

data class RankedScoreItem(
    val scoreItem: ScoreItem,
    val scoreTotal: Int,
    val scoreChangeText: String,
    val rankFloatText: String,
)

data class ScoreSettlement(
    val title: String,
    val scoreList: List<ScoreItem>,
) {
    val rankedScoreList: List<RankedScoreItem> =
        buildList {
            val origin = scoreList.sortedWith(compareBy<ScoreItem> { it.scoreOrigin }.thenBy { it.stringUUID }).reversed()
            val after = scoreList.sortedWith(compareBy<ScoreItem> { it.scoreOrigin + it.scoreChange }.thenBy { it.stringUUID }).reversed()
            after.forEachIndexed { index, playerScore ->
                val rankOrigin = origin.indexOf(playerScore)
                val rankFloat =
                    when {
                        index < rankOrigin -> "UP"
                        index > rankOrigin -> "DOWN"
                        else -> ""
                    }
                val scoreChangeString =
                    when {
                        playerScore.scoreChange == 0 -> ""
                        playerScore.scoreChange > 0 -> "+${playerScore.scoreChange}"
                        else -> playerScore.scoreChange.toString()
                    }
                add(
                    RankedScoreItem(
                        scoreItem = playerScore,
                        scoreTotal = playerScore.scoreOrigin + playerScore.scoreChange,
                        scoreChangeText = scoreChangeString,
                        rankFloatText = rankFloat,
                    ),
                )
            }
        }
}
