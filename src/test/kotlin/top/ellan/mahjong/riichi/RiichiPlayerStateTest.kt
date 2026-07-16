package top.ellan.mahjong.riichi

import mahjongutils.shanten.shanten
import top.ellan.mahjong.riichi.model.ClaimTarget
import top.ellan.mahjong.riichi.model.Fuuro
import top.ellan.mahjong.riichi.model.GeneralSituation
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.MeldType
import top.ellan.mahjong.riichi.model.PersonalSituation
import top.ellan.mahjong.riichi.model.TileInstance
import top.ellan.mahjong.riichi.model.Wind
import java.lang.reflect.Field
import java.util.NoSuchElementException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RiichiPlayerStateTest {
    private val probeTiles: List<mahjongutils.models.Tile> =
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

    @Test
    fun `best-only shanten probe switches strategy to primary full scan`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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
            )

        val originalCalculator = RiichiPlayerState.shantenCalculator
        var bestOnlyCalls = 0
        var fullCalls = 0
        try {
            RiichiPlayerState.shantenCalculator = { tiles, furo, bestShantenOnly ->
                if (bestShantenOnly) {
                    bestOnlyCalls++
                    throw java.util.NoSuchElementException("forced failure for regression test")
                }
                fullCalls++
                shanten(
                    tiles = tiles,
                    furo = furo,
                    bestShantenOnly = false,
                )
            }

            val suggestions = player.discardSuggestions()

            assertEquals("primary-full-scan", RiichiPlayerState.activeShantenStrategyName)
            assertTrue(suggestions.isNotEmpty())
            assertEquals(1, bestOnlyCalls)
            assertTrue(fullCalls > 1)
        } finally {
            RiichiPlayerState.shantenCalculator = originalCalculator
        }
    }

    @Test
    fun `runtime shanten failure promotes primary strategy to full scan`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
            )

        val runtimeHand = player.hands.map { it.mahjongTile.utilsTile }
        val originalCalculator = RiichiPlayerState.shantenCalculator
        var bestOnlyCalls = 0
        var fullCalls = 0
        try {
            RiichiPlayerState.shantenCalculator = { tiles, furo, bestShantenOnly ->
                if (tiles == runtimeHand && bestShantenOnly) {
                    bestOnlyCalls++
                    throw NoSuchElementException("forced runtime best-only failure")
                }
                if (!bestShantenOnly) {
                    fullCalls++
                }
                shanten(
                    tiles = tiles,
                    furo = furo,
                    bestShantenOnly = bestShantenOnly,
                )
            }

            val suggestions = player.discardSuggestions()

            assertTrue(suggestions.isNotEmpty())
            assertEquals(1, bestOnlyCalls)
            assertTrue(fullCalls >= 1)
            assertEquals("primary-full-scan", RiichiPlayerState.activeShantenStrategyName)
        } finally {
            RiichiPlayerState.shantenCalculator = originalCalculator
        }
    }

    @Test
    fun `runtime shanten failure promotes to stable util when primary full scan also fails`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
            )

        val runtimeHand = player.hands.map { it.mahjongTile.utilsTile }
        val originalCalculator = RiichiPlayerState.shantenCalculator
        var probeCalls = 0
        var runtimeCalls = 0
        try {
            RiichiPlayerState.shantenCalculator = { tiles, furo, bestShantenOnly ->
                if (tiles == probeTiles) {
                    probeCalls++
                    shanten(
                        tiles = tiles,
                        furo = furo,
                        bestShantenOnly = bestShantenOnly,
                    )
                } else if (tiles == runtimeHand) {
                    runtimeCalls++
                    throw NoSuchElementException("forced runtime failure for all primary strategies")
                } else {
                    shanten(
                        tiles = tiles,
                        furo = furo,
                        bestShantenOnly = bestShantenOnly,
                    )
                }
            }

            val suggestions = player.discardSuggestions()

            assertTrue(suggestions.isNotEmpty())
            assertTrue(probeCalls >= 1)
            assertTrue(runtimeCalls >= 2)
            assertEquals("stable-full-scan", RiichiPlayerState.activeShantenStrategyName)
        } finally {
            RiichiPlayerState.shantenCalculator = originalCalculator
        }
    }

    @Test
    fun `strategy switches to stable util when primary probe fails twice`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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
            )

        val originalCalculator = RiichiPlayerState.shantenCalculator
        var calculatorCalls = 0
        try {
            RiichiPlayerState.shantenCalculator = { _, _, _ ->
                calculatorCalls++
                throw java.util.NoSuchElementException("forced failure for stable util fallback test")
            }

            val suggestions = player.discardSuggestions()

            assertEquals("stable-full-scan", RiichiPlayerState.activeShantenStrategyName)
            assertTrue(suggestions.isNotEmpty())
            assertEquals(2, calculatorCalls)
        } finally {
            RiichiPlayerState.shantenCalculator = originalCalculator
        }
    }

    @Test
    fun `can win pre-check uses selected full-scan strategy`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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

        val originalCalculator = RiichiPlayerState.shantenCalculator
        var bestOnlyCalls = 0
        var fullCalls = 0
        try {
            RiichiPlayerState.shantenCalculator = { tiles, furo, bestShantenOnly ->
                if (bestShantenOnly) {
                    bestOnlyCalls++
                    throw java.util.NoSuchElementException("forced pre-check failure")
                }
                fullCalls++
                shanten(
                    tiles = tiles,
                    furo = furo,
                    bestShantenOnly = false,
                )
            }

            val canWin =
                player.canWin(
                    winningTile = MahjongTile.P6,
                    isWinningTileInHands = false,
                    rule = MahjongRule(),
                    generalSituation = defaultGeneralSituation(),
                    personalSituation = defaultPersonalSituation(),
                )

            assertEquals("primary-full-scan", RiichiPlayerState.activeShantenStrategyName)
            assertTrue(canWin)
            assertEquals(1, bestOnlyCalls)
            assertTrue(fullCalls >= 2)
        } finally {
            RiichiPlayerState.shantenCalculator = originalCalculator
        }
    }

    @Test
    fun `can win result is memoized for unchanged hand state`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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

        val originalCalculator = RiichiPlayerState.shantenCalculator
        var calculatorCalls = 0
        try {
            RiichiPlayerState.shantenCalculator = { tiles, furo, bestShantenOnly ->
                calculatorCalls++
                shanten(
                    tiles = tiles,
                    furo = furo,
                    bestShantenOnly = bestShantenOnly,
                )
            }

            val firstCanWin =
                player.canWin(
                    winningTile = MahjongTile.P6,
                    isWinningTileInHands = false,
                    rule = MahjongRule(),
                    generalSituation = defaultGeneralSituation(),
                    personalSituation = defaultPersonalSituation(),
                )
            val callsAfterFirstEvaluation = calculatorCalls
            val secondCanWin =
                player.canWin(
                    winningTile = MahjongTile.P6,
                    isWinningTileInHands = false,
                    rule = MahjongRule(),
                    generalSituation = defaultGeneralSituation(),
                    personalSituation = defaultPersonalSituation(),
                )

            assertTrue(firstCanWin)
            assertTrue(secondCanWin)
            assertTrue(callsAfterFirstEvaluation > 0)
            assertEquals(callsAfterFirstEvaluation, calculatorCalls)
        } finally {
            RiichiPlayerState.shantenCalculator = originalCalculator
        }
    }
}

