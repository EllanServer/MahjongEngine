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

internal object RiichiShantenRuntime {
    internal val LOGGER: Logger = Logger.getLogger(RiichiPlayerState::class.java.name)

    private data class StableUtilShantenInvoker(
        val internalArgsConstructor: Constructor<*>,
        val shantenMethod: Method,
    )

    internal data class ShantenStrategy(
        val name: String,
        val evaluate: (
            tiles: List<Tile>,
            furo: List<mahjongutils.models.Furo>,
            bestShantenOnly: Boolean,
        ) -> UnionShantenResult,
    )

    private val stableUtilShantenInvoker: StableUtilShantenInvoker? =
        runCatching {
            val internalArgsClass = Class.forName("mahjongutils.shanten.InternalShantenArgs")
            val calcContextClass = Class.forName("mahjongutils.CalcContext")
            val shantenKtClass = Class.forName("mahjongutils.shanten.ShantenKt")
            val internalArgsConstructor =
                internalArgsClass
                    .getDeclaredConstructor(
                        List::class.java,
                        List::class.java,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
            val shantenMethod =
                shantenKtClass
                    .getDeclaredMethod(
                        "shanten",
                        calcContextClass,
                        internalArgsClass,
                    ).apply { isAccessible = true }
            StableUtilShantenInvoker(
                internalArgsConstructor = internalArgsConstructor,
                shantenMethod = shantenMethod,
            )
        }.getOrElse { error ->
            LOGGER.log(Level.WARNING, "Unable to initialize util stable shanten invoker", error)
            null
        }

    private val strategyProbeTiles: List<Tile> =
        listOf(
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
            MahjongTile.M9,
        ).map { it.utilsTile }
    private val strategyProbeFuro: List<mahjongutils.models.Furo> = emptyList()

    internal var shantenCalculator: (
        tiles: List<Tile>,
        furo: List<mahjongutils.models.Furo>,
        bestShantenOnly: Boolean,
    ) -> UnionShantenResult = { tiles, furo, bestOnly ->
        shanten(
            tiles = tiles,
            furo = furo,
            bestShantenOnly = bestOnly,
        )
    }
        @Synchronized
        set(value) {
            field = value
            shantenStrategy = resolveShantenStrategy(value)
        }

    @Volatile
    internal var shantenStrategy: ShantenStrategy = resolveShantenStrategy(shantenCalculator)

    internal val activeShantenStrategyName: String
        get() = shantenStrategy.name

    private fun primaryFullScanStrategy(
        calculator: (
            tiles: List<Tile>,
            furo: List<mahjongutils.models.Furo>,
            bestShantenOnly: Boolean,
        ) -> UnionShantenResult,
    ): ShantenStrategy =
        ShantenStrategy("primary-full-scan") { tiles, furo, _ ->
            calculator(tiles, furo, false)
        }

    private fun stableFullScanStrategy(invoker: StableUtilShantenInvoker): ShantenStrategy =
        ShantenStrategy("stable-full-scan") { tiles, furo, _ ->
            invokeStableShanten(invoker, tiles, furo, false)
        }

    private fun resolveShantenStrategy(
        calculator: (
            tiles: List<Tile>,
            furo: List<mahjongutils.models.Furo>,
            bestShantenOnly: Boolean,
        ) -> UnionShantenResult,
    ): ShantenStrategy {
        val primaryBest = runCatching { calculator(strategyProbeTiles, strategyProbeFuro, true) }
        if (primaryBest.isSuccess) {
            return ShantenStrategy("primary-best-only") { tiles, furo, bestOnly ->
                calculator(tiles, furo, bestOnly)
            }
        }
        val primaryBestError = primaryBest.exceptionOrNull()
        if (primaryBestError != null && isNoSuchElementFailure(primaryBestError)) {
            val primaryFull = runCatching { calculator(strategyProbeTiles, strategyProbeFuro, false) }
            if (primaryFull.isSuccess) {
                LOGGER.log(Level.INFO, "Shanten strategy selected: primary-full-scan")
                return primaryFullScanStrategy(calculator)
            }
            val primaryFullError = primaryFull.exceptionOrNull()
            if (primaryFullError != null && isNoSuchElementFailure(primaryFullError)) {
                val stableInvoker = stableUtilShantenInvoker
                if (stableInvoker != null) {
                    val stableFull =
                        runCatching {
                            invokeStableShanten(stableInvoker, strategyProbeTiles, strategyProbeFuro, false)
                        }
                    if (stableFull.isSuccess) {
                        LOGGER.log(Level.INFO, "Shanten strategy selected: stable-full-scan")
                        return stableFullScanStrategy(stableInvoker)
                    }
                    val stableError = stableFull.exceptionOrNull()
                    if (stableError != null) {
                        LOGGER.log(shantenFailureLogLevel(stableError), "Shanten stable strategy probe failed", stableError)
                    }
                }
            }
            if (primaryFullError != null) {
                LOGGER.log(shantenFailureLogLevel(primaryFullError), "Shanten full-scan strategy probe failed", primaryFullError)
            }
        }
        if (primaryBestError != null) {
            LOGGER.log(shantenFailureLogLevel(primaryBestError), "Shanten best-only strategy probe failed", primaryBestError)
        }
        return ShantenStrategy("primary-best-only") { tiles, furo, bestOnly ->
            calculator(tiles, furo, bestOnly)
        }
    }

    private fun invokeStableShanten(
        invoker: StableUtilShantenInvoker,
        tiles: List<Tile>,
        furo: List<mahjongutils.models.Furo>,
        bestShantenOnly: Boolean,
    ): UnionShantenResult {
        val args =
            invoker.internalArgsConstructor.newInstance(
                tiles,
                furo,
                true,
                false,
                bestShantenOnly,
                true,
                true,
            )
        val result = invoker.shantenMethod.invoke(null, CalcContext(), args)
        return result as? UnionShantenResult
            ?: throw IllegalStateException(
                "mahjongutils internal shanten returned unexpected result type: ${result?.javaClass?.name}",
            )
    }

    internal fun shantenFailureLogLevel(error: Throwable): Level = if (error is IllegalArgumentException) Level.FINE else Level.WARNING

    internal fun fallbackShantenStrategies(
        failedStrategy: ShantenStrategy,
        calculator: (
            tiles: List<Tile>,
            furo: List<mahjongutils.models.Furo>,
            bestShantenOnly: Boolean,
        ) -> UnionShantenResult,
    ): List<ShantenStrategy> =
        buildList {
            if (failedStrategy.name != "primary-full-scan") {
                add(primaryFullScanStrategy(calculator))
            }
            val stableInvoker = stableUtilShantenInvoker
            if (stableInvoker != null && failedStrategy.name != "stable-full-scan") {
                add(stableFullScanStrategy(stableInvoker))
            }
        }

    internal fun isNoSuchElementFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            if (current == null) {
                return false
            }
            if (current is kotlin.NoSuchElementException || current is java.util.NoSuchElementException) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
