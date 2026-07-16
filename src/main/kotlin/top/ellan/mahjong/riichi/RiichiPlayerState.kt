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

data class RiichiDiscardSuggestion(
    val tile: MahjongTile,
    val shantenNum: Int,
    val advanceTiles: List<MahjongTile>,
    val advanceCount: Int,
    val goodShapeAdvanceCount: Int,
    val improvementCount: Int,
    val goodShapeImprovementCount: Int,
)

open class RiichiPlayerState(
    displayName: String,
    uuid: String,
    isRealPlayer: Boolean = true,
) : RiichiPlayerScoringState(displayName, uuid, isRealPlayer) {
    companion object {
        internal var shantenCalculator: (List<Tile>, List<mahjongutils.models.Furo>, Boolean) -> UnionShantenResult
            get() = RiichiShantenRuntime.shantenCalculator
            set(value) {
                RiichiShantenRuntime.shantenCalculator = value
            }

        internal val activeShantenStrategyName: String
            get() = RiichiShantenRuntime.activeShantenStrategyName
    }

    val isRiichiable: Boolean
        get() = isMenzenchin && !(riichi || doubleRiichi) && tilePairsForRiichi.isNotEmpty() && points >= 1000

    fun chii(
        tile: TileInstance,
        tilePair: Pair<MahjongTile, MahjongTile>,
        claimTarget: ClaimTarget,
        target: RiichiPlayerState,
    ) {
        lastDrawnTile = null
        val tileShuntsu =
            mutableListOf(
                tile,
                hands.first { it.mahjongTile == tilePair.first },
                hands.first { it.mahjongTile == tilePair.second },
            ).also { it.sortBy { candidate -> candidate.mahjongTile.sortOrder } }
        val fuuro =
            Fuuro(
                type = MeldType.CHII,
                tileInstances = tileShuntsu,
                claimTarget = claimTarget,
                claimTile = tile,
            )
        hands -= tileShuntsu.filter { it != tile }.toSet()
        target.discardedTilesForDisplay -= tile
        fuuroList += fuuro
        invalidateHandAnalysis()
    }

    fun pon(
        tile: TileInstance,
        claimTarget: ClaimTarget,
        target: RiichiPlayerState,
    ) {
        lastDrawnTile = null
        val tilesForPon = tilesForPon(tile)
        val fuuro = Fuuro(MeldType.PON, tilesForPon, claimTarget, tile)
        hands -= tilesForPon.filter { it != tile }.toSet()
        target.discardedTilesForDisplay -= tile
        fuuroList += fuuro
        invalidateHandAnalysis()
    }

    fun minkan(
        tile: TileInstance,
        claimTarget: ClaimTarget,
        target: RiichiPlayerState,
    ) {
        lastDrawnTile = null
        val tilesForMinkan = tilesForMinkan(tile)
        val fuuro = Fuuro(MeldType.MINKAN, tilesForMinkan, claimTarget, tile)
        hands -= tilesForMinkan.filter { it != tile }.toSet()
        target.discardedTilesForDisplay -= tile
        fuuroList += fuuro
        invalidateHandAnalysis()
    }

    fun ankan(tile: TileInstance) {
        lastDrawnTile = null
        val tilesForAnkan = tilesForAnkan(tile)
        val fuuro = Fuuro(MeldType.ANKAN, tilesForAnkan, ClaimTarget.SELF, tile)
        hands -= tilesForAnkan.toSet()
        discardedTilesForDisplay -= tile
        fuuroList += fuuro
        invalidateHandAnalysis()
    }

    fun kakan(tile: TileInstance) {
        lastDrawnTile = null
        val minPon =
            fuuroList.find { it.isPon && it.tileInstances.any { existing -> existing.mahjongTile.sameKind(tile.mahjongTile) } } ?: return
        fuuroList -= minPon
        val tiles = minPon.tileInstances.toMutableList().also { it += tile }
        val fuuro = Fuuro(MeldType.KAKAN, tiles, minPon.claimTarget, minPon.claimTile)
        hands -= tile
        fuuroList += fuuro
        invalidateHandAnalysis()
    }

    internal fun removeKanTileRobbedByRon(tile: TileInstance) {
        if (hands.remove(tile)) {
            if (lastDrawnTile?.id == tile.id) {
                lastDrawnTile = null
            }
            invalidateHandAnalysis()
        }
    }

    fun canPon(tile: TileInstance): Boolean =
        !(riichi || doubleRiichi) && (reactionOptionsFor(tile, allowChii = false, canRon = false)?.canPon == true)

    fun canMinkan(tile: TileInstance): Boolean =
        !(riichi || doubleRiichi) && (reactionOptionsFor(tile, allowChii = false, canRon = false)?.canMinkan == true)

    val canKakan: Boolean
        get() = tilesCanKakan.isNotEmpty()

    val canAnkan: Boolean
        get() = tilesCanAnkan.isNotEmpty()

    fun canChii(tile: TileInstance): Boolean = !(riichi || doubleRiichi) && availableChiiPairs(tile).isNotEmpty()

    private fun tilesForPon(tile: TileInstance): List<TileInstance> =
        sameTilesInHands(tile).apply {
            if (size > 2) {
                remove(first { !it.mahjongTile.isRed })
                sortBy { it.mahjongTile.isRed }
            }
            add(tile)
        }

    private fun tilesForMinkan(tile: TileInstance): List<TileInstance> = sameTilesInHands(tile).also { it += tile }

    private fun tilesForAnkan(tile: TileInstance): List<TileInstance> = sameTilesInHands(tile)

    val tilesCanAnkan: Set<TileInstance>
        get() {
            val countsByBaseTile = hashMapOf<MahjongTile, Int>()
            val firstTileByBaseTile = hashMapOf<MahjongTile, TileInstance>()
            for (tile in hands) {
                val baseTile = tile.mahjongTile.baseTile
                countsByBaseTile.merge(baseTile, 1, Int::plus)
                firstTileByBaseTile.putIfAbsent(baseTile, tile)
            }
            val candidates =
                firstTileByBaseTile.entries
                    .filter { (baseTile, _) -> countsByBaseTile[baseTile] == 4 }
                    .mapTo(mutableSetOf()) { it.value }
            if (!riichi && !doubleRiichi) {
                return candidates
            }
            val drawnTile = lastDrawnTile?.mahjongTile ?: return emptySet()
            candidates.removeIf { candidate -> !candidate.mahjongTile.sameKind(drawnTile) }
            if (candidates.isEmpty()) {
                return emptySet()
            }

            val machiBefore = machi
            for (candidate in candidates.toList()) {
                val handsCopy = hands.toMutableList()
                val anKanTilesInHands = hands.filter { tile -> tile.mahjongTile.sameKind(candidate.mahjongTile) }.toMutableList()
                handsCopy -= anKanTilesInHands.toSet()
                val fuuroListCopy =
                    fuuroList.toMutableList().apply {
                        add(Fuuro(MeldType.ANKAN, anKanTilesInHands, ClaimTarget.SELF, candidate))
                    }
                val calculatedMachi = calculateMachi(handsCopy.toMahjongTileList(), fuuroListCopy)
                if (calculatedMachi != machiBefore) {
                    candidates -= candidate
                }
            }
            return candidates
        }

    private val tilesCanKakan: MutableSet<Pair<TileInstance, ClaimTarget>>
        get() =
            mutableSetOf<Pair<TileInstance, ClaimTarget>>().apply {
                fuuroList.forEach { fuuro ->
                    if (!fuuro.isPon) {
                        return@forEach
                    }
                    val tile = hands.firstOrNull { it.mahjongTile.sameKind(fuuro.claimTile.mahjongTile) }
                    if (tile != null) {
                        add(tile to fuuro.claimTarget)
                    }
                }
            }

    fun availableChiiPairs(tile: TileInstance): List<Pair<MahjongTile, MahjongTile>> =
        analyzeFuroReaction(tile, allowChii = true)?.chiChoices?.map { it.first } ?: emptyList()

    fun reactionOptionsFor(
        tile: TileInstance,
        allowChii: Boolean,
        canRon: Boolean,
    ): ReactionOptions? {
        if (riichi || doubleRiichi) {
            return if (canRon) {
                ReactionOptions(
                    canRon = true,
                    canPon = false,
                    canMinkan = false,
                    chiiPairs = emptyList(),
                    suggestedResponse = ReactionResponse(ReactionType.RON, null),
                )
            } else {
                null
            }
        }

        val analysis = analyzeFuroReaction(tile, allowChii)
        val canPon = analysis?.pon != null
        val canMinkan = analysis?.minkan != null
        val chiiPairs = analysis?.chiChoices?.map { it.first } ?: emptyList()
        if (!canRon && !canPon && !canMinkan && chiiPairs.isEmpty()) {
            return null
        }

        val suggestion =
            when {
                canRon -> ReactionResponse(ReactionType.RON, null)
                analysis == null -> ReactionResponse(ReactionType.SKIP, null)
                else -> suggestedReactionResponse(analysis)
            }
        return ReactionOptions(
            canRon = canRon,
            canPon = canPon,
            canMinkan = canMinkan,
            chiiPairs = chiiPairs,
            suggestedResponse = suggestion,
        )
    }

    fun tilePairForPon(tile: TileInstance): Pair<MahjongTile, MahjongTile> {
        val tiles = tilesForPon(tile)
        return tiles[0].mahjongTile to tiles[1].mahjongTile
    }

    val tilePairsForRiichi: List<Pair<MahjongTile, List<MahjongTile>>>
        get() {
            if (isCacheCurrent(AnalysisCache.TILE_PAIRS_FOR_RIICHI)) {
                return cachedTilePairsForRiichi
            }
            cachedTilePairsForRiichi =
                buildList {
                    if (hands.size != 14) return@buildList
                    val shantenWithGot = currentShantenWithGot() ?: return@buildList
                    shantenWithGot.discardToAdvance.entries
                        .filter { it.value.shantenNum == 0 }
                        .map {
                            val discard = MahjongTile.fromUtilsTile(it.key)
                            val waits =
                                it.value.advance
                                    .map(MahjongTile::fromUtilsTile)
                                    .distinct()
                            discard to waits
                        }.filter { (discard, waits) ->
                            hasPhysicalWait(
                                waits,
                                hands.map { it.mahjongTile },
                                discard,
                            )
                        }.distinct()
                        .forEach(::add)
                }
            markCacheCurrent(AnalysisCache.TILE_PAIRS_FOR_RIICHI)
            return cachedTilePairsForRiichi
        }

    private fun sameTilesInHands(tile: TileInstance): MutableList<TileInstance> =
        hands.filter { it.mahjongTile.sameKind(tile.mahjongTile) }.toMutableList()

    val isTenpai: Boolean
        get() = hasPhysicalWait(machi, hands.map { it.mahjongTile })

    /**
     * Ordinary empty waits still count as tenpai. A shape that needs a fifth
     * physical copy of its sole wait does not; both Mahjong Soul and WRC make
     * that distinction.
     */
    fun isTenpaiForExhaustiveDraw(
        @Suppress("UNUSED_PARAMETER") rule: MahjongRule,
    ): Boolean = isTenpai

    private fun hasPhysicalWait(
        waits: List<MahjongTile>,
        concealedTiles: List<MahjongTile>,
        discardedTile: MahjongTile? = null,
    ): Boolean {
        val baseWaits = waits.map { it.baseTile }.distinct()
        if (baseWaits.isEmpty()) return false
        if (baseWaits.size != 1) return true
        val soleWait = baseWaits.single()
        val concealedCount =
            concealedTiles.count { it.baseTile == soleWait } -
                if (discardedTile?.baseTile == soleWait) 1 else 0
        return concealedCount < 4
    }

    fun discardSuggestions(): List<RiichiDiscardSuggestion> {
        if (isCacheCurrent(AnalysisCache.DISCARD_SUGGESTIONS)) {
            return cachedDiscardSuggestions
        }
        val shantenWithGot = currentShantenWithGot()
        cachedDiscardSuggestions =
            if (shantenWithGot == null) {
                emptyList()
            } else {
                shantenWithGot.discardToAdvance.entries
                    .sortedWith(
                        compareBy<Map.Entry<Tile, ShantenWithoutGot>> { it.value.shantenNum }
                            .thenByDescending { it.value.goodShapeAdvanceNum ?: -1 }
                            .thenByDescending { it.value.advanceNum }
                            .thenByDescending { it.value.goodShapeImprovementNum ?: -1 }
                            .thenByDescending { it.value.improvementNum ?: -1 }
                            .thenBy { it.key },
                    ).map { (tile, shanten) ->
                        RiichiDiscardSuggestion(
                            tile = MahjongTile.fromUtilsTile(tile),
                            shantenNum = shanten.shantenNum,
                            advanceTiles = shanten.advance.map(MahjongTile::fromUtilsTile).distinct(),
                            advanceCount = shanten.advanceNum,
                            goodShapeAdvanceCount = shanten.goodShapeAdvanceNum ?: 0,
                            improvementCount = shanten.improvementNum ?: 0,
                            goodShapeImprovementCount = shanten.goodShapeImprovementNum ?: 0,
                        )
                    }.distinctBy { it.tile }
            }
        markCacheCurrent(AnalysisCache.DISCARD_SUGGESTIONS)
        return cachedDiscardSuggestions
    }

    fun bestDiscardSuggestions(): List<MahjongTile> = discardSuggestions().map { it.tile }
}