class RiichiPlayerStateAnalysisCacheTest {
    @Test
    fun `invalid hand size does not throw during shanten checks`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
            )

        assertFalse(player.isTenpai)
        assertTrue(player.discardSuggestions().isEmpty())
    }

    @Test
    fun `majsoul exhaustive draw rejects only the sole four-in-hand tanki`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
            )

        assertFalse(player.isTenpai)
        assertFalse(player.isTenpaiForExhaustiveDraw(MahjongRule(riichiProfile = MahjongRule.RiichiProfile.MAJSOUL)))
        assertFalse(
            player.isTenpaiForExhaustiveDraw(
                MahjongRule(riichiProfile = MahjongRule.RiichiProfile.EARLY_KAN_DORA),
            ),
        )
    }

    @Test
    fun `riichi candidates reject a fifth-copy wait but keep a real empty wait`() {
        val impossible = RiichiPlayerState("Impossible", "impossible")
        impossible.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.WHITE_DRAGON,
            )
        assertFalse(impossible.tilePairsForRiichi.any { it.first == MahjongTile.WHITE_DRAGON })

        val emptyWait = RiichiPlayerState("Empty", "empty")
        emptyWait.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.WHITE_DRAGON,
            )
        assertTrue(emptyWait.tilePairsForRiichi.any { it.first == MahjongTile.WHITE_DRAGON })
    }

    @Test
    fun `tile pairs for riichi cache invalidates when hand changes`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.drawTile(TileInstance(mahjongTile = MahjongTile.M1))
        player.tilePairsForRiichi
        val cachedVersionBefore = cachedTilePairsVersion(player)
        assertEquals(analysisVersion(player), cachedVersionBefore)

        player.discardTile(MahjongTile.M1)

        assertTrue(analysisVersion(player) > cachedVersionBefore)

        player.tilePairsForRiichi

        assertEquals(analysisVersion(player), cachedTilePairsVersion(player))
    }

    @Test
    fun `reset round state clears cached hand analysis`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.drawTile(TileInstance(mahjongTile = MahjongTile.M1))
        player.riichi = true
        player.doubleRiichi = true
        player.tilePairsForRiichi
        val cachedVersionBefore = cachedTilePairsVersion(player)

        player.resetRoundState()

        assertTrue(analysisVersion(player) > cachedVersionBefore)
        assertTrue(player.hands.isEmpty())
        assertTrue(player.fuuroList.isEmpty())
        assertFalse(player.riichi)
        assertFalse(player.doubleRiichi)

        player.tilePairsForRiichi

        assertEquals(analysisVersion(player), cachedTilePairsVersion(player))
    }

    @Test
    fun `pon removes two matching hand tiles and creates open meld`() {
        val player = RiichiPlayerState("Alice", "alice")
        val target = RiichiPlayerState("Bob", "bob")
        val discard = TileInstance(mahjongTile = MahjongTile.M5)
        player.hands +=
            listOf(
                TileInstance(mahjongTile = MahjongTile.M5),
                TileInstance(mahjongTile = MahjongTile.M5_RED),
                TileInstance(mahjongTile = MahjongTile.P1),
            )
        target.discardedTilesForDisplay += discard

        player.pon(discard, ClaimTarget.RIGHT, target)

        assertEquals(1, player.hands.size)
        assertEquals(MahjongTile.P1, player.hands.single().mahjongTile)
        assertEquals(1, player.fuuroList.size)
        assertEquals(MeldType.PON, player.fuuroList.single().type)
        assertTrue(player.fuuroList.single().isOpen)
        assertFalse(target.discardedTilesForDisplay.contains(discard))
    }

    @Test
    fun `ankan removes four matching tiles and creates concealed kan`() {
        val player = RiichiPlayerState("Alice", "alice")
        val tile = TileInstance(mahjongTile = MahjongTile.EAST)
        player.hands +=
            listOf(
                tile,
                TileInstance(mahjongTile = MahjongTile.EAST),
                TileInstance(mahjongTile = MahjongTile.EAST),
                TileInstance(mahjongTile = MahjongTile.EAST),
            )

        player.ankan(tile)

        assertTrue(player.hands.isEmpty())
        assertEquals(1, player.fuuroList.size)
        assertEquals(MeldType.ANKAN, player.fuuroList.single().type)
        assertTrue(player.fuuroList.single().isKan)
        assertFalse(player.fuuroList.single().isOpen)
    }

    @Test
    fun `riichi ankan requires the freshly drawn tile to complete the quad`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.riichi = true
        player.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.WHITE_DRAGON,
            )
        player.lastDrawnTile = TileInstance(mahjongTile = MahjongTile.WHITE_DRAGON)

        assertTrue(player.tilesCanAnkan.isEmpty())
    }

    @Test
    fun `drawing winning tile refreshes riichi availability`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands += tiles(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3)
        player.tilePairsForRiichi
        val cachedVersionBefore = cachedTilePairsVersion(player)

        player.drawTile(TileInstance(mahjongTile = MahjongTile.M4))

        assertTrue(analysisVersion(player) > cachedVersionBefore)
        assertEquals(cachedVersionBefore, cachedTilePairsVersion(player))
    }

    @Test
    fun `drawing a tile clears temporary furiten`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.markTemporaryFuriten()

        player.drawTile(TileInstance(mahjongTile = MahjongTile.M1))

        assertFalse(player.temporaryFuriten)
    }

    @Test
    fun `drawing does not clear riichi furiten but round reset does`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.riichi = true
        player.markTemporaryFuriten()

        assertTrue(player.riichiFuriten)
        assertFalse(player.temporaryFuriten)
        player.drawTile(TileInstance(mahjongTile = MahjongTile.M1))
        assertTrue(player.riichiFuriten)

        player.resetRoundState()
        assertFalse(player.riichiFuriten)
    }

    @Test
    fun `discard suggestion cache invalidates when hand changes`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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
            )

        player.discardSuggestions()
        val cachedVersionBefore = cachedDiscardSuggestionsVersion(player)
        assertEquals(analysisVersion(player), cachedVersionBefore)

        player.discardTile(MahjongTile.M9)

        assertTrue(analysisVersion(player) > cachedVersionBefore)
        assertEquals(cachedVersionBefore, cachedDiscardSuggestionsVersion(player))
    }

    @Test
    fun `best discard suggestions mirror detailed discard suggestions`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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
            )

        val details = player.discardSuggestions()

        assertTrue(details.isNotEmpty())
        assertEquals(details.map { it.tile }, player.bestDiscardSuggestions())
        assertTrue(details.first().advanceTiles.isNotEmpty())
        assertTrue(details.first().advanceCount > 0)
    }

    @Test
    fun `furiten checks start from the actual last discard instance`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M3,
                MahjongTile.P4,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P5,
                MahjongTile.S6,
                MahjongTile.S6,
                MahjongTile.EAST,
            )
        val earlierSameKind = TileInstance(mahjongTile = MahjongTile.M9)
        val waitedTileFromEarlierTurn = TileInstance(mahjongTile = MahjongTile.EAST)
        val ownLastDiscard = TileInstance(mahjongTile = MahjongTile.M9)
        val currentWinningDiscard = TileInstance(mahjongTile = MahjongTile.EAST)
        player.discardedTiles += ownLastDiscard

        assertFalse(
            player.isFuriten(
                currentWinningDiscard,
                listOf(earlierSameKind, waitedTileFromEarlierTurn, ownLastDiscard, currentWinningDiscard),
            ),
        )
    }

    @Test
    fun `discarding any tile in a multi-sided wait makes every ron tile furiten`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.EAST,
            )
        val ownDiscard = TileInstance(mahjongTile = MahjongTile.M3)
        val currentWinningDiscard = TileInstance(mahjongTile = MahjongTile.M6)
        player.discardedTiles += ownDiscard

        assertTrue(player.isTenpai)
        assertTrue(player.isFuriten(currentWinningDiscard, listOf(ownDiscard, currentWinningDiscard)))
    }
}

