package top.ellan.mahjong.riichi

import mahjongutils.models.isYaochu
import top.ellan.mahjong.riichi.model.ClaimTarget
import top.ellan.mahjong.riichi.model.ExhaustiveDraw
import top.ellan.mahjong.riichi.model.GeneralSituation
import top.ellan.mahjong.riichi.model.MahjongRound
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.MeldType
import top.ellan.mahjong.riichi.model.OpeningDiceRoll
import top.ellan.mahjong.riichi.model.PersonalSituation
import top.ellan.mahjong.riichi.model.ScoreItem
import top.ellan.mahjong.riichi.model.ScoreSettlement
import top.ellan.mahjong.riichi.model.ScoringStick
import top.ellan.mahjong.riichi.model.SettlementPayment
import top.ellan.mahjong.riichi.model.SettlementPaymentType
import top.ellan.mahjong.riichi.model.TileInstance
import top.ellan.mahjong.riichi.model.Wind
import top.ellan.mahjong.riichi.model.YakuSettlement
import top.ellan.mahjong.riichi.scoring.RiichiPaoRules
import kotlin.random.Random

private val HAND_SORT_BUCKET_SIZE = MahjongTile.entries.maxOf { tile -> tile.sortOrder } + 1

enum class ReactionType {
    RON,
    PON,
    MINKAN,
    CHII,
    SKIP,
}

data class ReactionOptions
    @JvmOverloads
    constructor(
        val canRon: Boolean,
        val canPon: Boolean,
        val canMinkan: Boolean,
        val chiiPairs: List<Pair<MahjongTile, MahjongTile>>,
        val suggestedResponse: ReactionResponse? = null,
    )

data class ReactionResponse(
    val type: ReactionType,
    val chiiPair: Pair<MahjongTile, MahjongTile>? = null,
)

data class PendingReaction(
    val discarderUuid: String,
    val tile: TileInstance,
    val options: Map<String, ReactionOptions>,
    val isChankan: Boolean = false,
    val pendingKanType: MeldType? = null,
    val responses: MutableMap<String, ReactionResponse> = linkedMapOf(),
)

data class RoundResolution(
    val title: String,
    val yakuSettlements: List<YakuSettlement> = emptyList(),
    val scoreSettlement: ScoreSettlement? = null,
    val draw: ExhaustiveDraw? = null,
)

