package top.ellan.mahjong.riichi

import mahjongutils.CalcContext
import mahjongutils.hora.HoraOptions
import mahjongutils.hora.hora
import mahjongutils.models.Tatsu
import mahjongutils.models.Tile
import mahjongutils.models.isYaochu
import mahjongutils.shanten.ShantenWithGot
import mahjongutils.shanten.ShantenWithoutGot
import mahjongutils.shanten.UnionShantenResult
import mahjongutils.shanten.furoChanceShanten
import mahjongutils.shanten.shanten
import mahjongutils.yaku.Yaku
import mahjongutils.yaku.Yakus
import top.ellan.mahjong.error.MahjongBusinessException
import top.ellan.mahjong.error.MahjongErrorCode
import top.ellan.mahjong.error.MahjongInfrastructureException
import top.ellan.mahjong.riichi.model.ClaimTarget
import top.ellan.mahjong.riichi.model.DoubleYakuman
import top.ellan.mahjong.riichi.model.Fuuro
import top.ellan.mahjong.riichi.model.GeneralSituation
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.MeldType
import top.ellan.mahjong.riichi.model.PersonalSituation
import top.ellan.mahjong.riichi.model.ScoringStick
import top.ellan.mahjong.riichi.model.TileInstance
import top.ellan.mahjong.riichi.model.Wind
import top.ellan.mahjong.riichi.model.YakuSettlement
import top.ellan.mahjong.riichi.model.toMahjongTileList
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.EnumMap
import java.util.logging.Level
import java.util.logging.Logger

private val CANONICAL_YAKU_NAMES: Map<String, String> =
    mapOf(
        "Tsumo" to "TSUMO",
        "Pinhu" to "PINFU",
        "Tanyao" to "TANYAO",
        "Ipe" to "IIPEIKOU",
        "SelfWind" to "SELF_WIND",
        "RoundWind" to "ROUND_WIND",
        "Haku" to "HAKU",
        "Hatsu" to "HATSU",
        "Chun" to "CHUN",
        "Sanshoku" to "SANSHOKU",
        "Ittsu" to "ITTSU",
        "Chanta" to "CHANTA",
        "Chitoi" to "CHITOITSU",
        "Toitoi" to "TOITOI",
        "Sananko" to "SANANKOU",
        "Honroto" to "HONROUTOU",
        "Sandoko" to "SANDOKOU",
        "Sankantsu" to "SANKANTSU",
        "Shosangen" to "SHOUSANGEN",
        "Honitsu" to "HONITSU",
        "Junchan" to "JUNCHAN",
        "Ryanpe" to "RYANPEIKOU",
        "Chinitsu" to "CHINITSU",
        "Kokushi" to "KOKUSHIMUSO",
        "Suanko" to "SUANKO",
        "Daisangen" to "DAISANGEN",
        "Tsuiso" to "TSUUIISOU",
        "Shousushi" to "SHOUSUUSHII",
        "Lyuiso" to "RYUUIISOU",
        "Chinroto" to "CHINROUTOU",
        "Sukantsu" to "SUUKANTSU",
        "Churen" to "CHUURENPOUTOU",
        "Richi" to "REACH",
        "Ippatsu" to "IPPATSU",
        "Rinshan" to "RINSHAN_KAIHOU",
        "Chankan" to "CHANKAN",
        "Haitei" to "HAITEI",
        "Houtei" to "HOUTEI",
        "WRichi" to "DOUBLE_REACH",
        "Tenhou" to "TENHOU",
        "Chihou" to "CHIIHOU",
    )

internal fun canonicalYakuName(name: String): String = CANONICAL_YAKU_NAMES[name] ?: name.uppercase()