class RiichiPlayerStateGameplayAndScoringTest {
    @Test
    fun `discarding a selected tile removes the exact tile instance`() {
        val player = RiichiPlayerState("Alice", "alice")
        val selected = TileInstance(mahjongTile = MahjongTile.M1)
        val otherSameKind = TileInstance(mahjongTile = MahjongTile.M1)
        player.hands += listOf(selected, otherSameKind, TileInstance(mahjongTile = MahjongTile.P5))

        val discarded = player.discardTile(selected)

        assertSame(selected, discarded)
        assertFalse(player.hands.contains(selected))
        assertTrue(player.hands.contains(otherSameKind))
    }

    @Test
    fun `available chii pairs come from mahjong utils furo analysis`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M3,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P3,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
            )

        val pairs = player.availableChiiPairs(TileInstance(mahjongTile = MahjongTile.M2))

        assertEquals(
            setOf(MahjongTile.M1 to MahjongTile.M3, MahjongTile.M3 to MahjongTile.M4),
            pairs.toSet(),
        )
    }

    @Test
    fun `closed tanyao hand can win`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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

        val canWin =
            player.canWin(
                winningTile = MahjongTile.P6,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(),
                personalSituation = defaultPersonalSituation(),
            )
        val settlement =
            player.calcYakuSettlementForWin(
                winningTile = MahjongTile.P6,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(),
                personalSituation = defaultPersonalSituation(),
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList(),
            )

        assertTrue(canWin)
        assertContains(settlement.yakuList, "PINFU")
        assertContains(settlement.yakuList, "TANYAO")
        assertEquals(2, settlement.han)
    }

    @Test
    fun `dora adds han when hand already has a yaku`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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

        val settlement =
            player.calcYakuSettlementForWin(
                winningTile = MahjongTile.P6,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(doraIndicators = listOf(MahjongTile.P5)),
                personalSituation = defaultPersonalSituation(),
                doraIndicators = listOf(MahjongTile.P5),
                uraDoraIndicators = emptyList(),
            )

        assertContains(settlement.yakuList, "PINFU")
        assertContains(settlement.yakuList, "TANYAO")
        assertEquals(3, settlement.yakuList.count { it == "DORA" })
        assertEquals(5, settlement.han)
    }

    @Test
    fun `identical dora indicators each count their matching tiles`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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
        val indicators = listOf(MahjongTile.P5, MahjongTile.P5)

        val settlement =
            player.calcYakuSettlementForWin(
                winningTile = MahjongTile.P6,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(doraIndicators = indicators),
                personalSituation = defaultPersonalSituation(),
                doraIndicators = indicators,
                uraDoraIndicators = emptyList(),
            )

        assertEquals(6, settlement.yakuList.count { it == "DORA" })
        assertEquals(8, settlement.han)
    }

    @Test
    fun `red five contributes to both han and awarded points`() {
        fun settlement(five: MahjongTile) =
            RiichiPlayerState("Alice", "alice")
                .apply {
                    hands +=
                        tiles(
                            MahjongTile.M2,
                            MahjongTile.M3,
                            MahjongTile.M4,
                            MahjongTile.M3,
                            MahjongTile.M4,
                            MahjongTile.M5,
                            MahjongTile.P4,
                            five,
                            MahjongTile.P6,
                            MahjongTile.S6,
                            MahjongTile.S7,
                            MahjongTile.S8,
                            MahjongTile.P6,
                        )
                }.calcYakuSettlementForWin(
                    winningTile = MahjongTile.P6,
                    isWinningTileInHands = false,
                    rule = MahjongRule(redFive = MahjongRule.RedFive.THREE),
                    generalSituation = defaultGeneralSituation(),
                    personalSituation = defaultPersonalSituation(),
                    doraIndicators = emptyList(),
                    uraDoraIndicators = emptyList(),
                )

        val normal = settlement(MahjongTile.P5)
        val red = settlement(MahjongTile.P5_RED)

        assertEquals(normal.han + 1, red.han)
        assertEquals(1, red.redFiveCount)
        assertTrue(red.score > normal.score)
    }

    @Test
    fun `ittsu loses one han after opening the hand`() {
        val closed = RiichiPlayerState("Closed", "closed")
        closed.hands +=
            tiles(
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
            )
        val closedSettlement =
            closed.calcYakuSettlementForWin(
                winningTile = MahjongTile.M9,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(),
                personalSituation = defaultPersonalSituation(),
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList(),
            )
        assertContains(closedSettlement.yakuList, "ITTSU")
        assertEquals(2, closedSettlement.han)

        val open = RiichiPlayerState("Open", "open")
        open.hands +=
            tiles(
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
            )
        open.fuuroList += openChii(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3)
        val openSettlement =
            open.calcYakuSettlementForWin(
                winningTile = MahjongTile.M9,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(),
                personalSituation = defaultPersonalSituation(),
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList(),
            )
        assertContains(openSettlement.yakuList, "ITTSU")
        assertEquals(1, openSettlement.han)
    }

    @Test
    fun `chinitsu loses one han after opening the hand`() {
        val closed = RiichiPlayerState("Closed", "closed")
        closed.hands +=
            tiles(
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
            )
        val closedSettlement =
            closed.calcYakuSettlementForWin(
                winningTile = MahjongTile.M7,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(),
                personalSituation = defaultPersonalSituation(),
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList(),
            )
        assertContains(closedSettlement.yakuList, "CHINITSU")
        assertEquals(6, closedSettlement.han)

        val open = RiichiPlayerState("Open", "open")
        open.hands +=
            tiles(
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
            )
        open.fuuroList += openPon(MahjongTile.M1)
        val openSettlement =
            open.calcYakuSettlementForWin(
                winningTile = MahjongTile.M7,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(),
                personalSituation = defaultPersonalSituation(),
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList(),
            )
        assertContains(openSettlement.yakuList, "CHINITSU")
        assertEquals(5, openSettlement.han)
    }

    @Test
    fun `dora alone does not make a hand winnable`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.EAST,
            )

        val canWin =
            player.canWin(
                winningTile = MahjongTile.EAST,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(doraIndicators = listOf(MahjongTile.NORTH)),
                personalSituation = defaultPersonalSituation(jikaze = Wind.WEST),
            )
        val settlement =
            player.calcYakuSettlementForWin(
                winningTile = MahjongTile.EAST,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation = defaultGeneralSituation(doraIndicators = listOf(MahjongTile.NORTH)),
                personalSituation = defaultPersonalSituation(jikaze = Wind.WEST),
                doraIndicators = listOf(MahjongTile.NORTH),
                uraDoraIndicators = emptyList(),
            )

        assertFalse(canWin)
        assertTrue(settlement.yakuList.isEmpty())
        assertEquals(0, settlement.han)
        assertEquals(0, settlement.score)
    }

    @Test
    fun `minimum han counts yaku only while score still includes dora aka and ura`() {
        val oneYakuWithDoraAndAka = RiichiPlayerState("Bonus", "bonus")
        oneYakuWithDoraAndAka.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.S6,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.RED_DRAGON,
                MahjongTile.RED_DRAGON,
                MahjongTile.RED_DRAGON,
                MahjongTile.M5_RED,
            )
        val doraGeneral = defaultGeneralSituation(doraIndicators = listOf(MahjongTile.M4))
        val twoHanRule = MahjongRule(minimumHan = MahjongRule.MinimumHan.TWO)
        val bonusSettlement =
            oneYakuWithDoraAndAka.calcYakuSettlementForWin(
                winningTile = MahjongTile.M5,
                isWinningTileInHands = false,
                rule = twoHanRule,
                generalSituation = doraGeneral,
                personalSituation = defaultPersonalSituation(),
                doraIndicators = doraGeneral.doraIndicators,
                uraDoraIndicators = emptyList(),
            )
        assertContains(bonusSettlement.yakuList, "CHUN")
        assertEquals(2, bonusSettlement.yakuList.count { it == "DORA" })
        assertEquals(1, bonusSettlement.redFiveCount)
        assertTrue(bonusSettlement.han >= 4)
        assertFalse(
            oneYakuWithDoraAndAka.canWin(
                winningTile = MahjongTile.M5,
                isWinningTileInHands = false,
                rule = twoHanRule,
                generalSituation = doraGeneral,
                personalSituation = defaultPersonalSituation(),
            ),
        )

        val riichiWithUraOnly = RiichiPlayerState("Ura", "ura")
        riichiWithUraOnly.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.EAST,
            )
        val uraGeneral = defaultGeneralSituation(uraDoraIndicators = listOf(MahjongTile.NORTH))
        val riichiSituation =
            PersonalSituation(
                isTsumo = false,
                isIppatsu = false,
                isRiichi = true,
                isDoubleRiichi = false,
                isChankan = false,
                isRinshanKaihoh = false,
                jikaze = Wind.WEST,
            )
        val uraSettlement =
            riichiWithUraOnly.calcYakuSettlementForWin(
                winningTile = MahjongTile.EAST,
                isWinningTileInHands = false,
                rule = twoHanRule,
                generalSituation = uraGeneral,
                personalSituation = riichiSituation,
                doraIndicators = emptyList(),
                uraDoraIndicators = uraGeneral.uraDoraIndicators,
            )
        assertContains(uraSettlement.yakuList, "REACH")
        assertEquals(2, uraSettlement.yakuList.count { it == "URADORA" })
        assertTrue(uraSettlement.han >= 3)
        assertFalse(
            riichiWithUraOnly.canWin(
                winningTile = MahjongTile.EAST,
                isWinningTileInHands = false,
                rule = twoHanRule,
                generalSituation = uraGeneral,
                personalSituation = riichiSituation,
            ),
        )

        val twoYaku = RiichiPlayerState("TwoYaku", "two-yaku")
        twoYaku.hands +=
            tiles(
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
        assertTrue(
            twoYaku.canWin(
                winningTile = MahjongTile.P6,
                isWinningTileInHands = false,
                rule = twoHanRule,
                generalSituation = defaultGeneralSituation(),
                personalSituation = defaultPersonalSituation(),
            ),
        )
    }

    @Test
    fun `rinshan kaihou never stacks with haitei`() {
        val player = RiichiPlayerState("Alice", "alice")
        player.hands +=
            tiles(
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
        val settlement =
            player.calcYakuSettlementForWin(
                winningTile = MahjongTile.P6,
                isWinningTileInHands = false,
                rule = MahjongRule(),
                generalSituation =
                    GeneralSituation(
                        isFirstRound = false,
                        isHoutei = true,
                        bakaze = Wind.SOUTH,
                        doraIndicators = emptyList(),
                        uraDoraIndicators = emptyList(),
                    ),
                personalSituation =
                    PersonalSituation(
                        isTsumo = true,
                        isIppatsu = false,
                        isRiichi = false,
                        isDoubleRiichi = false,
                        isChankan = false,
                        isRinshanKaihoh = true,
                        jikaze = Wind.WEST,
                    ),
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList(),
            )

        assertContains(settlement.yakuList, "RINSHAN_KAIHOU")
        assertFalse("HAITEI" in settlement.yakuList)
    }

    @Test
    fun `double riichi still enables ippatsu when no one calls`() {
        val player = RiichiPlayerState("Alice", "alice")
        val south = RiichiPlayerState("South", "south")
        val west = RiichiPlayerState("West", "west")
        val north = RiichiPlayerState("North", "north")
        val riichiDiscard = TileInstance(mahjongTile = MahjongTile.M1)
        val discards =
            listOf(
                riichiDiscard,
                TileInstance(mahjongTile = MahjongTile.P1),
                TileInstance(mahjongTile = MahjongTile.S1),
            )
        player.doubleRiichi = true
        player.riichiSengenTile = riichiDiscard

        assertTrue(player.isIppatsu(listOf(player, south, west, north), discards))
    }
}

