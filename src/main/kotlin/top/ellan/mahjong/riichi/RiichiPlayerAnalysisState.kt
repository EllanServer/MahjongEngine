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

abstract class RiichiPlayerAnalysisState(
    val displayName: String,
    val uuid: String,
    val isRealPlayer: Boolean = true,
) {
    val hands: MutableList<TileInstance> = mutableListOf()
    var lastDrawnTile: TileInstance? = null
    var autoArrangeHands: Boolean = true
    val fuuroList: MutableList<Fuuro> = mutableListOf()
    var riichiSengenTile: TileInstance? = null
    val discardedTiles: MutableList<TileInstance> = mutableListOf()
    val discardedTilesForDisplay: MutableList<TileInstance> = mutableListOf()
    var ready: Boolean = false
    var riichi: Boolean = false
    var doubleRiichi: Boolean = false
    var temporaryFuriten: Boolean = false
        private set
    var riichiFuriten: Boolean = false
        private set
    protected var ippatsuInterrupted: Boolean = false
    val sticks: MutableList<ScoringStick> = mutableListOf()
    var points: Int = 0
    var basicThinkingTime: Int = 0
    var extraThinkingTime: Int = 0
    protected var analysisStateVersion: Long = 0
    protected val cacheVersions: MutableMap<AnalysisCache, Long> = EnumMap(AnalysisCache::class.java)
    protected var cachedTilePairsForRiichi: List<Pair<MahjongTile, List<MahjongTile>>> = emptyList()
    protected var cachedMachi: List<MahjongTile> = emptyList()
    protected var cachedCurrentShantenResult: UnionShantenResult? = null
    protected var cachedDiscardSuggestions: List<RiichiDiscardSuggestion> = emptyList()
    protected val cachedFuroReactions: MutableMap<Pair<MahjongTile, Boolean>, FuroReactionAnalysis?> = mutableMapOf()
    protected var cachedHandsMahjongTiles: List<MahjongTile> = emptyList()
    protected var cachedHandsUtilsTiles: List<Tile> = emptyList()
    protected var cachedFuuroUtils: List<mahjongutils.models.Furo> = emptyList()
    protected val cachedCanWinDecisions: MutableMap<CanWinMemoKey, Boolean> = mutableMapOf()

    val riichiStickAmount: Int
        get() = sticks.count { it == ScoringStick.P1000 }

    protected fun isCacheCurrent(cache: AnalysisCache): Boolean = cacheVersions[cache] == analysisStateVersion

    protected fun markCacheCurrent(cache: AnalysisCache) {
        cacheVersions[cache] = analysisStateVersion
    }

    val isMenzenchin: Boolean
        get() = fuuroList.isEmpty() || fuuroList.all { it.isKan && !it.isOpen }

    val numbersOfYaochuuhaiTypes: Int
        get() {
            val seen = hashSetOf<Int>()
            var count = 0
            for (tile in hands) {
                val scoringTile = tile.scoringTile
                if (!scoringTile.isYaochu) {
                    continue
                }
                if (seen.add(scoringTile.code)) {
                    count++
                }
            }
            return count
        }

    protected fun findChiiTileVariant(
        baseTile: MahjongTile,
        exclude: MahjongTile? = null,
    ): MahjongTile? = hands.firstOrNull { it.mahjongTile.baseTile == baseTile && it.mahjongTile != exclude }?.mahjongTile

    protected val machi: List<MahjongTile>
        get() {
            if (!isCacheCurrent(AnalysisCache.MACHI)) {
                cachedMachi = currentShantenWithoutGot()
                    ?.takeIf { it.shantenNum == 0 }
                    ?.advance
                    ?.map(MahjongTile::fromUtilsTile)
                    ?.distinct()
                    ?: emptyList()
                markCacheCurrent(AnalysisCache.MACHI)
            }
            return cachedMachi
        }

    protected fun calculateMachi(
        hands: List<MahjongTile> = currentHandsMahjongTiles(),
        fuuroList: List<Fuuro> = this.fuuroList,
    ): List<MahjongTile> =
        shantenWithoutGot(hands, fuuroList)
            ?.takeIf { it.shantenNum == 0 }
            ?.advance
            ?.map(MahjongTile::fromUtilsTile)
            ?.distinct()
            ?: emptyList()

    fun drawTile(tile: TileInstance) {
        hands += tile
        temporaryFuriten = false
        lastDrawnTile = tile
        invalidateHandAnalysis()
    }

    fun markTemporaryFuriten() {
        if (riichi || doubleRiichi) {
            riichiFuriten = true
        } else {
            temporaryFuriten = true
        }
    }

    val missedRonFuriten: Boolean
        get() = temporaryFuriten || riichiFuriten

    internal fun markIppatsuInterrupted() {
        ippatsuInterrupted = true
    }

    fun declareRiichi(
        riichiSengenTile: TileInstance,
        isFirstRound: Boolean,
    ) {
        this.riichiSengenTile = riichiSengenTile
        ippatsuInterrupted = false
        if (isFirstRound) doubleRiichi = true else riichi = true
        invalidateHandAnalysis()
    }

    fun discardTile(tile: TileInstance): TileInstance? = hands.find { it.id == tile.id }?.also { discardTileInstance(it) }

    fun discardTile(tile: MahjongTile): TileInstance? = hands.findLast { it.mahjongTile == tile }?.also { discardTileInstance(it) }

    fun resetRoundState() {
        hands.clear()
        fuuroList.clear()
        discardedTiles.clear()
        discardedTilesForDisplay.clear()
        lastDrawnTile = null
        riichiSengenTile = null
        riichi = false
        doubleRiichi = false
        temporaryFuriten = false
        riichiFuriten = false
        ippatsuInterrupted = false
        invalidateHandAnalysis()
    }

    protected fun invalidateHandAnalysis() {
        analysisStateVersion++
    }

    protected fun currentShantenResult(): UnionShantenResult? {
        if (!isCacheCurrent(AnalysisCache.CURRENT_SHANTEN)) {
            cachedCurrentShantenResult = analyzeShanten()
            markCacheCurrent(AnalysisCache.CURRENT_SHANTEN)
        }
        return cachedCurrentShantenResult
    }

    protected fun currentShantenWithGot(): ShantenWithGot? = currentShantenResult()?.shantenInfo as? ShantenWithGot

    protected fun currentShantenWithoutGot(): ShantenWithoutGot? = currentShantenResult()?.shantenInfo as? ShantenWithoutGot

    protected fun analyzeShanten(
        hands: List<MahjongTile> = currentHandsMahjongTiles(),
        fuuroList: List<Fuuro> = this.fuuroList,
        bestShantenOnly: Boolean = true,
    ): UnionShantenResult? {
        val tiles = toUtilsTilesCached(hands)
        val furo = toUtilsFuroList(fuuroList)
        val strategy = RiichiShantenRuntime.shantenStrategy
        val result =
            runCatching {
                strategy.evaluate(tiles, furo, bestShantenOnly)
            }
        if (result.isSuccess) {
            return result.getOrNull()
        }
        val error = result.exceptionOrNull() ?: return null
        if (RiichiShantenRuntime.isNoSuchElementFailure(error)) {
            for (fallback in RiichiShantenRuntime.fallbackShantenStrategies(strategy, RiichiShantenRuntime.shantenCalculator)) {
                val fallbackResult =
                    runCatching {
                        fallback.evaluate(tiles, furo, bestShantenOnly)
                    }
                if (fallbackResult.isSuccess) {
                    if (strategy.name != fallback.name) {
                        RiichiShantenRuntime.LOGGER.log(Level.INFO, "Shanten strategy promoted: ${strategy.name} -> ${fallback.name}")
                        RiichiShantenRuntime.shantenStrategy = fallback
                    }
                    return fallbackResult.getOrNull()
                }
                fallbackResult.exceptionOrNull()?.let { fallbackError ->
                    RiichiShantenRuntime.LOGGER.log(
                        RiichiShantenRuntime.shantenFailureLogLevel(fallbackError),
                        "Shanten fallback failed strategy=${fallback.name} hands=${hands.size} fuuro=${fuuroList.size} bestOnly=$bestShantenOnly",
                        fallbackError,
                    )
                }
            }
        }
        val handled =
            if (error is IllegalArgumentException) {
                MahjongBusinessException(
                    MahjongErrorCode.SHANTEN_ANALYSIS_FAILED,
                    MahjongErrorCode.SHANTEN_ANALYSIS_FAILED.publicMessage(),
                    error,
                )
            } else {
                MahjongInfrastructureException(
                    MahjongErrorCode.SHANTEN_ANALYSIS_FAILED,
                    MahjongErrorCode.SHANTEN_ANALYSIS_FAILED.publicMessage(),
                    error,
                )
            }
        RiichiShantenRuntime.LOGGER.log(
            handled.logLevel(),
            "${handled.code().name} strategy=${strategy.name} hands=${hands.size} fuuro=${fuuroList.size} bestOnly=$bestShantenOnly",
            error,
        )
        return null
    }

    protected fun shantenWithoutGot(
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
    ): ShantenWithoutGot? = analyzeShanten(hands, fuuroList)?.shantenInfo as? ShantenWithoutGot

    protected fun discardTileInstance(tile: TileInstance) {
        hands -= tile
        if (lastDrawnTile?.id == tile.id) {
            lastDrawnTile = null
        }
        discardedTiles += tile
        discardedTilesForDisplay += tile
        invalidateHandAnalysis()
    }

    protected fun currentHandsMahjongTiles(): List<MahjongTile> {
        if (!isCacheCurrent(AnalysisCache.HANDS_MAHJONG_TILES)) {
            cachedHandsMahjongTiles = hands.toMahjongTileList()
            markCacheCurrent(AnalysisCache.HANDS_MAHJONG_TILES)
        }
        return cachedHandsMahjongTiles
    }

    protected fun currentHandsUtilsTiles(): List<Tile> {
        if (!isCacheCurrent(AnalysisCache.HANDS_UTILS_TILES)) {
            cachedHandsUtilsTiles = currentHandsMahjongTiles().toUtilsTiles()
            markCacheCurrent(AnalysisCache.HANDS_UTILS_TILES)
        }
        return cachedHandsUtilsTiles
    }

    protected fun currentFuuroUtils(): List<mahjongutils.models.Furo> {
        if (!isCacheCurrent(AnalysisCache.FUURO_UTILS)) {
            cachedFuuroUtils = fuuroList.map { it.utilsFuro }
            markCacheCurrent(AnalysisCache.FUURO_UTILS)
        }
        return cachedFuuroUtils
    }

    protected fun toUtilsFuroList(fuuroList: List<Fuuro>): List<mahjongutils.models.Furo> =
        if (fuuroList === this.fuuroList) {
            currentFuuroUtils()
        } else {
            fuuroList.map { it.utilsFuro }
        }

    protected fun toUtilsTilesCached(hands: List<MahjongTile>): List<Tile> =
        if (hands === cachedHandsMahjongTiles && isCacheCurrent(AnalysisCache.HANDS_MAHJONG_TILES)) {
            currentHandsUtilsTiles()
        } else {
            hands.toUtilsTiles()
        }

    protected fun List<MahjongTile>.toUtilsTiles(): List<Tile> = map { it.utilsTile }

    protected fun analyzeFuroReaction(
        tile: TileInstance,
        allowChii: Boolean,
    ): FuroReactionAnalysis? {
        if (!isCacheCurrent(AnalysisCache.FURO_REACTION)) {
            cachedFuroReactions.clear()
            markCacheCurrent(AnalysisCache.FURO_REACTION)
        }
        val cacheKey = tile.mahjongTile.baseTile to allowChii
        return cachedFuroReactions.getOrPut(cacheKey) {
            val result =
                runCatching {
                    furoChanceShanten(
                        tiles = currentHandsUtilsTiles(),
                        chanceTile = tile.scoringTile,
                        allowChi = allowChii,
                        bestShantenOnly = false,
                        allowKuikae = false,
                    )
                }.getOrElse { error ->
                    RiichiShantenRuntime.LOGGER.log(
                        RiichiShantenRuntime.shantenFailureLogLevel(error),
                        "Furo reaction analysis failed (hands=${hands.size}, fuuro=${fuuroList.size}, tile=${tile.mahjongTile}, allowChii=$allowChii)",
                        error,
                    )
                    return@getOrPut null
                }
            val shanten = result.shantenInfo
            val chiChoices =
                if (allowChii) {
                    shanten.chi.entries
                        .mapNotNull { entry ->
                            toChiiChoice(entry.key, entry.value)
                        }.distinctBy { it.first }
                        .sortedWith { left, right ->
                            val evaluationCompare = ActionEvaluation.comparator.compare(right.second, left.second)
                            if (evaluationCompare != 0) {
                                evaluationCompare
                            } else {
                                compareValuesBy(left, right, { it.first.first.sortOrder }, { it.first.second.sortOrder })
                            }
                        }
                } else {
                    emptyList()
                }
            FuroReactionAnalysis(
                pass = shanten.pass?.let(::evaluateAction) ?: ActionEvaluation.worst(),
                chiChoices = chiChoices,
                pon = shanten.pon?.let(::evaluateAction),
                minkan = shanten.minkan?.let(::evaluateAction),
            )
        }
    }

    protected fun toChiiChoice(
        tatsu: Tatsu,
        shanten: ShantenWithGot,
    ): Pair<Pair<MahjongTile, MahjongTile>, ActionEvaluation>? {
        val firstBase = MahjongTile.fromUtilsTile(tatsu.first)
        val secondBase = MahjongTile.fromUtilsTile(tatsu.second)
        val firstTile = findChiiTileVariant(firstBase) ?: return null
        val secondTile = findChiiTileVariant(secondBase, exclude = firstTile) ?: return null
        return (firstTile to secondTile) to evaluateAction(shanten)
    }

    protected fun evaluateAction(shanten: ShantenWithoutGot): ActionEvaluation =
        ActionEvaluation(
            shantenNum = shanten.shantenNum,
            goodShapeAdvanceNum = shanten.goodShapeAdvanceNum ?: -1,
            advanceNum = shanten.advanceNum,
            goodShapeImprovementNum = shanten.goodShapeImprovementNum ?: -1,
            improvementNum = shanten.improvementNum ?: -1,
        )

    protected fun evaluateAction(shanten: ShantenWithGot): ActionEvaluation {
        val bestDiscard =
            shanten.discardToAdvance.values
                .map(::evaluateAction)
                .maxWithOrNull(ActionEvaluation.comparator)
        return if (bestDiscard == null) {
            ActionEvaluation(
                shantenNum = shanten.shantenNum,
                goodShapeAdvanceNum = -1,
                advanceNum = 0,
                goodShapeImprovementNum = -1,
                improvementNum = -1,
            )
        } else {
            bestDiscard
        }
    }

    protected fun suggestedReactionResponse(analysis: FuroReactionAnalysis): ReactionResponse {
        var best = ReactionChoice(ReactionResponse(ReactionType.SKIP, null), analysis.pass)
        analysis.pon?.let {
            val candidate = ReactionChoice(ReactionResponse(ReactionType.PON, null), it)
            if (ReactionChoice.comparator.compare(candidate, best) > 0) {
                best = candidate
            }
        }
        analysis.minkan?.let {
            val candidate = ReactionChoice(ReactionResponse(ReactionType.MINKAN, null), it)
            if (ReactionChoice.comparator.compare(candidate, best) > 0) {
                best = candidate
            }
        }
        analysis.chiChoices.forEach { (pair, evaluation) ->
            val candidate = ReactionChoice(ReactionResponse(ReactionType.CHII, pair), evaluation)
            if (ReactionChoice.comparator.compare(candidate, best) > 0) {
                best = candidate
            }
        }
        return best.response
    }

    protected fun countDora(
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        indicators: List<MahjongTile>,
    ): Int {
        if (indicators.isEmpty()) {
            return 0
        }
        val doraMultipliers = indicators.groupingBy { it.nextTile.baseTile }.eachCount()
        val handCount = hands.sumOf { doraMultipliers[it.baseTile] ?: 0 }
        val fuuroCount =
            fuuroList.sumOf { fuuro ->
                fuuro.tileInstances.sumOf { tile -> doraMultipliers[tile.mahjongTile.baseTile] ?: 0 }
            }
        return handCount + fuuroCount
    }

    protected fun buildExtraYaku(
        yakus: Yakus,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Set<Yaku> =
        buildSet {
            when {
                personalSituation.isDoubleRiichi -> add(yakus.WRichi)
                personalSituation.isRiichi -> add(yakus.Richi)
            }
            if (personalSituation.isIppatsu) {
                add(yakus.Ippatsu)
            }
            if (personalSituation.isRinshanKaihoh) {
                add(yakus.Rinshan)
            }
            if (personalSituation.isChankan) {
                add(yakus.Chankan)
            }
            if (generalSituation.isHoutei && !personalSituation.isRinshanKaihoh) {
                add(if (personalSituation.isTsumo) yakus.Haitei else yakus.Houtei)
            }
            if (generalSituation.isFirstRound && personalSituation.isTsumo) {
                add(if (personalSituation.jikaze == Wind.EAST) yakus.Tenhou else yakus.Chihou)
            }
        }

    protected fun toHoraOptions(rule: MahjongRule): HoraOptions =
        HoraOptions(
            aotenjou = false,
            allowKuitan = rule.openTanyao,
            hasRenpuuJyantouHu = true,
            hasKiriageMangan = false,
            hasKazoeYakuman = true,
            hasMultipleYakuman = true,
            hasComplexYakuman = true,
        )

    protected fun toDoubleYakuman(name: String): DoubleYakuman? =
        when (name) {
            "Daisushi" -> DoubleYakuman.DAISUSHI
            "SuankoTanki" -> DoubleYakuman.SUANKO_TANKI
            "ChurenNineWaiting" -> DoubleYakuman.JUNSEI_CHURENPOHTO
            "KokushiThirteenWaiting" -> DoubleYakuman.KOKUSHIMUSO_JUSANMENMACHI
            else -> null
        }

    protected data class CanWinRuleSignature(
        val minimumHan: MahjongRule.MinimumHan,
        val openTanyao: Boolean,
        val redFive: MahjongRule.RedFive,
        val localYaku: Boolean,
    )

    protected data class CanWinGeneralSignature(
        val isFirstRound: Boolean,
        val isHoutei: Boolean,
        val bakaze: Wind,
        val doraIndicators: List<MahjongTile>,
        val uraDoraIndicators: List<MahjongTile>,
    ) {
        companion object {
            fun from(generalSituation: GeneralSituation): CanWinGeneralSignature =
                CanWinGeneralSignature(
                    isFirstRound = generalSituation.isFirstRound,
                    isHoutei = generalSituation.isHoutei,
                    bakaze = generalSituation.bakaze,
                    doraIndicators = generalSituation.doraIndicators.toList(),
                    uraDoraIndicators = generalSituation.uraDoraIndicators.toList(),
                )
        }
    }

    protected data class CanWinPersonalSignature(
        val isTsumo: Boolean,
        val isIppatsu: Boolean,
        val isRiichi: Boolean,
        val isDoubleRiichi: Boolean,
        val isChankan: Boolean,
        val isRinshanKaihoh: Boolean,
        val jikaze: Wind,
    ) {
        companion object {
            fun from(personalSituation: PersonalSituation): CanWinPersonalSignature =
                CanWinPersonalSignature(
                    isTsumo = personalSituation.isTsumo,
                    isIppatsu = personalSituation.isIppatsu,
                    isRiichi = personalSituation.isRiichi,
                    isDoubleRiichi = personalSituation.isDoubleRiichi,
                    isChankan = personalSituation.isChankan,
                    isRinshanKaihoh = personalSituation.isRinshanKaihoh,
                    jikaze = personalSituation.jikaze,
                )
        }
    }

    protected data class CanWinMemoKey(
        val winningTile: MahjongTile,
        val isWinningTileInHands: Boolean,
        val rule: CanWinRuleSignature,
        val general: CanWinGeneralSignature,
        val personal: CanWinPersonalSignature,
    )

    protected enum class AnalysisCache {
        TILE_PAIRS_FOR_RIICHI,
        MACHI,
        CURRENT_SHANTEN,
        DISCARD_SUGGESTIONS,
        FURO_REACTION,
        HANDS_MAHJONG_TILES,
        HANDS_UTILS_TILES,
        FUURO_UTILS,
        CAN_WIN,
    }

    protected data class FuroReactionAnalysis(
        val pass: ActionEvaluation,
        val chiChoices: List<Pair<Pair<MahjongTile, MahjongTile>, ActionEvaluation>>,
        val pon: ActionEvaluation?,
        val minkan: ActionEvaluation?,
    )

    protected data class ActionEvaluation(
        val shantenNum: Int,
        val goodShapeAdvanceNum: Int,
        val advanceNum: Int,
        val goodShapeImprovementNum: Int,
        val improvementNum: Int,
    ) {
        companion object {
            fun worst(): ActionEvaluation =
                ActionEvaluation(
                    shantenNum = Int.MAX_VALUE,
                    goodShapeAdvanceNum = -1,
                    advanceNum = -1,
                    goodShapeImprovementNum = -1,
                    improvementNum = -1,
                )

            val comparator: Comparator<ActionEvaluation> =
                compareBy<ActionEvaluation> { -it.shantenNum }
                    .thenBy { it.goodShapeAdvanceNum }
                    .thenBy { it.advanceNum }
                    .thenBy { it.goodShapeImprovementNum }
                    .thenBy { it.improvementNum }
        }
    }

    protected data class ReactionChoice(
        val response: ReactionResponse,
        val evaluation: ActionEvaluation,
    ) {
        companion object {
            val comparator: Comparator<ReactionChoice> =
                Comparator { left, right ->
                    val evaluationCompare = ActionEvaluation.comparator.compare(left.evaluation, right.evaluation)
                    if (evaluationCompare != 0) {
                        evaluationCompare
                    } else {
                        reactionPriority(left.response.type).compareTo(reactionPriority(right.response.type))
                    }
                }

            private fun reactionPriority(type: ReactionType): Int =
                when (type) {
                    ReactionType.SKIP -> 0
                    ReactionType.MINKAN -> 1
                    ReactionType.CHII -> 2
                    ReactionType.PON -> 3
                    ReactionType.RON -> 4
                }
        }
    }
}