abstract class RiichiPlayerScoringState(
    displayName: String,
    uuid: String,
    isRealPlayer: Boolean,
) : RiichiPlayerAnalysisState(displayName, uuid, isRealPlayer) {
    fun calculateMachiAndHan(
        hands: List<MahjongTile> = currentHandsMahjongTiles(),
        fuuroList: List<Fuuro> = this.fuuroList,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Map<MahjongTile, Int> {
        val allMachi = calculateMachi(hands, fuuroList)
        return allMachi.associateWith { machiTile ->
            val yakuSettlement =
                calculateYakuSettlement(
                    winningTile = machiTile,
                    isWinningTileInHands = false,
                    hands = hands,
                    fuuroList = fuuroList,
                    rule = rule,
                    generalSituation = generalSituation,
                    personalSituation = personalSituation,
                    doraIndicators = emptyList(),
                    uraDoraIndicators = emptyList(),
                )
            if (yakuSettlement.yakuList.isNotEmpty() || yakuSettlement.yakumanList.isNotEmpty()) -1 else yakuSettlement.han
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun isFuriten(
        tile: TileInstance,
        discards: List<TileInstance>,
    ): Boolean {
        val baseMachi = machi.mapTo(linkedSetOf()) { it.baseTile }
        if (discardedTiles.any { discardedTile -> discardedTile.mahjongTile.baseTile in baseMachi }) {
            return true
        }
        val lastOwnDiscard = discardedTiles.lastOrNull()
        if (lastOwnDiscard != null) {
            val sameTurnStartIndex = discards.indexOf(lastOwnDiscard)
            if (sameTurnStartIndex >= 0) {
                for (index in sameTurnStartIndex until discards.lastIndex) {
                    if (discards[index].mahjongTile.baseTile in baseMachi) {
                        return true
                    }
                }
            }
        }
        val riichiDiscard = riichiSengenTile
        if ((riichi || doubleRiichi) && riichiDiscard != null) {
            val riichiStartIndex = discards.indexOf(riichiDiscard)
            if (riichiStartIndex >= 0) {
                for (index in riichiStartIndex until discards.lastIndex) {
                    if (discards[index].mahjongTile.baseTile in baseMachi) {
                        return true
                    }
                }
            }
        }
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun isFuriten(
        tile: MahjongTile,
        discards: List<MahjongTile>,
        machi: List<MahjongTile> = this.machi,
    ): Boolean {
        val ownDiscardedBaseTiles = discardedTiles.mapTo(linkedSetOf()) { it.mahjongTile.baseTile }
        val baseMachi = machi.mapTo(linkedSetOf()) { it.baseTile }
        if (ownDiscardedBaseTiles.any { it in baseMachi }) return true
        if (discardedTiles.isNotEmpty()) {
            val lastDiscard = discardedTiles.last().mahjongTile.baseTile
            val sameTurnStartIndex = discards.indexOfFirst { it.baseTile == lastDiscard }
            if (sameTurnStartIndex >= 0) {
                for (index in sameTurnStartIndex until discards.lastIndex) {
                    if (discards[index].baseTile in baseMachi) return true
                }
            }
        }
        val riichiSengenTile = riichiSengenTile?.mahjongTile?.baseTile ?: return false
        if (riichi || doubleRiichi) {
            val riichiStartIndex = discards.indexOfFirst { it.baseTile == riichiSengenTile }
            if (riichiStartIndex >= 0) {
                for (index in riichiStartIndex until discards.lastIndex) {
                    if (discards[index].baseTile in baseMachi) return true
                }
            }
        }
        return false
    }

    fun isIppatsu(
        players: List<RiichiPlayerState>,
        discards: List<TileInstance>,
    ): Boolean {
        if (riichi || doubleRiichi) {
            if (ippatsuInterrupted) return false
            val riichiSengenIndex = discards.indexOf(riichiSengenTile!!)
            if (discards.lastIndex - riichiSengenIndex > 4) return false
            val someoneCalls =
                discards.slice(riichiSengenIndex..discards.lastIndex).any { tile ->
                    players.any { player -> player.fuuroList.any { fuuro -> tile in fuuro.tileInstances } }
                }
            return !someoneCalls
        }
        return false
    }

    fun isKokushimuso(tile: MahjongTile): Boolean {
        val result =
            runCatching {
                hora(
                    tiles = (currentHandsMahjongTiles() + tile).toUtilsTiles(),
                    furo = currentFuuroUtils(),
                    agari = tile.utilsTile,
                    tsumo = false,
                    options = HoraOptions.Default,
                )
            }.getOrElse { error ->
                RiichiShantenRuntime.LOGGER.log(
                    RiichiShantenRuntime.shantenFailureLogLevel(error),
                    "Kokushi check failed (hands=${hands.size}, fuuro=${fuuroList.size}, tile=$tile)",
                    error,
                )
                return false
            }
        return result.yaku.any { it.name == "Kokushi" || it.name == "KokushiThirteenWaiting" }
    }

    fun canWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile> = currentHandsMahjongTiles(),
        fuuroList: List<Fuuro> = this.fuuroList,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Boolean {
        val useMemo = hands === currentHandsMahjongTiles() && fuuroList === this.fuuroList
        if (useMemo) {
            if (!isCacheCurrent(AnalysisCache.CAN_WIN)) {
                cachedCanWinDecisions.clear()
                markCacheCurrent(AnalysisCache.CAN_WIN)
            }
            val memoKey =
                canWinMemoKey(
                    winningTile = winningTile,
                    isWinningTileInHands = isWinningTileInHands,
                    rule = rule,
                    generalSituation = generalSituation,
                    personalSituation = personalSituation,
                )
            cachedCanWinDecisions[memoKey]?.let { return it }
            val result =
                evaluateCanWin(
                    winningTile = winningTile,
                    isWinningTileInHands = isWinningTileInHands,
                    hands = hands,
                    fuuroList = fuuroList,
                    rule = rule,
                    generalSituation = generalSituation,
                    personalSituation = personalSituation,
                )
            cachedCanWinDecisions[memoKey] = result
            return result
        }
        return evaluateCanWin(
            winningTile = winningTile,
            isWinningTileInHands = isWinningTileInHands,
            hands = hands,
            fuuroList = fuuroList,
            rule = rule,
            generalSituation = generalSituation,
            personalSituation = personalSituation,
        )
    }

    protected fun evaluateCanWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Boolean {
        val yakuSettlement =
            calculateYakuSettlement(
                winningTile = winningTile,
                isWinningTileInHands = isWinningTileInHands,
                hands = hands,
                fuuroList = fuuroList,
                rule = rule,
                generalSituation = generalSituation,
                personalSituation = personalSituation,
            )
        return yakuSettlement.yakumanList.isNotEmpty() ||
            yakuSettlement.doubleYakumanList.isNotEmpty() ||
            yakuSettlement.yakuOnlyHan() >= rule.minimumHan.han
    }

    protected fun YakuSettlement.yakuOnlyHan(): Int {
        val visibleDoraHan = yakuList.count { it == "DORA" }
        val uraDoraHan = yakuList.count { it == "URADORA" }
        return (han - visibleDoraHan - uraDoraHan - redFiveCount).coerceAtLeast(0)
    }

    protected fun canWinMemoKey(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): CanWinMemoKey =
        CanWinMemoKey(
            winningTile = winningTile,
            isWinningTileInHands = isWinningTileInHands,
            rule =
                CanWinRuleSignature(
                    minimumHan = rule.minimumHan,
                    openTanyao = rule.openTanyao,
                    redFive = rule.redFive,
                    localYaku = rule.localYaku,
                ),
            general = CanWinGeneralSignature.from(generalSituation),
            personal = CanWinPersonalSignature.from(personalSituation),
        )

    protected fun canFormWinningHand(
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
    ): Boolean {
        val shantenResult =
            analyzeShanten(
                hands = hands,
                fuuroList = fuuroList,
                bestShantenOnly = true,
            ) ?: return false
        return shantenResult.shantenInfo.shantenNum == -1
    }

    protected fun calculateYakuSettlement(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
        doraIndicators: List<MahjongTile> = listOf(),
        uraDoraIndicators: List<MahjongTile> = listOf(),
    ): YakuSettlement {
        val fullHands = hands.toMutableList().also { if (!isWinningTileInHands) it += winningTile }
        if (!canFormWinningHand(fullHands, fuuroList)) {
            return YakuSettlement.NO_YAKU
        }

        val horaOptions = toHoraOptions(rule)
        val yakus = Yakus(horaOptions)
        val extraYaku = buildExtraYaku(yakus, generalSituation, personalSituation)
        val doraCount = countDora(fullHands, fuuroList, generalSituation.doraIndicators)
        val uraDoraCount =
            if (personalSituation.isRiichi || personalSituation.isDoubleRiichi) {
                countDora(fullHands, fuuroList, generalSituation.uraDoraIndicators)
            } else {
                0
            }
        val redFiveCount =
            if (rule.redFive == MahjongRule.RedFive.NONE) {
                0
            } else {
                fullHands.count { it.isRed } + fuuroList.sumOf { fuuro -> fuuro.tileInstances.count { it.mahjongTile.isRed } }
            }

        val hora =
            runCatching {
                hora(
                    tiles = fullHands.toUtilsTiles(),
                    furo = toUtilsFuroList(fuuroList),
                    agari = winningTile.utilsTile,
                    tsumo = personalSituation.isTsumo,
                    dora = doraCount + uraDoraCount + redFiveCount,
                    selfWind = personalSituation.jikaze.utilsWind,
                    roundWind = generalSituation.bakaze.utilsWind,
                    extraYaku = extraYaku,
                    options = horaOptions,
                )
            }.getOrElse { error ->
                RiichiShantenRuntime.LOGGER.log(
                    RiichiShantenRuntime.shantenFailureLogLevel(error),
                    "Yaku settlement hora failed (hands=${fullHands.size}, fuuro=${fuuroList.size}, winningTile=$winningTile, tsumo=${personalSituation.isTsumo})",
                    error,
                )
                return YakuSettlement.NO_YAKU
            }

        val finalNormalYakuList = mutableListOf<String>()
        val finalYakumanList = mutableListOf<String>()
        val finalDoubleYakumanList = mutableListOf<DoubleYakuman>()
        hora.yaku.sortedBy { it.name }.forEach { yaku ->
            when (val doubleYakuman = toDoubleYakuman(yaku.name)) {
                null -> {
                    if (yaku.isYakuman) {
                        finalYakumanList += canonicalYakuName(yaku.name)
                    } else {
                        finalNormalYakuList += canonicalYakuName(yaku.name)
                    }
                }

                else -> {
                    finalDoubleYakumanList += doubleYakuman
                }
            }
        }
        if (finalNormalYakuList.isEmpty() && finalYakumanList.isEmpty() && finalDoubleYakumanList.isEmpty()) {
            return YakuSettlement.NO_YAKU
        }
        repeat(doraCount) {
            finalNormalYakuList += "DORA"
        }
        repeat(uraDoraCount) {
            finalNormalYakuList += "URADORA"
        }

        val fuuroListForSettlement =
            fuuroList.map { fuuro ->
                (!fuuro.isOpen && fuuro.isKan) to fuuro.tileInstances.toMahjongTileList()
            }
        val isParent = personalSituation.jikaze == Wind.EAST
        val score =
            if (personalSituation.isTsumo) {
                if (isParent) {
                    hora.parentPoint.tsumoTotal.toInt()
                } else {
                    hora.childPoint.tsumoTotal.toInt()
                }
            } else {
                if (isParent) {
                    hora.parentPoint.ron.toInt()
                } else {
                    hora.childPoint.ron.toInt()
                }
            }

        return YakuSettlement(
            displayName = displayName,
            uuid = uuid,
            yakuList = finalNormalYakuList,
            yakumanList = finalYakumanList,
            doubleYakumanList = finalDoubleYakumanList,
            redFiveCount = redFiveCount,
            riichi = riichi || doubleRiichi,
            winningTile = winningTile,
            hands = currentHandsMahjongTiles(),
            fuuroList = fuuroListForSettlement,
            doraIndicators = doraIndicators,
            uraDoraIndicators = uraDoraIndicators,
            fu = if (finalYakumanList.isEmpty() && finalDoubleYakumanList.isEmpty() && finalNormalYakuList.isEmpty()) 0 else hora.hu,
            han = hora.han,
            score = score,
        )
    }

    fun calcYakuSettlementForWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
        doraIndicators: List<MahjongTile>,
        uraDoraIndicators: List<MahjongTile>,
    ): YakuSettlement =
        calculateYakuSettlement(
            winningTile = winningTile,
            isWinningTileInHands = isWinningTileInHands,
            hands = currentHandsMahjongTiles(),
            fuuroList = fuuroList,
            rule = rule,
            generalSituation = generalSituation,
            personalSituation = personalSituation,
            doraIndicators = doraIndicators,
            uraDoraIndicators = uraDoraIndicators,
        )
}