abstract class RiichiRoundEngineState protected constructor(
    players: List<RiichiPlayerState>,
    val rule: MahjongRule = MahjongRule(),
) {
    val seats: MutableList<RiichiPlayerState> = players.toMutableList()
    var round: MahjongRound = rule.length.getStartingRound()
    private val liveWallBuffer: LiveWallBuffer = LiveWallBuffer()
    protected val liveWall: MutableList<TileInstance> = liveWallBuffer
    val wall: MutableList<TileInstance> = liveWallBuffer
    val deadWall: MutableList<TileInstance> = mutableListOf()
    val discards: MutableList<TileInstance> = mutableListOf()
    var kanCount: Int = 0
        protected set
    var dicePoints: Int = 0
        protected set
    protected var openingDiceRoll: OpeningDiceRoll? = null
    var currentPlayerIndex: Int = 0
        protected set
    var pendingReaction: PendingReaction? = null
        protected set
    var lastResolution: RoundResolution? = null
        protected set
    var started: Boolean = false
        protected set
    var gameFinished: Boolean = false
        protected set
    protected var currentDrawIsRinshan: Boolean = false
    protected var currentDiscardIsAfterRinshan: Boolean = false
    protected var pendingAbortiveDraw: ExhaustiveDraw? = null
    protected var revealedKanDoraCount: Int = 0
    protected var pendingOpenKanDoraCount: Int = 0
    protected val kuikaeForbiddenByPlayer: MutableMap<String, Set<MahjongTile>> = linkedMapOf()
    protected val paoLiabilityByWinner: MutableMap<String, MutableMap<String, String>> = linkedMapOf()
    protected val seatByUuid: Map<String, RiichiPlayerState> = seats.associateBy { it.uuid }
    protected val seatIndexByUuid: Map<String, Int> = seats.mapIndexed { index, player -> player.uuid to index }.toMap()

    val currentPlayer: RiichiPlayerState
        get() = seats[currentPlayerIndex]

    val dealer: RiichiPlayerState
        get() = seatOrderFromDealer().first()

    val isFirstRound: Boolean
        get() = discards.size < 4 && seats.none { it.fuuroList.isNotEmpty() }

    val isHoutei: Boolean
        get() = liveWall.isEmpty() && !currentDrawIsRinshan && !currentDiscardIsAfterRinshan

    val isSuufonRenda: Boolean
        get() {
            if (discards.size != 4) return false
            if (seats.any { it.fuuroList.isNotEmpty() }) return false
            val lastFour = discards.takeLast(4)
            val first = lastFour.first().scoringTile
            if (first.type.name != "Z" || first.realNum !in 1..4) return false
            return lastFour.all { it.scoringTile == first }
        }

    val doraIndicators: List<TileInstance>
        get() {
            val visibleKanCount = minOf(revealedKanDoraCount, 4)
            if (deadWall.isEmpty()) {
                return emptyList()
            }
            return buildList {
                repeat(visibleKanCount + 1) {
                    val index = (4 - it) * 2 + kanCount
                    if (index in deadWall.indices) {
                        add(deadWall[index])
                    }
                }
            }
        }

    val uraDoraIndicators: List<TileInstance>
        get() {
            val visibleKanCount = minOf(revealedKanDoraCount, 4)
            if (deadWall.isEmpty()) {
                return emptyList()
            }
            return buildList {
                repeat(visibleKanCount + 1) {
                    val index = (4 - it) * 2 + 1 + kanCount
                    if (index in deadWall.indices) {
                        add(deadWall[index])
                    }
                }
            }
        }

    val generalSituation: GeneralSituation
        get() =
            GeneralSituation(
                isFirstRound,
                isHoutei,
                round.wind,
                doraIndicators.map { it.mahjongTile },
                uraDoraIndicators.map { it.mahjongTile },
            )

    init {
        require(players.size == 4) { "Riichi round engine requires exactly 4 players" }
        seats.forEach {
            it.points = rule.startingPoints
            it.basicThinkingTime = rule.thinkingTime.base
            it.extraThinkingTime = rule.thinkingTime.extra
        }
    }

    fun startRound() {
        if (gameFinished) {
            return
        }
        clearRoundState()
        buildWall()
        assignDeadWall()
        dealHands()
        currentPlayerIndex = round.round
        started = true
        lastResolution = null
        pendingReaction = null
        currentDrawIsRinshan = false
        pendingAbortiveDraw = null
    }

    fun setPendingDiceRoll(diceRoll: OpeningDiceRoll?) {
        openingDiceRoll = diceRoll
    }

    fun discard(
        playerUuid: String,
        tileIndex: Int,
    ): Boolean {
        if (!started || pendingReaction != null) return false
        if (currentPlayer.uuid != playerUuid) return false
        val player = currentPlayer
        if (tileIndex !in player.hands.indices) return false
        val selectedTile = player.hands[tileIndex]
        if (selectedTile.mahjongTile.baseTile in kuikaeForbiddenByPlayer[playerUuid].orEmpty()) return false
        if ((player.riichi || player.doubleRiichi) &&
            selectedTile.id != player.lastDrawnTile?.id &&
            selectedTile.id != player.riichiSengenTile?.id
        ) {
            return false
        }
        val discarded = player.discardTile(selectedTile) ?: return false
        kuikaeForbiddenByPlayer.remove(playerUuid)
        currentDiscardIsAfterRinshan = currentDrawIsRinshan
        currentDrawIsRinshan = false
        discards += discarded
        revealPendingOpenKanDoraForMajsoulIfNeeded()
        pendingReaction = computePendingReaction(player, discarded)
        if (pendingReaction == null) {
            val abortiveDraw = pendingAbortiveDraw
            if (abortiveDraw != null) {
                resolveDraw(abortiveDraw)
            } else {
                advanceAfterDiscard()
            }
        }
        return true
    }

    fun declareRiichi(
        playerUuid: String,
        tileIndex: Int,
    ): Boolean {
        if (!started || pendingReaction != null) return false
        val player = currentPlayer
        if (player.uuid != playerUuid || tileIndex !in player.hands.indices) return false
        if (riichiRequiresMinimumWallTilesForDeclaration() && liveWall.size < 4) return false
        if (!player.isMenzenchin || player.riichi || player.doubleRiichi || player.points < ScoringStick.P1000.point) return false
        val discardTile = player.hands[tileIndex].mahjongTile
        if (player.tilePairsForRiichi.none { it.first == discardTile }) return false
        val tile = player.hands[tileIndex]
        player.declareRiichi(tile, isFirstRound)
        player.points -= ScoringStick.P1000.point
        player.sticks += ScoringStick.P1000
        return discard(playerUuid, tileIndex)
    }

    fun tryTsumo(playerUuid: String): Boolean {
        if (!started || pendingReaction != null || currentPlayer.uuid != playerUuid) return false
        val player = currentPlayer
        val winningTileInstance = bestTsumoWinningTile(player) ?: return false
        resolveTsumo(player, winningTileInstance, isRinshanKaihoh = currentDrawIsRinshan)
        return true
    }

    fun canDeclareTsumo(playerUuid: String): Boolean {
        if (!started || pendingReaction != null || currentPlayer.uuid != playerUuid) return false
        return bestTsumoWinningTile(currentPlayer) != null
    }

    private fun bestTsumoWinningTile(player: RiichiPlayerState): TileInstance? {
        val candidates =
            if (player == dealer && discards.isEmpty() && seats.none { it.fuuroList.isNotEmpty() }) {
                player.hands.distinctBy { it.mahjongTile }
            } else {
                listOfNotNull(player.lastDrawnTile)
            }
        val situation = generalSituation
        val personal = personalSituation(player, isTsumo = true, isRinshanKaihoh = currentDrawIsRinshan)
        return candidates
            .mapNotNull { candidate ->
                val canWin =
                    player.canWin(
                        candidate.mahjongTile,
                        true,
                        rule = rule,
                        generalSituation = situation,
                        personalSituation = personal,
                    )
                if (!canWin) {
                    null
                } else {
                    candidate to
                        player.calcYakuSettlementForWin(
                            winningTile = candidate.mahjongTile,
                            isWinningTileInHands = true,
                            rule = rule,
                            generalSituation = situation,
                            personalSituation = personal,
                            doraIndicators = situation.doraIndicators,
                            uraDoraIndicators = situation.uraDoraIndicators,
                        )
                }
            }.maxWithOrNull(
                compareBy<Pair<TileInstance, YakuSettlement>> { (_, settlement) -> settlement.score }
                    .thenBy { (_, settlement) ->
                        settlement.yakumanList.size + settlement.doubleYakumanList.size * 2
                    }.thenBy { (_, settlement) -> settlement.han }
                    .thenBy { (_, settlement) -> settlement.fu },
            )?.first
    }

    fun tryAnkanOrKakan(
        playerUuid: String,
        tile: MahjongTile,
    ): Boolean {
        if (!started || pendingReaction != null || currentPlayer.uuid != playerUuid) return false
        if (playerUuid in kuikaeForbiddenByPlayer) return false
        if (kanForbiddenAfterLastLiveDraw() && liveWall.isEmpty()) return false
        if (kanCount >= 4) return false
        val player = currentPlayer
        val ankanTile = player.tilesCanAnkan.find { it.mahjongTile == tile }
        if (ankanTile != null) {
            if (!canDrawRinshanTile()) return false
            revealPendingOpenKanDoraForMajsoulIfNeeded()
            currentDrawIsRinshan = false
            cancelActiveIppatsu()
            pendingReaction =
                computeChankanReaction(
                    player,
                    ankanTile,
                    allowOnlyKokushi = true,
                    pendingKanType = MeldType.ANKAN,
                )
            if (pendingReaction != null) {
                pendingAbortiveDraw = null
                return true
            }
            player.ankan(ankanTile)
            registerClosedKan()
            drawRinshanAndContinue(player)
            return true
        }
        val kakanTile =
            player.hands.find { candidate ->
                candidate.mahjongTile.sameKind(tile) &&
                    player.fuuroList.any { fuuro ->
                        fuuro.isPon && fuuro.claimTile.mahjongTile.sameKind(candidate.mahjongTile)
                    }
            }
        if (kakanTile != null) {
            if (!canDrawRinshanTile()) return false
            revealPendingOpenKanDoraForMajsoulIfNeeded()
            currentDrawIsRinshan = false
            cancelActiveIppatsu()
            pendingReaction =
                computeChankanReaction(
                    player,
                    kakanTile,
                    allowOnlyKokushi = false,
                    pendingKanType = MeldType.KAKAN,
                )
            if (pendingReaction != null) {
                pendingAbortiveDraw = null
                return true
            }
            player.kakan(kakanTile)
            registerOpenKan()
            revealPendingOpenKanDoraForEarlyProfileIfNeeded()
            drawRinshanAndContinue(player)
            return true
        }
        return false
    }

    fun react(
        playerUuid: String,
        response: ReactionResponse,
    ): Boolean {
        val pending = pendingReaction ?: return false
        val options = pending.options[playerUuid] ?: return false
        when (response.type) {
            ReactionType.RON -> {
                if (!options.canRon) return false
            }

            ReactionType.PON -> {
                if (!options.canPon) return false
            }

            ReactionType.MINKAN -> {
                if (!options.canMinkan) return false
            }

            ReactionType.CHII -> {
                if (response.chiiPair !in options.chiiPairs) return false
            }

            ReactionType.SKIP -> {}
        }
        if (response.type != ReactionType.RON && options.canRon) {
            seatPlayer(playerUuid)?.markTemporaryFuriten()
        }
        pending.responses[playerUuid] = response
        resolvePendingReactionsIfReady()
        return true
    }

    fun availableReactions(playerUuid: String): ReactionOptions? = pendingReaction?.options?.get(playerUuid)

    fun canKyuushuKyuuhai(playerUuid: String): Boolean =
        started &&
            pendingReaction == null &&
            currentPlayer.uuid == playerUuid &&
            isFirstRound &&
            currentPlayer.numbersOfYaochuuhaiTypes >= 9

    fun declareKyuushuKyuuhai(playerUuid: String): Boolean {
        if (!canKyuushuKyuuhai(playerUuid)) return false
        resolveDraw(ExhaustiveDraw.KYUUSHU_KYUUHAI)
        return true
    }

    fun seatPlayer(uuid: String): RiichiPlayerState? = seatByUuid[uuid]

    fun placementOrder(): List<RiichiPlayerState> =
        seats.sortedWith(
            compareByDescending<RiichiPlayerState> { it.points }
                .thenBy { seatIndex(it) },
        )

    fun nagashiManganCandidates(): List<RiichiPlayerState> =
        seats.filter { player ->
            player.discardedTiles.isNotEmpty() &&
                player.discardedTiles.size == player.discardedTilesForDisplay.size &&
                player.discardedTiles.all { it.scoringTile.isYaochu }
        }

    protected fun removeFirstLiveWallTile(): TileInstance = liveWallBuffer.removeFirstLiveTile()

    protected fun removeLastLiveWallTile(): TileInstance = liveWallBuffer.removeLastLiveTile()

    protected abstract fun clearRoundState()

    protected abstract fun buildWall()

    protected abstract fun assignDeadWall()

    protected abstract fun dealHands()

    protected abstract fun revealPendingOpenKanDoraForMajsoulIfNeeded()

    protected abstract fun computePendingReaction(
        discarder: RiichiPlayerState,
        tile: TileInstance,
    ): PendingReaction?

    protected abstract fun resolveDraw(draw: ExhaustiveDraw)

    protected abstract fun advanceAfterDiscard()

    protected abstract fun personalSituation(
        player: RiichiPlayerState,
        isTsumo: Boolean = false,
        isChankan: Boolean = false,
        isRinshanKaihoh: Boolean = false,
    ): PersonalSituation

    protected abstract fun resolveTsumo(
        player: RiichiPlayerState,
        tile: TileInstance,
        isRinshanKaihoh: Boolean = false,
    )

    protected abstract fun canDrawRinshanTile(): Boolean

    protected abstract fun cancelActiveIppatsu()

    protected abstract fun computeChankanReaction(
        discarder: RiichiPlayerState,
        tile: TileInstance,
        allowOnlyKokushi: Boolean,
        pendingKanType: MeldType,
    ): PendingReaction?

    protected abstract fun registerClosedKan()

    protected abstract fun drawRinshanAndContinue(player: RiichiPlayerState)

    protected abstract fun registerOpenKan()

    protected abstract fun revealPendingOpenKanDoraForEarlyProfileIfNeeded()

    protected abstract fun resolvePendingReactionsIfReady()

    protected abstract fun riichiRequiresMinimumWallTilesForDeclaration(): Boolean

    protected abstract fun kanForbiddenAfterLastLiveDraw(): Boolean

    protected abstract fun resolveRon(
        winners: List<RiichiPlayerState>,
        target: RiichiPlayerState,
        tile: TileInstance,
        isChankan: Boolean,
    )

    protected abstract fun seatIndex(player: RiichiPlayerState): Int

    protected abstract fun seatOrderFromDealer(): List<RiichiPlayerState>

    protected abstract fun seatOrderFrom(target: RiichiPlayerState): List<RiichiPlayerState>

    protected abstract fun claimTarget(
        claimer: RiichiPlayerState,
        discarder: RiichiPlayerState,
    ): ClaimTarget

    protected abstract fun isSuukaikanAbort(): Boolean
}

