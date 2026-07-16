package top.ellan.mahjong.riichi.scoring

import top.ellan.mahjong.riichi.RiichiPlayerState
import top.ellan.mahjong.riichi.model.DoubleYakuman
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.SettlementPayment
import top.ellan.mahjong.riichi.model.SettlementPaymentType
import top.ellan.mahjong.riichi.model.TileInstance
import top.ellan.mahjong.riichi.model.YakuSettlement

data class PaoLiabilityEntry(
    val key: String,
    val liablePlayer: RiichiPlayerState,
    val yakumanUnits: Int,
)

data class PaoRonBreakdown(
    val targetBase: Int,
    val liabilityPayments: Map<RiichiPlayerState, Int>,
    val liabilityNotes: Map<String, String>,
    val honbaPayer: RiichiPlayerState?,
)

data class PaoTsumoBreakdown(
    val payments: List<SettlementPayment>,
    val paymentTotals: Map<String, Int>,
)

object RiichiPaoRules {
    private val dragonTiles: Set<MahjongTile> =
        setOf(MahjongTile.WHITE_DRAGON, MahjongTile.GREEN_DRAGON, MahjongTile.RED_DRAGON)

    private val windTiles: Set<MahjongTile> =
        setOf(MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST, MahjongTile.NORTH)

    fun registerLiability(
        liabilitiesByWinner: MutableMap<String, MutableMap<String, String>>,
        winner: RiichiPlayerState,
        discarder: RiichiPlayerState,
        tile: TileInstance,
    ) {
        val baseTile = tile.mahjongTile.baseTile
        val liability = liabilitiesByWinner.getOrPut(winner.uuid) { linkedMapOf() }
        if (baseTile in dragonTiles && "DAISANGEN" !in liability) {
            val dragonGroups =
                winner.fuuroList
                    .asSequence()
                    .filter { it.isOpen && (it.type.name == "PON" || it.type.name == "MINKAN" || it.type.name == "KAKAN") }
                    .map { it.claimTile.mahjongTile.baseTile }
                    .filter { it in dragonTiles }
                    .distinct()
                    .count()
            if (dragonGroups >= 3) {
                liability["DAISANGEN"] = discarder.uuid
            }
        }
        if (baseTile in windTiles && "DAISUUSHI" !in liability) {
            val windGroups =
                winner.fuuroList
                    .asSequence()
                    .filter { it.isOpen && (it.type.name == "PON" || it.type.name == "MINKAN" || it.type.name == "KAKAN") }
                    .map { it.claimTile.mahjongTile.baseTile }
                    .filter { it in windTiles }
                    .distinct()
                    .count()
            if (windGroups >= 4) {
                liability["DAISUUSHI"] = discarder.uuid
            }
        }
    }

    fun liabilityEntries(
        liabilitiesByWinner: Map<String, Map<String, String>>,
        winner: RiichiPlayerState,
        settlement: YakuSettlement,
        seatResolver: (String) -> RiichiPlayerState?,
    ): List<PaoLiabilityEntry> {
        val raw = liabilitiesByWinner[winner.uuid].orEmpty()
        if (raw.isEmpty()) {
            return emptyList()
        }
        val entries = mutableListOf<PaoLiabilityEntry>()
        if ("DAISANGEN" in settlement.yakumanList) {
            raw["DAISANGEN"]?.let { liableUuid ->
                seatResolver(liableUuid)?.let { entries += PaoLiabilityEntry("DAISANGEN", it, 1) }
            }
        }
        if (settlement.doubleYakumanList.contains(DoubleYakuman.DAISUSHI)) {
            raw["DAISUUSHI"]?.let { liableUuid ->
                seatResolver(liableUuid)?.let { entries += PaoLiabilityEntry("DAISUUSHI", it, 2) }
            }
        }
        return entries
    }

    fun ronBreakdown(
        liabilityEntries: List<PaoLiabilityEntry>,
        winnerIsDealer: Boolean,
        basicScore: Int,
        target: RiichiPlayerState,
        seatOrderFromDealer: List<RiichiPlayerState>,
    ): PaoRonBreakdown {
        if (liabilityEntries.isEmpty()) {
            return PaoRonBreakdown(
                targetBase = basicScore,
                liabilityPayments = emptyMap(),
                liabilityNotes = emptyMap(),
                honbaPayer = target,
            )
        }
        val unitValue = yakumanUnitValue(winnerIsDealer, basicScore, liabilityEntries)
        val liabilityPortions = linkedMapOf<RiichiPlayerState, Int>()
        val notes = linkedMapOf<String, String>()
        liabilityEntries.forEach { entry ->
            val amount = unitValue * entry.yakumanUnits / 2
            liabilityPortions.merge(entry.liablePlayer, amount, Int::plus)
            notes.merge(entry.liablePlayer.uuid, entry.key, ::mergeLiabilityNotes)
        }
        val liabilityTotal = unitValue * liabilityEntries.sumOf { it.yakumanUnits }
        return PaoRonBreakdown(
            targetBase = (basicScore - liabilityTotal) + (liabilityTotal / 2),
            liabilityPayments = liabilityPortions,
            liabilityNotes = notes,
            honbaPayer = honbaPayer(liabilityEntries, seatOrderFromDealer) ?: target,
        )
    }