private fun analysisVersion(player: RiichiPlayerState): Long = playerField("analysisStateVersion").getLong(player)

private fun cachedTilePairsVersion(player: RiichiPlayerState): Long = cacheVersion(player, "TILE_PAIRS_FOR_RIICHI")

private fun cachedDiscardSuggestionsVersion(player: RiichiPlayerState): Long = cacheVersion(player, "DISCARD_SUGGESTIONS")

private fun cacheVersion(
    player: RiichiPlayerState,
    cacheName: String,
): Long {
    @Suppress("UNCHECKED_CAST")
    val versions = playerField("cacheVersions").get(player) as Map<Any, Long>
    return versions[analysisCache(cacheName)] ?: -1L
}

private fun analysisCache(cacheName: String): Any {
    val cacheClass =
        generateSequence(RiichiPlayerState::class.java as Class<*>?) { type -> type.superclass }
            .flatMap { type -> type.declaredClasses.asSequence() }
            .firstOrNull { type -> type.simpleName == "AnalysisCache" }
            ?: error("Missing RiichiPlayerState AnalysisCache enum")
    return cacheClass.enumConstants.first { constant -> (constant as Enum<*>).name == cacheName }
}

private fun playerField(name: String): Field =
    generateSequence(RiichiPlayerState::class.java as Class<*>?) { type -> type.superclass }
        .firstNotNullOfOrNull { type ->
            runCatching { type.getDeclaredField(name) }.getOrNull()
        }?.apply { isAccessible = true }
        ?: error("Missing RiichiPlayerState field: $name")