abstract class RiichiRoundEngineWallAndReaction protected constructor(
    players: List<RiichiPlayerState>,
    rule: MahjongRule,
) : RiichiRoundEngineState(players, rule) {
    protected override fun clearRoundState() {
        liveWall.clear()
        deadWall.clear()
        discards.clear()
        kanCount = 0
        currentDrawIsRinshan = false
        currentDiscardIsAfterRinshan = false
        pendingAbortiveDraw = null
        revealedKanDoraCount = 0
        pendingOpenKanDoraCount = 0
        kuikaeForbiddenByPlayer.clear()
        paoLiabilityByWinner.clear()
        seats.forEach {
            it.resetRoundState()
        }
    }

    protected override fun buildWall() {
        val tiles =
            when (rule.redFive) {
                MahjongRule.RedFive.NONE -> MahjongTile.normalWall
                MahjongRule.RedFive.THREE -> MahjongTile.redFive3Wall
                MahjongRule.RedFive.FOUR -> MahjongTile.redFive4Wall
            }.shuffled(Random.Default).map { TileInstance(mahjongTile = it) }
        val diceRoll = openingDiceRoll ?: OpeningDiceRoll(Random.nextInt(1, 7), Random.nextInt(1, 7))
        openingDiceRoll = null
        dicePoints = diceRoll.total()
        val startingTileIndex = wallBreakTileIndex(dicePoints, round.round)
        val reordered =
            MutableList(tiles.size) {
                val tileIndex = (startingTileIndex + it) % tiles.size
                tiles[tileIndex]
            }
        liveWall.clear()
        liveWall.addAll(reordered)
    }

    internal fun wallBreakTileIndex(
        dicePoints: Int,
        dealerIndex: Int,
    ): Int {
        val openDoorIndex = Math.floorMod(dealerIndex + dicePoints - 1, 4)
        return Math.floorMod(openDoorIndex * 34 + 2 * dicePoints, 136)
    }

    protected override fun assignDeadWall() {
        repeat(14) {
            deadWall += liveWall.removeLast()
        }
        deadWall.reverse()
    }

    protected override fun dealHands() {
        val dealer = dealer
        repeat(3) {
            seats.forEach { player ->
                repeat(4) {
                    player.drawTile(drawFromLiveWallFront())
                }
            }
        }
        seats.forEach { it.drawTile(drawFromLiveWallFront()) }
        dealer.drawTile(drawFromLiveWallFront())
        seats.forEach { player -> sortHandByCount(player.hands) }
    }

    private fun drawRinshanTile(player: RiichiPlayerState): TileInstance {
        val rinshanTileIndex = if (kanCount % 2 == 0) deadWall.lastIndex - 1 else deadWall.lastIndex
        val tile = deadWall.removeAt(rinshanTileIndex)
        val lastWallTile = drawFromLiveWallBack()
        deadWall.add(0, lastWallTile)
        player.drawTile(tile)
        return tile
    }

    protected override fun drawRinshanAndContinue(player: RiichiPlayerState) {
        val rinshan = drawRinshanTile(player)
        sortHandByCount(player.hands)
        player.hands.remove(rinshan)
        player.hands.add(rinshan)
        currentDrawIsRinshan = true
        currentDiscardIsAfterRinshan = false
        pendingAbortiveDraw = if (isSuukaikanAbort()) ExhaustiveDraw.SUUKAIKAN else null
    }

    protected override fun canDrawRinshanTile(): Boolean = deadWall.size >= 2 && liveWall.isNotEmpty()

    protected override fun computePendingReaction(
        discarder: RiichiPlayerState,
        tile: TileInstance,
    ): PendingReaction? {
        val options = linkedMapOf<String, ReactionOptions>()
        val ronOnlyDiscard =
            (lastDiscardRonOnly() && liveWall.isEmpty()) || pendingAbortiveDraw != null
        val situation = generalSituation
        forEachReactionCandidate(discarder) { candidate, target ->
            val canRon =
                canRonOnDiscard(
                    candidate = candidate,
                    tile = tile,
                    generalSituation = situation,
                    personalSituation = personalSituation(candidate, isTsumo = false),
                )
            val reactionOptions =
                if (ronOnlyDiscard) {
                    if (!canRon) {
                        null
                    } else {
                        ReactionOptions(
                            canRon = true,
                            canPon = false,
                            canMinkan = false,
                            chiiPairs = emptyList(),
                            suggestedResponse = ReactionResponse(ReactionType.RON, null),
                        )
                    }
                } else {
                    candidate.reactionOptionsFor(tile, allowChii = target == ClaimTarget.LEFT, canRon = canRon)
                }
            if (reactionOptions != null) {
                options[candidate.uuid] = reactionOptions
            }
        }
        return options.takeIf { it.isNotEmpty() }?.let { PendingReaction(discarder.uuid, tile, it) }
    }

    protected override fun computeChankanReaction(
        discarder: RiichiPlayerState,
        tile: TileInstance,
        allowOnlyKokushi: Boolean,
        pendingKanType: MeldType,
    ): PendingReaction? {
        val options = linkedMapOf<String, ReactionOptions>()
        val situation = generalSituation
        forEachReactionCandidate(discarder) { candidate, _ ->
            val canRon =
                canRonOnDiscard(
                    candidate = candidate,
                    tile = tile,
                    generalSituation = situation,
                    personalSituation = personalSituation(candidate, isTsumo = false, isChankan = true),
                    allowOnlyKokushi = allowOnlyKokushi,
                )
            if (canRon) {
                options[candidate.uuid] =
                    ReactionOptions(
                        canRon = true,
                        canPon = false,
                        canMinkan = false,
                        chiiPairs = emptyList(),
                        suggestedResponse = ReactionResponse(ReactionType.RON, null),
                    )
            }
        }
        return options.takeIf { it.isNotEmpty() }?.let {
            PendingReaction(
                discarder.uuid,
                tile,
                it,
                isChankan = true,
                pendingKanType = pendingKanType,
            )
        }
    }

    private fun canRonOnDiscard(
        candidate: RiichiPlayerState,
        tile: TileInstance,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
        allowOnlyKokushi: Boolean = false,
    ): Boolean {
        if (!candidate.isTenpai || candidate.missedRonFuriten || candidate.isFuriten(tile, discards)) {
            return false
        }
        if (allowOnlyKokushi && !candidate.isKokushimuso(tile.mahjongTile)) {
            return false
        }
        return candidate.canWin(
            tile.mahjongTile,
            false,
            rule = rule,
            generalSituation = generalSituation,
            personalSituation = personalSituation,
        )
    }

    private inline fun forEachReactionCandidate(
        discarder: RiichiPlayerState,
        block: (candidate: RiichiPlayerState, target: ClaimTarget) -> Unit,
    ) {
        val discarderIndex = seatIndex(discarder)
        for (offset in 1 until seats.size) {
            val candidate = seats[(discarderIndex + offset) % seats.size]
            val target =
                when (offset) {
                    1 -> ClaimTarget.LEFT
                    2 -> ClaimTarget.ACROSS
                    else -> ClaimTarget.RIGHT
                }
            block(candidate, target)
        }
    }

    protected override fun resolvePendingReactionsIfReady() {
        val pending = pendingReaction ?: return
        val discarder = seatPlayer(pending.discarderUuid)!!
        val orderedClaimers = seatOrderFrom(discarder).drop(1)
        if (!allRespondedFor(pending) { it.canRon }) {
            return
        }
        val ronPlayers = orderedClaimers.filter { pending.responses[it.uuid]?.type == ReactionType.RON }
        if (ronPlayers.isNotEmpty()) {
            val winners =
                when (rule.ronMode) {
                    MahjongRule.RonMode.HEAD_BUMP -> ronPlayers.take(1)
                    MahjongRule.RonMode.MULTI_RON -> ronPlayers
                }
            if (pending.isChankan) {
                discarder.removeKanTileRobbedByRon(pending.tile)
            }
            resolveRon(winners, discarder, pending.tile, isChankan = pending.isChankan)
            pendingReaction = null
            return
        }

        pendingAbortiveDraw?.let { abortiveDraw ->
            pendingReaction = null
            resolveDraw(abortiveDraw)
            return
        }

        if (pending.isChankan) {
            when (pending.pendingKanType) {
                MeldType.ANKAN -> {
                    discarder.ankan(pending.tile)
                    registerClosedKan()
                }

                MeldType.KAKAN -> {
                    discarder.kakan(pending.tile)
                    registerOpenKan()
                    revealPendingOpenKanDoraForEarlyProfileIfNeeded()
                }

                else -> {
                    error("Chankan reaction is missing its pending kan type")
                }
            }
            pendingReaction = null
            drawRinshanAndContinue(discarder)
            return
        }
        if (!allRespondedFor(pending) { it.canPon || it.canMinkan }) {
            return
        }
        val ponKanWinner =
            orderedClaimers.firstOrNull { player ->
                val type = pending.responses[player.uuid]?.type
                type == ReactionType.PON || type == ReactionType.MINKAN
            }
        if (ponKanWinner != null) {
            val response = pending.responses[ponKanWinner.uuid]!!
            val winner = ponKanWinner
            val target = claimTarget(winner, discarder)
            if (response.type == ReactionType.PON) {
                winner.pon(pending.tile, target, discarder)
                kuikaeForbiddenByPlayer[winner.uuid] = setOf(pending.tile.mahjongTile.baseTile)
                cancelActiveIppatsu()
                RiichiPaoRules.registerLiability(paoLiabilityByWinner, winner, discarder, pending.tile)
                currentDrawIsRinshan = false
                currentDiscardIsAfterRinshan = false
                pendingAbortiveDraw = null
            } else {
                winner.minkan(pending.tile, target, discarder)
                cancelActiveIppatsu()
                RiichiPaoRules.registerLiability(paoLiabilityByWinner, winner, discarder, pending.tile)
                currentPlayerIndex = seats.indexOf(winner)
                registerOpenKan()
                revealPendingOpenKanDoraForEarlyProfileIfNeeded()
                drawRinshanAndContinue(winner)
            }
            currentPlayerIndex = seats.indexOf(winner)
            pendingReaction = null
            return
        }

        if (!allRespondedFor(pending) { it.chiiPairs.isNotEmpty() }) {
            return
        }
        val chiiResponse =
            orderedClaimers.firstNotNullOfOrNull { player ->
                pending.responses[player.uuid]
                    ?.takeIf { it.type == ReactionType.CHII }
                    ?.let { player to it }
            }
        if (chiiResponse != null) {
            val winner = chiiResponse.first
            val response = chiiResponse.second
            val chiiPair = response.chiiPair ?: error("Chii response is missing its tile pair")
            winner.chii(pending.tile, chiiPair, claimTarget(winner, discarder), discarder)
            kuikaeForbiddenByPlayer[winner.uuid] = kuikaeForbiddenAfterChii(pending.tile, chiiPair)
            cancelActiveIppatsu()
            currentPlayerIndex = seats.indexOf(winner)
            currentDrawIsRinshan = false
            currentDiscardIsAfterRinshan = false
            pendingAbortiveDraw = null
            pendingReaction = null
            return
        }

        pendingReaction = null
        advanceAfterDiscard()
    }

    private inline fun allRespondedFor(
        pending: PendingReaction,
        predicate: (ReactionOptions) -> Boolean,
    ): Boolean {
        pending.options.forEach { (uuid, options) ->
            if (predicate(options) && uuid !in pending.responses) {
                return false
            }
        }
        return true
    }

    protected override fun advanceAfterDiscard() {
        currentDrawIsRinshan = false
        currentDiscardIsAfterRinshan = false
        pendingAbortiveDraw = null
        if (isSuufonRenda) {
            resolveDraw(ExhaustiveDraw.SUUFON_RENDA)
            return
        }
        if (seats.count { it.riichi || it.doubleRiichi } == 4) {
            resolveDraw(ExhaustiveDraw.SUUCHA_RIICHI)
            return
        }
        if (liveWall.isEmpty()) {
            resolveDraw(ExhaustiveDraw.NORMAL)
            return
        }
        currentPlayerIndex = (currentPlayerIndex + 1) % seats.size
        currentPlayer.drawTile(drawFromLiveWallFront())
        sortHandByCount(currentPlayer.hands)
    }

    private fun sortHandByCount(hand: MutableList<TileInstance>) {
        if (hand.size < 2) {
            return
        }
        val counts = IntArray(HAND_SORT_BUCKET_SIZE)
        hand.forEach { tile ->
            counts[tile.mahjongTile.sortOrder]++
        }
        for (index in 1 until counts.size) {
            counts[index] += counts[index - 1]
        }
        val sorted = arrayOfNulls<TileInstance>(hand.size)
        for (index in hand.lastIndex downTo 0) {
            val tile = hand[index]
            val order = tile.mahjongTile.sortOrder
            val position = --counts[order]
            sorted[position] = tile
        }
        for (index in sorted.indices) {
            hand[index] = sorted[index]!!
        }
    }

    private fun drawFromLiveWallFront(): TileInstance = removeFirstLiveWallTile()

    private fun drawFromLiveWallBack(): TileInstance = removeLastLiveWallTile()

    protected override fun registerClosedKan() {
        kanCount++
        if (revealedKanDoraCount < 4) {
            revealedKanDoraCount++
        }
    }

    protected override fun registerOpenKan() {
        kanCount++
        if (revealedKanDoraCount + pendingOpenKanDoraCount < 4) {
            pendingOpenKanDoraCount++
        }
    }

    private fun revealPendingOpenKanDoraIfNeeded() {
        if (pendingOpenKanDoraCount <= 0) {
            return
        }
        val revealCount = minOf(4 - revealedKanDoraCount, pendingOpenKanDoraCount)
        if (revealCount > 0) {
            revealedKanDoraCount += revealCount
        }
        pendingOpenKanDoraCount = 0
    }

    protected override fun revealPendingOpenKanDoraForMajsoulIfNeeded() {
        if (rule.riichiProfile == MahjongRule.RiichiProfile.MAJSOUL) {
            revealPendingOpenKanDoraIfNeeded()
        }
    }

    protected override fun revealPendingOpenKanDoraForEarlyProfileIfNeeded() {
        if (
            rule.riichiProfile == MahjongRule.RiichiProfile.EARLY_KAN_DORA ||
            rule.riichiProfile.name == "TOURNAMENT"
        ) {
            revealPendingOpenKanDoraIfNeeded()
        }
    }

    protected override fun cancelActiveIppatsu() {
        seats.forEach { player ->
            if (player.riichi || player.doubleRiichi) {
                player.markIppatsuInterrupted()
            }
        }
    }

    private fun kuikaeForbiddenAfterChii(
        claimedTile: TileInstance,
        chiiPair: Pair<MahjongTile, MahjongTile>,
    ): Set<MahjongTile> {
        val claimed = claimedTile.mahjongTile.baseTile
        val forbidden = linkedSetOf(claimed)
        val claimedNumber = claimed.scoringTile.realNum
        val pairNumbers = listOf(chiiPair.first.baseTile.scoringTile.realNum, chiiPair.second.baseTile.scoringTile.realNum)
        val sujiKuikae =
            when {
                pairNumbers.all { it < claimedNumber } -> adjacentSuitedTile(claimed, -3)
                pairNumbers.all { it > claimedNumber } -> adjacentSuitedTile(claimed, 3)
                else -> null
            }
        if (sujiKuikae != null) {
            forbidden += sujiKuikae
        }
        return forbidden
    }

    private fun adjacentSuitedTile(
        tile: MahjongTile,
        offset: Int,
    ): MahjongTile? {
        val ordinal = tile.baseTile.ordinal
        if (ordinal !in MahjongTile.M1.ordinal..MahjongTile.S9.ordinal) return null
        val number = ordinal % 9 + 1
        if (number + offset !in 1..9) return null
        return MahjongTile.entries[ordinal + offset]
    }

    protected override fun riichiRequiresMinimumWallTilesForDeclaration(): Boolean = true

    private fun lastDiscardRonOnly(): Boolean = true

    protected override fun kanForbiddenAfterLastLiveDraw(): Boolean = true
}