    fun tsumoBreakdown(
        liabilityEntries: List<PaoLiabilityEntry>,
        winnerIsDealer: Boolean,
        basicScore: Int,
        others: List<RiichiPlayerState>,
        dealer: RiichiPlayerState,
    ): PaoTsumoBreakdown {
        if (liabilityEntries.isEmpty()) {
            val payments =
                others.map { other ->
                    SettlementPayment(
                        other.uuid,
                        normalTsumoShare(winnerIsDealer, other == dealer, basicScore),
                        SettlementPaymentType.TSUMO,
                    )
                }
            return PaoTsumoBreakdown(
                payments = payments,
                paymentTotals = payments.associate { it.payerUuid to it.amount },
            )
        }
        val unitValue = yakumanUnitValue(winnerIsDealer, basicScore, liabilityEntries)
        val payments = mutableListOf<SettlementPayment>()
        val totals = others.associate { it.uuid to 0 }.toMutableMap()
        val liabilityTotals = linkedMapOf<String, Int>()
        val liabilityNotes = linkedMapOf<String, String>()
        liabilityEntries.forEach { entry ->
            liabilityTotals.merge(entry.liablePlayer.uuid, unitValue * entry.yakumanUnits, Int::plus)
            liabilityNotes.merge(entry.liablePlayer.uuid, entry.key, ::mergeLiabilityNotes)
        }
        liabilityTotals.forEach { (liableUuid, amount) ->
            if (amount <= 0) {
                return@forEach
            }
            payments += SettlementPayment(liableUuid, amount, SettlementPaymentType.PAO, liabilityNotes[liableUuid].orEmpty())
            totals.merge(liableUuid, amount, Int::plus)
        }
        val nonLiableUnits = totalYakumanUnits(liabilityEntries, basicScore, winnerIsDealer) - liabilityEntries.sumOf { it.yakumanUnits }
        if (nonLiableUnits > 0) {
            others.forEach { other ->
                val share = normalTsumoShare(winnerIsDealer, other == dealer, unitValue * nonLiableUnits)
                if (share > 0) {
                    payments += SettlementPayment(other.uuid, share, SettlementPaymentType.TSUMO)
                    totals.merge(other.uuid, share, Int::plus)
                }
            }
        }
        return PaoTsumoBreakdown(payments, totals)
    }

    fun honbaPayer(
        liabilityEntries: List<PaoLiabilityEntry>,
        seatOrder: List<RiichiPlayerState>,
    ): RiichiPlayerState? =
        liabilityEntries
            .sortedWith(compareByDescending<PaoLiabilityEntry> { it.yakumanUnits }.thenBy { seatOrder.indexOf(it.liablePlayer) })
            .firstOrNull()
            ?.liablePlayer

    private fun yakumanUnitValue(
        winnerIsDealer: Boolean,
        basicScore: Int,
        liabilityEntries: List<PaoLiabilityEntry>,
    ): Int {
        val totalUnits = totalYakumanUnits(liabilityEntries, basicScore, winnerIsDealer)
        return if (totalUnits <= 0) basicScore else basicScore / totalUnits
    }

    private fun totalYakumanUnits(
        liabilityEntries: List<PaoLiabilityEntry>,
        basicScore: Int,
        winnerIsDealer: Boolean,
    ): Int {
        val singleValue = if (winnerIsDealer) 48000 else 32000
        return if (basicScore % singleValue == 0) {
            basicScore / singleValue
        } else {
            liabilityEntries.sumOf { it.yakumanUnits }.coerceAtLeast(1)
        }
    }

    private fun normalTsumoShare(
        winnerIsDealer: Boolean,
        payerIsDealer: Boolean,
        totalValue: Int,
    ): Int =
        if (winnerIsDealer) {
            totalValue / 3
        } else if (payerIsDealer) {
            totalValue / 2
        } else {
            totalValue / 4
        }

    private fun mergeLiabilityNotes(
        left: String,
        right: String,
    ): String = if (left == right || right.isBlank()) left else "$left/$right"
}