private fun tiles(vararg tiles: MahjongTile): List<TileInstance> = tiles.map { TileInstance(mahjongTile = it) }

private fun openChii(
    first: MahjongTile,
    second: MahjongTile,
    third: MahjongTile,
): Fuuro {
    val claim = TileInstance(mahjongTile = first)
    return Fuuro(
        type = MeldType.CHII,
        tileInstances = listOf(claim, TileInstance(mahjongTile = second), TileInstance(mahjongTile = third)),
        claimTarget = ClaimTarget.RIGHT,
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

private fun defaultGeneralSituation(
    doraIndicators: List<MahjongTile> = emptyList(),
    uraDoraIndicators: List<MahjongTile> = emptyList(),
): GeneralSituation =
    GeneralSituation(
        isFirstRound = false,
        isHoutei = false,
        bakaze = Wind.SOUTH,
        doraIndicators = doraIndicators,
        uraDoraIndicators = uraDoraIndicators,
    )

private fun defaultPersonalSituation(jikaze: Wind = Wind.WEST): PersonalSituation =
    PersonalSituation(
        isTsumo = false,
        isIppatsu = false,
        isRiichi = false,
        isDoubleRiichi = false,
        isChankan = false,
        isRinshanKaihoh = false,
        jikaze = jikaze,
    )