abstract class RiichiRoundEngineSettlement protected constructor(
    players: List<RiichiPlayerState>,
    rule: MahjongRule,
) : RiichiRoundEngineWallAndReaction(players, rule) {
    protected override fun resolveRon(
        winners: List<RiichiPlayerState>,
        target: RiichiPlayerState,
        tile: TileInstance,
        isChankan: Boolean,
    ) {
        currentDrawIsRinshan = false
        pendingAbortiveDraw = null
        cancelRiichiDepositIfDeclarationRon(target, tile)
        val yakuSettlements = mutableListOf<YakuSettlement>()
        val scoreList = mutableListOf<ScoreItem>()
        val situation = generalSituation
        val doraIndicatorTiles = situation.doraIndicators
        val uraDoraIndicatorTiles = situation.uraDoraIndicators
        val seatOrderFromTarget = seatOrderFrom(target)
        val atamahanePlayer = seatOrderFromTarget.firstOrNull { it in winners }
        val allRiichiStickQuantity = seats.sumOf { it.riichiStickAmount }
        val honbaScore = round.honba * 300
        val riichiPoolScore = allRiichiStickQuantity * ScoringStick.P1000.point
        winners.forEach {
            val receivesTableBonuses = it == atamahanePlayer
            val winnerHonbaScore = if (receivesTableBonuses) honbaScore else 0
            val settlement =
                it.calcYakuSettlementForWin(
                    winningTile = tile.mahjongTile,
                    isWinningTileInHands = false,
                    rule = rule,
                    generalSituation = situation,
                    personalSituation = personalSituation(it, isChankan = isChankan),
                    doraIndicators = doraIndicatorTiles,
                    uraDoraIndicators = uraDoraIndicatorTiles,
                )
            val liabilityEntries = RiichiPaoRules.liabilityEntries(paoLiabilityByWinner, it, settlement, ::seatPlayer)
            val basicScore = settlement.score
            val payments = mutableListOf<SettlementPayment>()
            val paoBreakdown = RiichiPaoRules.ronBreakdown(liabilityEntries, it == dealer, basicScore, target, seatOrderFromDealer())
            val targetBasePayment =
                paoBreakdown.targetBase +
                    if (receivesTableBonuses && target == paoBreakdown.honbaPayer) winnerHonbaScore else 0
            if (targetBasePayment > 0) {
                payments += SettlementPayment(target.uuid, targetBasePayment, SettlementPaymentType.RON)
            }
            paoBreakdown.liabilityPayments.forEach { (liablePlayer, amount) ->
                val totalPayment =
                    amount +
                        if (receivesTableBonuses && liablePlayer == paoBreakdown.honbaPayer && liablePlayer != target) {
                            winnerHonbaScore
                        } else {
                            0
                        }
                if (totalPayment > 0) {
                    payments +=
                        SettlementPayment(
                            liablePlayer.uuid,
                            totalPayment,
                            SettlementPaymentType.PAO,
                            paoBreakdown.liabilityNotes[liablePlayer.uuid].orEmpty(),
                        )
                }
            }
            if (it == atamahanePlayer && riichiPoolScore > 0) {
                payments += SettlementPayment("", riichiPoolScore, SettlementPaymentType.RIICHI_POOL)
            }
            val score = basicScore + winnerHonbaScore + if (receivesTableBonuses) riichiPoolScore else 0
            scoreList += ScoreItem(it.displayName, it.uuid, it.points, score)
            it.points += score
            yakuSettlements += settlement.copy(paymentBreakdown = payments)
        }
        val targetPaoShare =
            yakuSettlements.sumOf { settlement ->
                settlement.paymentBreakdown
                    .filter { payment -> payment.payerUuid == target.uuid && payment.type != SettlementPaymentType.RIICHI_POOL }
                    .sumOf(SettlementPayment::amount)
            }
        scoreList += ScoreItem(target.displayName, target.uuid, target.points, -targetPaoShare)
        target.points -= targetPaoShare
        val liabilityTotals = linkedMapOf<String, Int>()
        yakuSettlements.forEach { settlement ->
            settlement.paymentBreakdown
                .filter { payment ->
                    payment.payerUuid.isNotBlank() &&
                        payment.payerUuid != target.uuid &&
                        payment.type == SettlementPaymentType.PAO
                }.forEach { payment -> liabilityTotals.merge(payment.payerUuid, payment.amount, Int::plus) }
        }
        liabilityTotals.forEach { (liableUuid, amount) ->
            val liablePlayer = seatPlayer(liableUuid) ?: return@forEach
            scoreList += ScoreItem(liablePlayer.displayName, liablePlayer.uuid, liablePlayer.points, -amount)
            liablePlayer.points -= amount
        }
        seats.filter { it !in winners && it != target }.forEach {
            if (it.uuid in liabilityTotals.keys) {
                return@forEach
            }
            scoreList += ScoreItem(it.displayName, it.uuid, it.points, 0)
        }
        lastResolution = RoundResolution("Ron", yakuSettlements, ScoreSettlement("Ron", scoreList))
        finishRound(
            dealerRemaining = winners.contains(dealer),
            clearRiichiSticks = true,
            incrementHonbaOnRotation = false,
            forceDealerContinuation = winners.size > 1 && winners.contains(dealer),
        )
    }

    private fun cancelRiichiDepositIfDeclarationRon(
        target: RiichiPlayerState,
        tile: TileInstance,
    ) {
        if (!(target.riichi || target.doubleRiichi)) {
            return
        }
        val declarationTile = target.riichiSengenTile ?: return
        if (declarationTile.id != tile.id) {
            return
        }
        if (target.sticks.remove(ScoringStick.P1000)) {
            target.points += ScoringStick.P1000.point
        }
        target.riichi = false
        target.doubleRiichi = false
        target.riichiSengenTile = null
    }

    protected override fun resolveTsumo(
        player: RiichiPlayerState,
        tile: TileInstance,
        isRinshanKaihoh: Boolean,
    ) {
        currentDrawIsRinshan = false
        pendingAbortiveDraw = null
        val yakuSettlements = mutableListOf<YakuSettlement>()
        val scoreList = mutableListOf<ScoreItem>()
        val situation = generalSituation
        val doraIndicatorTiles = situation.doraIndicators
        val uraDoraIndicatorTiles = situation.uraDoraIndicators
        val allRiichiStickQuantity = seats.sumOf { it.riichiStickAmount }
        val honbaScore = round.honba * 300
        val riichiPoolScore = allRiichiStickQuantity * ScoringStick.P1000.point
        val tsumoPlayerIsDealer = player == dealer
        val settlement =
            player.calcYakuSettlementForWin(
                winningTile = tile.mahjongTile,
                isWinningTileInHands = true,
                rule = rule,
                generalSituation = situation,
                personalSituation = personalSituation(player, isTsumo = true, isRinshanKaihoh = isRinshanKaihoh),
                doraIndicators = doraIndicatorTiles,
                uraDoraIndicators = uraDoraIndicatorTiles,
            )
        val liabilityEntries = RiichiPaoRules.liabilityEntries(paoLiabilityByWinner, player, settlement, ::seatPlayer)
        val basicScore = settlement.score
        val paoBreakdown =
            RiichiPaoRules.tsumoBreakdown(
                liabilityEntries = liabilityEntries,
                winnerIsDealer = tsumoPlayerIsDealer,
                basicScore = basicScore,
                others = seats.filter { it != player },
                dealer = dealer,
            )
        val payments = paoBreakdown.payments.toMutableList()
        val honbaPayer = RiichiPaoRules.honbaPayer(liabilityEntries, seatOrderFromDealer())
        if (honbaPayer != null && honbaScore > 0) {
            payments += SettlementPayment(honbaPayer.uuid, honbaScore, SettlementPaymentType.HONBA)
        } else if (honbaScore > 0) {
            seats.filter { it != player }.forEach { payer ->
                payments += SettlementPayment(payer.uuid, honbaScore / 3, SettlementPaymentType.HONBA)
            }
        }
        if (riichiPoolScore > 0) {
            payments += SettlementPayment("", riichiPoolScore, SettlementPaymentType.RIICHI_POOL)
        }
        val score = basicScore + honbaScore + riichiPoolScore
        scoreList += ScoreItem(player.displayName, player.uuid, player.points, score)
        player.points += score
        yakuSettlements += settlement.copy(paymentBreakdown = payments)
        seats.filter { it != player }.forEach {
            val basePayment = paoBreakdown.paymentTotals[it.uuid] ?: 0
            val honbaPayment =
                if (honbaPayer == null) {
                    honbaScore / 3
                } else if (it == honbaPayer) {
                    honbaScore
                } else {
                    0
                }
            val totalPayment = basePayment + honbaPayment
            scoreList += ScoreItem(it.displayName, it.uuid, it.points, -totalPayment)
            it.points -= totalPayment
        }
        lastResolution = RoundResolution("Tsumo", yakuSettlements, ScoreSettlement("Tsumo", scoreList))
        finishRound(player == dealer, true)
    }

    protected override fun resolveDraw(draw: ExhaustiveDraw) {
        currentDrawIsRinshan = false
        pendingAbortiveDraw = null
        if (draw == ExhaustiveDraw.NORMAL) {
            val nagashiPlayers = nagashiManganCandidates()
            if (nagashiPlayers.isNotEmpty()) {
                resolveNagashiMangan(nagashiPlayers)
                return
            }
        }
        val scoreList =
            buildList {
                if (draw != ExhaustiveDraw.NORMAL) {
                    seats.forEach {
                        add(ScoreItem(it.displayName, it.uuid, it.points, 0))
                    }
                } else {
                    val tenpaiCount = seats.count { it.isTenpaiForExhaustiveDraw(rule) }
                    if (tenpaiCount == 0 || tenpaiCount == seats.size) {
                        seats.forEach { add(ScoreItem(it.displayName, it.uuid, it.points, 0)) }
                    } else {
                        val notenCount = seats.size - tenpaiCount
                        val notenBappu = 3000 / notenCount
                        val bappuGet = 3000 / tenpaiCount
                        seats.forEach {
                            if (it.isTenpaiForExhaustiveDraw(rule)) {
                                add(ScoreItem(it.displayName, it.uuid, it.points, bappuGet))
                                it.points += bappuGet
                            } else {
                                add(ScoreItem(it.displayName, it.uuid, it.points, -notenBappu))
                                it.points -= notenBappu
                            }
                        }
                    }
                }
            }
        lastResolution = RoundResolution(draw.name, scoreSettlement = ScoreSettlement(draw.name, scoreList), draw = draw)
        val dealerRemaining = if (draw == ExhaustiveDraw.NORMAL) dealer.isTenpaiForExhaustiveDraw(rule) else true
        finishRound(dealerRemaining, clearRiichiSticks = false, incrementHonbaOnRotation = true)
    }

    private fun resolveNagashiMangan(winners: List<RiichiPlayerState>) {
        val yakuSettlements = mutableListOf<YakuSettlement>()
        val originalScores = seats.associateWith { it.points }
        val doraIndicatorTiles = doraIndicators.map { it.mahjongTile }
        val uraDoraIndicatorTiles = uraDoraIndicators.map { it.mahjongTile }

        winners.forEach { winner ->
            val settlement =
                YakuSettlement.nagashiMangan(
                    displayName = winner.displayName,
                    uuid = winner.uuid,
                    doraIndicators = doraIndicatorTiles,
                    uraDoraIndicators = uraDoraIndicatorTiles,
                    isDealer = winner == dealer,
                )
            val payments = mutableListOf<SettlementPayment>()
            seats.filter { it != winner }.forEach { other ->
                val basePayment =
                    if (winner == dealer || other == dealer) {
                        4000
                    } else {
                        2000
                    }
                payments += SettlementPayment(other.uuid, basePayment, SettlementPaymentType.TSUMO)
                other.points -= basePayment
            }
            yakuSettlements += settlement.copy(paymentBreakdown = payments)
            winner.points += settlement.score
        }

        val scoreList =
            seats.map { player ->
                val original = originalScores.getValue(player)
                ScoreItem(player.displayName, player.uuid, original, player.points - original)
            }
        lastResolution =
            RoundResolution(
                title = "NagashiMangan",
                yakuSettlements = yakuSettlements,
                scoreSettlement = ScoreSettlement("NagashiMangan", scoreList),
            )
        finishRound(dealer.isTenpaiForExhaustiveDraw(rule), clearRiichiSticks = false, incrementHonbaOnRotation = true)
    }

    protected override fun personalSituation(
        player: RiichiPlayerState,
        isTsumo: Boolean,
        isChankan: Boolean,
        isRinshanKaihoh: Boolean,
    ): PersonalSituation {
        val selfWindNumber = (seatIndex(player) - round.round + 4) % 4
        val jikaze = Wind.entries[selfWindNumber]
        val isIppatsu = player.isIppatsu(seats, discards)
        return PersonalSituation(
            isTsumo,
            isIppatsu,
            player.riichi,
            player.doubleRiichi,
            isChankan,
            isRinshanKaihoh,
            jikaze,
        )
    }

    protected override fun seatIndex(player: RiichiPlayerState): Int = seatIndexByUuid[player.uuid] ?: seats.indexOf(player)

    protected override fun seatOrderFromDealer(): List<RiichiPlayerState> = List(4) { seats[(round.round + it) % 4] }

    protected override fun seatOrderFrom(target: RiichiPlayerState): List<RiichiPlayerState> {
        val index = seatIndex(target)
        return List(4) { seats[(index + it) % 4] }
    }

    protected override fun claimTarget(
        claimer: RiichiPlayerState,
        discarder: RiichiPlayerState,
    ): ClaimTarget {
        val diff = (seatIndex(claimer) - seatIndex(discarder) + 4) % 4
        return when (diff) {
            1 -> ClaimTarget.LEFT
            2 -> ClaimTarget.ACROSS
            3 -> ClaimTarget.RIGHT
            else -> ClaimTarget.SELF
        }
    }

    protected override fun isSuukaikanAbort(): Boolean {
        if (kanCount < 4) {
            return false
        }
        return seats.count { player -> player.fuuroList.count { it.isKan } > 0 } > 1
    }

    private fun finishRound(
        dealerRemaining: Boolean,
        clearRiichiSticks: Boolean,
    ) {
        finishRound(dealerRemaining, clearRiichiSticks, incrementHonbaOnRotation = false)
    }

    private fun finishRound(
        dealerRemaining: Boolean,
        clearRiichiSticks: Boolean,
        incrementHonbaOnRotation: Boolean,
    ) {
        finishRound(dealerRemaining, clearRiichiSticks, incrementHonbaOnRotation, forceDealerContinuation = false)
    }

    private fun finishRound(
        dealerRemaining: Boolean,
        clearRiichiSticks: Boolean,
        incrementHonbaOnRotation: Boolean,
        forceDealerContinuation: Boolean,
    ) {
        started = false
        pendingReaction = null
        currentDrawIsRinshan = false
        currentDiscardIsAfterRinshan = false
        pendingAbortiveDraw = null
        if (clearRiichiSticks) {
            seats.forEach { it.sticks.removeIf { stick -> stick == ScoringStick.P1000 } }
        }
        if (seats.any { it.points < 0 }) {
            gameFinished = true
            awardRemainingRiichiDepositsToFirstPlace()
            return
        }

        if (!round.isAllLast(rule)) {
            if (dealerRemaining) {
                round.honba++
            } else {
                val nextHonba = if (incrementHonbaOnRotation) round.honba + 1 else 0
                round.nextRound()
                round.honba = nextHonba
            }
            return
        }

        val firstPlace = placementOrder().first()
        if (round.isExtension(rule) && firstPlace.points >= rule.minPointsToWin && !forceDealerContinuation) {
            gameFinished = true
            awardRemainingRiichiDepositsToFirstPlace()
            return
        }
        if (round.isExtension(rule) && forceDealerContinuation && dealerRemaining) {
            round.honba++
            return
        }
        if (dealerRemaining) {
            if (firstPlace == dealer && dealer.points >= rule.minPointsToWin) {
                gameFinished = true
            } else {
                round.honba++
            }
        } else {
            if (firstPlace.points >= rule.minPointsToWin) {
                gameFinished = true
            } else {
                val finalRound = rule.length.finalRound
                if (round.wind == finalRound.first && round.round == finalRound.second) {
                    gameFinished = true
                } else {
                    val nextHonba = if (incrementHonbaOnRotation) round.honba + 1 else 0
                    round.nextRound()
                    round.honba = nextHonba
                }
            }
        }
        if (gameFinished) {
            awardRemainingRiichiDepositsToFirstPlace()
        }
    }

    private fun awardRemainingRiichiDepositsToFirstPlace() {
        val riichiPoints = seats.sumOf { it.riichiStickAmount } * ScoringStick.P1000.point
        if (riichiPoints > 0) {
            val firstPlace = placementOrder().first()
            firstPlace.points += riichiPoints
            includeFinalRiichiPoolInResolution(firstPlace, riichiPoints)
        }
        seats.forEach { it.sticks.removeIf { stick -> stick == ScoringStick.P1000 } }
    }

    private fun includeFinalRiichiPoolInResolution(
        recipient: RiichiPlayerState,
        riichiPoints: Int,
    ) {
        val resolution = lastResolution ?: return
        val scoreSettlement = resolution.scoreSettlement ?: return
        val recipientScore = scoreSettlement.scoreList.find { it.stringUUID == recipient.uuid }
        val updatedScores =
            if (recipientScore == null) {
                scoreSettlement.scoreList +
                    ScoreItem(recipient.displayName, recipient.uuid, recipient.points - riichiPoints, riichiPoints)
            } else {
                scoreSettlement.scoreList.map { scoreItem ->
                    if (scoreItem.stringUUID == recipient.uuid) {
                        scoreItem.copy(scoreChange = scoreItem.scoreChange + riichiPoints)
                    } else {
                        scoreItem
                    }
                }
            }
        lastResolution = resolution.copy(scoreSettlement = scoreSettlement.copy(scoreList = updatedScores))
    }
}

class RiichiRoundEngine(
    players: List<RiichiPlayerState>,
    rule: MahjongRule = MahjongRule(),
) : RiichiRoundEngineSettlement(players, rule)

private class LiveWallBuffer : AbstractMutableList<TileInstance>() {
    private val tiles = ArrayList<TileInstance>()
    private var headIndex: Int = 0

    override val size: Int
        get() = tiles.size - headIndex

    override fun get(index: Int): TileInstance = tiles[resolveIndex(index)]

    override fun set(
        index: Int,
        element: TileInstance,
    ): TileInstance = tiles.set(resolveIndex(index), element)

    override fun add(
        index: Int,
        element: TileInstance,
    ) {
        tiles.add(resolveInsertIndex(index), element)
    }

    override fun removeAt(index: Int): TileInstance {
        val removed = tiles.removeAt(resolveIndex(index))
        if (tiles.isEmpty()) {
            headIndex = 0
        }
        compactIfNeeded()
        return removed
    }

    override fun clear() {
        tiles.clear()
        headIndex = 0
    }

    fun removeFirstLiveTile(): TileInstance {
        if (isEmpty()) {
            throw NoSuchElementException("Live wall is empty")
        }
        val tile = tiles[headIndex]
        headIndex++
        compactIfNeeded()
        return tile
    }

    fun removeLastLiveTile(): TileInstance {
        if (isEmpty()) {
            throw NoSuchElementException("Live wall is empty")
        }
        val tile = tiles.removeAt(tiles.lastIndex)
        if (tiles.isEmpty()) {
            headIndex = 0
        }
        compactIfNeeded()
        return tile
    }

    private fun resolveIndex(index: Int): Int {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for live wall size $size")
        }
        return headIndex + index
    }

    private fun resolveInsertIndex(index: Int): Int {
        if (index !in 0..size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for live wall size $size")
        }
        return headIndex + index
    }

    private fun compactIfNeeded() {
        if (headIndex == 0) {
            return
        }
        if (tiles.isEmpty()) {
            headIndex = 0
            return
        }
        if (headIndex >= 64 && headIndex * 2 >= tiles.size) {
            tiles.subList(0, headIndex).clear()
            headIndex = 0
        }
    }
}
