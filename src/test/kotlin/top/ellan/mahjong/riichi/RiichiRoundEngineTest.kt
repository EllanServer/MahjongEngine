package top.ellan.mahjong.riichi

import top.ellan.mahjong.riichi.model.ClaimTarget
import top.ellan.mahjong.riichi.model.DoubleYakuman
import top.ellan.mahjong.riichi.model.ExhaustiveDraw
import top.ellan.mahjong.riichi.model.Fuuro
import top.ellan.mahjong.riichi.model.MahjongRound
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.MeldType
import top.ellan.mahjong.riichi.model.OpeningDiceRoll
import top.ellan.mahjong.riichi.model.ScoringStick
import top.ellan.mahjong.riichi.model.SettlementPaymentType
import top.ellan.mahjong.riichi.model.TileInstance
import top.ellan.mahjong.riichi.model.Wind
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RiichiRoundEngineTest {
    @Test
    fun `start round deals dealer 14 and others 13`() {
        val players =
            listOf(
                RiichiPlayerState("A", "a"),
                RiichiPlayerState("B", "b"),
                RiichiPlayerState("C", "c"),
                RiichiPlayerState("D", "d"),
            )
        val engine = RiichiRoundEngine(players, MahjongRule())

        engine.startRound()

        assertTrue(engine.started)
        assertEquals(4, engine.seats.size)
        assertEquals(1, engine.doraIndicators.size)
        assertEquals(14, engine.currentPlayer.hands.size)
        assertEquals(3, engine.seats.count { it.hands.size == 13 })
        assertEquals(1, engine.seats.count { it.hands.size == 14 })
        assertFalse(engine.gameFinished)
    }

    @Test
    fun `dealer opening tsumo chooses the highest scoring tile independent of deal order`() {
        val kokushiWithExtraOne =
            listOf(
                MahjongTile.M1,
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
            )

        fun settle(order: List<MahjongTile>) =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            ).let { engine ->
                engine.startRound()
                val east = engine.currentPlayer
                east.resetRoundState()
                order.forEach { tile -> east.drawTile(TileInstance(mahjongTile = tile)) }

                assertTrue(engine.canDeclareTsumo(east.uuid))
                assertTrue(engine.tryTsumo(east.uuid))
                engine.lastResolution!!.yakuSettlements.single()
            }

        val redLast = settle(kokushiWithExtraOne)
        val greenLast = settle(kokushiWithExtraOne.dropLast(2) + MahjongTile.RED_DRAGON + MahjongTile.GREEN_DRAGON)

        listOf(redLast, greenLast).forEach { settlement ->
            assertEquals(MahjongTile.M1, settlement.winningTile)
            assertTrue(DoubleYakuman.KOKUSHIMUSO_JUSANMENMACHI in settlement.doubleYakumanList)
        }
        assertEquals(redLast.score, greenLast.score)
    }

    @Test
    fun `engine initializes starting points for all seats`() {
        val rule = MahjongRule(startingPoints = 32000)
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("A", "a"),
                    RiichiPlayerState("B", "b"),
                    RiichiPlayerState("C", "c"),
                    RiichiPlayerState("D", "d"),
                ),
                rule,
            )

        assertTrue(engine.seats.all { it.points == 32000 })
    }

    @Test
    fun `engine uses pending opening dice roll when provided`() {
        val players =
            listOf(
                RiichiPlayerState("A", "a"),
                RiichiPlayerState("B", "b"),
                RiichiPlayerState("C", "c"),
                RiichiPlayerState("D", "d"),
            )
        val engine = RiichiRoundEngine(players, MahjongRule())
        engine.setPendingDiceRoll(OpeningDiceRoll(3, 4))

        engine.startRound()

        assertEquals(7, engine.dicePoints)
    }

    @Test
    fun `riichi wall opening follows every dealer and valid dice total`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("A", "a"),
                    RiichiPlayerState("B", "b"),
                    RiichiPlayerState("C", "c"),
                    RiichiPlayerState("D", "d"),
                ),
                MahjongRule(),
            )

        for (dealerIndex in 0..3) {
            for (dicePoints in 2..12) {
                val openDoorIndex = Math.floorMod(dealerIndex + dicePoints - 1, 4)
                val expected = Math.floorMod(openDoorIndex * 34 + 2 * dicePoints, 136)
                assertEquals(
                    expected,
                    engine.wallBreakTileIndex(dicePoints, dealerIndex),
                    "dealerIndex=$dealerIndex, dicePoints=$dicePoints",
                )
            }
        }
    }

    @Test
    fun `dora indicators stay empty before dead wall is assigned`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("A", "a"),
                    RiichiPlayerState("B", "b"),
                    RiichiPlayerState("C", "c"),
                    RiichiPlayerState("D", "d"),
                ),
                MahjongRule(),
            )

        assertTrue(engine.doraIndicators.isEmpty())
        assertTrue(engine.uraDoraIndicators.isEmpty())
    }

    @Test
    fun `houtei only applies when live wall is exhausted`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("A", "a"),
                    RiichiPlayerState("B", "b"),
                    RiichiPlayerState("C", "c"),
                    RiichiPlayerState("D", "d"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        engine.wall.clear()
        engine.wall += tiles(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3)

        assertFalse(engine.isHoutei)

        engine.wall.clear()
        assertTrue(engine.isHoutei)
    }

    @Test
    fun `engine preserves input seat order`() {
        val players =
            listOf(
                RiichiPlayerState("East", "east"),
                RiichiPlayerState("South", "south"),
                RiichiPlayerState("West", "west"),
                RiichiPlayerState("North", "north"),
            )

        val engine = RiichiRoundEngine(players, MahjongRule())

        assertEquals(listOf("east", "south", "west", "north"), engine.seats.map { it.uuid })
    }

    @Test
    fun `normal draw with all players tenpai does not divide by zero`() {
        val players =
            listOf(
                RiichiPlayerState("A", "a"),
                RiichiPlayerState("B", "b"),
                RiichiPlayerState("C", "c"),
                RiichiPlayerState("D", "d"),
            )
        val engine = RiichiRoundEngine(players, MahjongRule())

        engine.startRound()
        val tenpaiHand =
            listOf(
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
        engine.seats.forEach { player ->
            player.hands.clear()
            tenpaiHand.forEach { tile ->
                player.hands += TileInstance(mahjongTile = tile)
            }
            if (player == engine.currentPlayer) {
                player.hands += TileInstance(mahjongTile = MahjongTile.WHITE_DRAGON)
            }
        }
        engine.wall.clear()

        val result = engine.discard(engine.currentPlayer.uuid, engine.currentPlayer.hands.lastIndex)

        assertTrue(result)
        val scoreChanges =
            engine.lastResolution
                ?.scoreSettlement
                ?.scoreList
                ?.map { it.scoreChange }
        if (scoreChanges != null) {
            assertEquals(0, scoreChanges.sum())
        }
    }

    @Test
    fun `discard uses the exact selected tile instance`() {
        val players =
            listOf(
                RiichiPlayerState("A", "a"),
                RiichiPlayerState("B", "b"),
                RiichiPlayerState("C", "c"),
                RiichiPlayerState("D", "d"),
            )
        val engine = RiichiRoundEngine(players, MahjongRule())

        engine.startRound()

        val player = engine.currentPlayer
        player.resetRoundState()
        val selected = TileInstance(mahjongTile = MahjongTile.M1)
        val otherSameKind = TileInstance(mahjongTile = MahjongTile.M1)
        player.hands += listOf(selected, otherSameKind, TileInstance(mahjongTile = MahjongTile.P5))

        assertTrue(engine.discard(player.uuid, 0))
        assertSame(selected, engine.discards.last())
        assertFalse(player.hands.contains(selected))
        assertTrue(player.hands.contains(otherSameKind))
    }

    @Test
    fun `turn advances east south west north after a discard with no reactions`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        south.resetRoundState()
        west.resetRoundState()
        north.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M9,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M4,
                MahjongTile.M7,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
            )
        west.hands +=
            tiles(
                MahjongTile.M2,
                MahjongTile.M5,
                MahjongTile.M8,
                MahjongTile.P2,
                MahjongTile.P5,
                MahjongTile.P8,
                MahjongTile.S2,
                MahjongTile.S5,
                MahjongTile.S8,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.WHITE_DRAGON,
            )
        north.hands +=
            tiles(
                MahjongTile.M3,
                MahjongTile.M6,
                MahjongTile.M9,
                MahjongTile.P3,
                MahjongTile.P6,
                MahjongTile.P9,
                MahjongTile.S3,
                MahjongTile.S6,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
            )
        engine.wall.clear()
        engine.wall += TileInstance(mahjongTile = MahjongTile.M1)

        assertTrue(engine.discard(east.uuid, 0))
        assertEquals("south", engine.currentPlayer.uuid)
        assertEquals(14, south.hands.size)
    }

    @Test
    fun `only the next player may chii a discard in east south west north flow`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        south.resetRoundState()
        west.resetRoundState()
        north.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M2,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
            )
        west.hands +=
            tiles(
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
            )
        north.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M3,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.GREEN_DRAGON,
            )

        assertTrue(engine.discard(east.uuid, 0))
        assertEquals(listOf(MahjongTile.M1 to MahjongTile.M3), engine.availableReactions(south.uuid)?.chiiPairs)
        assertEquals(null, engine.availableReactions(west.uuid))
        assertEquals(null, engine.availableReactions(north.uuid))
    }
}

class RiichiRoundEngineCallAndRiichiTest {
    @Test
    fun `riichi declaration requires at least four live-wall tiles`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(riichiProfile = MahjongRule.RiichiProfile.MAJSOUL),
            )
        engine.startRound()
        val east = engine.currentPlayer
        east.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M9,
            )
        val riichiTile = east.tilePairsForRiichi.firstOrNull()?.first
        assertTrue(riichiTile != null)
        val riichiIndex = east.hands.indexOfFirst { it.mahjongTile == riichiTile }
        assertTrue(riichiIndex >= 0)

        engine.wall.clear()
        engine.wall += tiles(MahjongTile.M7, MahjongTile.P7, MahjongTile.S7)
        assertFalse(engine.declareRiichi(east.uuid, riichiIndex))

        engine.wall += TileInstance(mahjongTile = MahjongTile.EAST)
        assertTrue(engine.declareRiichi(east.uuid, riichiIndex))
    }

    @Test
    fun `riichi player can only discard the freshly drawn tile`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M9,
            )
        val riichiTile = east.tilePairsForRiichi.firstOrNull()?.first
        assertTrue(riichiTile != null)
        val riichiIndex = east.hands.indexOfFirst { it.mahjongTile == riichiTile }
        assertTrue(riichiIndex >= 0)

        assertTrue(engine.declareRiichi(east.uuid, riichiIndex))
        resolveAllPendingWithSkip(engine)
        assertEquals(south.uuid, engine.currentPlayer.uuid)

        assertTrue(engine.discard(south.uuid, 0))
        resolveAllPendingWithSkip(engine)
        assertTrue(engine.discard(west.uuid, 0))
        resolveAllPendingWithSkip(engine)
        assertTrue(engine.discard(north.uuid, 0))
        resolveAllPendingWithSkip(engine)
        assertEquals(east.uuid, engine.currentPlayer.uuid)

        val drawnId = east.lastDrawnTile?.id
        assertTrue(drawnId != null)
        val nonDrawnIndex = east.hands.indexOfFirst { it.id != drawnId }
        assertTrue(nonDrawnIndex >= 0)
        assertFalse(engine.discard(east.uuid, nonDrawnIndex))

        val drawnIndex = east.hands.indexOfFirst { it.id == drawnId }
        assertTrue(drawnIndex >= 0)
        assertTrue(engine.discard(east.uuid, drawnIndex))
    }

    @Test
    fun `chii caller does not draw and must discard next`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        south.resetRoundState()
        west.resetRoundState()
        north.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M2,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
            )
        west.hands +=
            tiles(
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
            )
        north.hands +=
            tiles(
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.GREEN_DRAGON,
            )
        engine.wall.clear()
        engine.wall += TileInstance(mahjongTile = MahjongTile.P9)

        assertTrue(engine.discard(east.uuid, 0))
        val wallSizeBeforeChii = engine.wall.size
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.CHII, MahjongTile.M1 to MahjongTile.M3)))
        assertEquals("south", engine.currentPlayer.uuid)
        assertEquals(11, south.hands.size)
        assertEquals(wallSizeBeforeChii, engine.wall.size)
        assertEquals(MeldType.CHII, south.fuuroList.last().type)
        assertEquals(ClaimTarget.LEFT, south.fuuroList.last().claimTarget)
        assertFalse(engine.discard(west.uuid, 0))
    }

    @Test
    fun `chii forbids immediate same-tile and forward suji kuikae then clears after a legal discard`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        engine.seats.forEach { it.resetRoundState() }
        east.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M9,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
            )

        assertTrue(engine.discard(east.uuid, 0))
        val chiiPair = MahjongTile.M2 to MahjongTile.M3
        assertTrue(chiiPair in engine.availableReactions(south.uuid)!!.chiiPairs)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.CHII, chiiPair)))

        val sameTileIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.M1 }
        val sujiKuikaeIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.M4 }
        val legalIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.M9 }
        assertTrue(sameTileIndex >= 0)
        assertTrue(sujiKuikaeIndex >= 0)
        assertTrue(legalIndex >= 0)
        assertFalse(engine.discard(south.uuid, sameTileIndex))
        assertFalse(engine.discard(south.uuid, sujiKuikaeIndex))
        assertTrue(engine.discard(south.uuid, legalIndex))
        resolveAllPendingWithSkip(engine)

        currentPlayerIndexField.setInt(engine, 1)
        val restoredIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.M1 }
        assertTrue(restoredIndex >= 0)
        assertTrue(engine.discard(south.uuid, restoredIndex))
    }

    @Test
    fun `chii at the high end does not invent a reverse suji kuikae tile`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        engine.seats.forEach { it.resetRoundState() }
        east.hands +=
            tiles(
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.M9,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
            )

        assertTrue(engine.discard(east.uuid, 0))
        val chiiPair = MahjongTile.M1 to MahjongTile.M2
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.CHII, chiiPair)))

        val legalM4Index = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.M4 }
        assertTrue(legalM4Index >= 0)
        assertTrue(engine.discard(south.uuid, legalM4Index))
    }

    @Test
    fun `pon forbids discarding the fourth matching tile only for the immediate discard`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        engine.seats.forEach { it.resetRoundState() }
        east.hands +=
            tiles(
                MahjongTile.P5,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.P5,
                MahjongTile.P5,
                MahjongTile.P5,
                MahjongTile.M9,
                MahjongTile.M1,
                MahjongTile.M4,
                MahjongTile.M7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
            )

        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(south.uuid)?.canPon == true)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.PON)))

        val fourthTileIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.P5 }
        val legalIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.M9 }
        assertTrue(fourthTileIndex >= 0)
        assertTrue(legalIndex >= 0)
        assertFalse(engine.discard(south.uuid, fourthTileIndex))
        assertTrue(engine.discard(south.uuid, legalIndex))
        resolveAllPendingWithSkip(engine)

        currentPlayerIndexField.setInt(engine, 1)
        val restoredIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.P5 }
        assertTrue(restoredIndex >= 0)
        assertTrue(engine.discard(south.uuid, restoredIndex))
    }

    @Test
    fun `called discard leaves display river but still counts as discarder history`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        south.resetRoundState()
        west.resetRoundState()
        north.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M2,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
            )
        west.hands +=
            tiles(
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
            )
        north.hands +=
            tiles(
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.GREEN_DRAGON,
            )

        assertTrue(engine.discard(east.uuid, 0))
        val calledTile = engine.discards.last()
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.CHII, MahjongTile.M1 to MahjongTile.M3)))

        assertTrue(east.discardedTiles.contains(calledTile))
        assertFalse(east.discardedTilesForDisplay.contains(calledTile))
    }
}

class RiichiRoundEngineReactionAndAbortTest {
    @Test
    fun `pon takes priority over chii on the same discard`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        south.resetRoundState()
        west.resetRoundState()
        north.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M2,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
            )
        south.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.WHITE_DRAGON,
            )
        west.hands +=
            tiles(
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.P1,
                MahjongTile.P4,
                MahjongTile.P7,
                MahjongTile.S1,
                MahjongTile.S4,
                MahjongTile.S7,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.NORTH,
                MahjongTile.GREEN_DRAGON,
            )
        north.hands +=
            tiles(
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.EAST,
                MahjongTile.SOUTH,
                MahjongTile.WEST,
                MahjongTile.RED_DRAGON,
            )

        assertTrue(engine.discard(east.uuid, 0))
        assertEquals(listOf(MahjongTile.M1 to MahjongTile.M3), engine.availableReactions(south.uuid)?.chiiPairs)
        assertTrue(engine.availableReactions(west.uuid)?.canPon == true)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.CHII, MahjongTile.M1 to MahjongTile.M3)))
        assertTrue(engine.react(west.uuid, ReactionResponse(ReactionType.PON)))
        assertEquals("west", engine.currentPlayer.uuid)
        assertTrue(west.fuuroList.any { it.type == MeldType.PON })
        assertTrue(south.fuuroList.none { it.type == MeldType.CHII })
    }

    @Test
    fun `last live-wall discard only allows ron responses`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(riichiProfile = MahjongRule.RiichiProfile.EARLY_KAN_DORA),
            )
        engine.startRound()
        setupDualRedDragonRonReaction(engine)
        val east = engine.seats[0]
        val south = engine.seats[1]
        val north = engine.seats[3]
        engine.wall.clear()

        assertTrue(engine.discard(east.uuid, 0))
        val southOptions = engine.availableReactions(south.uuid)
        val northOptions = engine.availableReactions(north.uuid)
        assertTrue(southOptions != null)
        assertTrue(northOptions != null)
        assertTrue(southOptions.canRon)
        assertFalse(southOptions.canPon)
        assertFalse(southOptions.canMinkan)
        assertTrue(southOptions.chiiPairs.isEmpty())
    }

    @Test
    fun `every accepted non ron response marks furiten when ron was available`() {
        val chiiPair = MahjongTile.M1 to MahjongTile.M2
        val responses =
            listOf(
                ReactionResponse(ReactionType.SKIP) to ReactionOptions(true, false, false, emptyList()),
                ReactionResponse(ReactionType.PON) to ReactionOptions(true, true, false, emptyList()),
                ReactionResponse(ReactionType.MINKAN) to ReactionOptions(true, false, true, emptyList()),
                ReactionResponse(ReactionType.CHII, chiiPair) to ReactionOptions(true, false, false, listOf(chiiPair)),
            )

        responses.forEach { (response, options) ->
            val engine =
                RiichiRoundEngine(
                    listOf(
                        RiichiPlayerState("East", "east"),
                        RiichiPlayerState("South", "south"),
                        RiichiPlayerState("West", "west"),
                        RiichiPlayerState("North", "north"),
                    ),
                    MahjongRule(),
                )
            val east = engine.seats[0]
            val south = engine.seats[1]
            val west = engine.seats[2]
            pendingReactionField.set(
                engine,
                PendingReaction(
                    discarderUuid = east.uuid,
                    tile = TileInstance(mahjongTile = MahjongTile.M3),
                    options =
                        mapOf(
                            south.uuid to options,
                            west.uuid to ReactionOptions(true, false, false, emptyList()),
                        ),
                ),
            )

            assertTrue(engine.react(south.uuid, response))
            assertTrue(south.temporaryFuriten, response.type.name)
        }
    }

    @Test
    fun `skipping ron applies temporary furiten until next draw`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        setupDualRedDragonRonReaction(engine)
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        engine.wall.clear()
        engine.wall += tiles(MahjongTile.M1, MahjongTile.P1, MahjongTile.S1)

        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(south.uuid)?.canRon == true)
        assertTrue(engine.availableReactions(north.uuid)?.canRon == true)

        assertTrue(engine.react(north.uuid, ReactionResponse(ReactionType.SKIP)))
        assertTrue(north.temporaryFuriten)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.SKIP)))
        assertTrue(engine.pendingReaction == null)
        assertEquals(south.uuid, engine.currentPlayer.uuid)

        val southRedDragonIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.RED_DRAGON }
        assertTrue(southRedDragonIndex >= 0)
        assertTrue(engine.discard(south.uuid, southRedDragonIndex))
        val northOptionsAfterSkip = engine.availableReactions(north.uuid)
        assertTrue(northOptionsAfterSkip != null)
        assertFalse(northOptionsAfterSkip.canRon)
        assertTrue(north.temporaryFuriten)
        resolveAllPendingWithSkip(engine)
        assertEquals(west.uuid, engine.currentPlayer.uuid)

        assertTrue(engine.discard(west.uuid, 0))
        resolveAllPendingWithSkip(engine)
        assertEquals(north.uuid, engine.currentPlayer.uuid)
        assertFalse(north.temporaryFuriten)
    }

    @Test
    fun `riichi player skipping ron stays furiten after their next draw`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        setupDualRedDragonRonReaction(engine)
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        val declarationTile = TileInstance(mahjongTile = MahjongTile.M9)
        north.riichi = true
        north.riichiSengenTile = declarationTile
        engine.discards += declarationTile
        engine.wall.clear()
        engine.wall += tiles(MahjongTile.M1, MahjongTile.P1, MahjongTile.S1)

        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(north.uuid)?.canRon == true)
        assertTrue(engine.react(north.uuid, ReactionResponse(ReactionType.SKIP)))
        assertTrue(north.riichiFuriten)
        assertFalse(north.temporaryFuriten)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.SKIP)))

        val southRedDragonIndex = south.hands.indexOfFirst { it.mahjongTile == MahjongTile.RED_DRAGON }
        assertTrue(southRedDragonIndex >= 0)
        assertTrue(engine.discard(south.uuid, southRedDragonIndex))
        assertFalse(engine.availableReactions(north.uuid)?.canRon == true)
        resolveAllPendingWithSkip(engine)
        assertTrue(engine.discard(west.uuid, 0))
        resolveAllPendingWithSkip(engine)

        assertEquals(north.uuid, engine.currentPlayer.uuid)
        assertTrue(north.riichiFuriten)
        assertFalse(north.temporaryFuriten)
    }

    @Test
    fun `suufon renda only triggers on first four opening discards`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.discards +=
            tiles(
                MahjongTile.M1,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
            )

        assertFalse(engine.isSuufonRenda)
    }

    @Test
    fun `dealer second turn is outside the first-cycle abort window`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.currentPlayer
        east.resetRoundState()
        east.hands +=
            tiles(
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
                MahjongTile.M2,
            )
        engine.discards +=
            tiles(
                MahjongTile.M2,
                MahjongTile.P2,
                MahjongTile.S2,
                MahjongTile.M3,
            )

        assertFalse(engine.isFirstRound)
        assertFalse(engine.canKyuushuKyuuhai(east.uuid))
    }
}

class RiichiRoundEngineMatchProgressionTest {
    @Test
    fun `placement order uses fixed starting-seat order for tie breaks`() {
        val players =
            listOf(
                RiichiPlayerState("East", "east"),
                RiichiPlayerState("South", "south"),
                RiichiPlayerState("West", "west"),
                RiichiPlayerState("North", "north"),
            )
        val engine = RiichiRoundEngine(players, MahjongRule())
        engine.round.round = 2
        engine.seats.forEach { it.points = 25000 }

        assertEquals(listOf("east", "south", "west", "north"), engine.placementOrder().map { it.uuid })
    }

    @Test
    fun `bankruptcy ends the match and awards leftover riichi sticks to first place`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("A", "a"),
                    RiichiPlayerState("B", "b"),
                    RiichiPlayerState("C", "c"),
                    RiichiPlayerState("D", "d"),
                ),
                MahjongRule(),
            )
        engine.seats[0].points = 35000
        engine.seats[1].points = 24000
        engine.seats[2].points = -1000
        engine.seats[3].points = 22000
        engine.seats[1].sticks += ScoringStick.P1000
        engine.seats[2].sticks += ScoringStick.P1000

        finishRound(engine, dealerRemaining = false, clearRiichiSticks = false)

        assertTrue(engine.gameFinished)
        assertEquals(37000, engine.seats[0].points)
        assertTrue(engine.seats.all { it.sticks.isEmpty() })
    }

    @Test
    fun `final riichi pool award is included in the resolution score snapshot`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("A", "a"),
                    RiichiPlayerState("B", "b"),
                    RiichiPlayerState("C", "c"),
                    RiichiPlayerState("D", "d"),
                ),
                MahjongRule(),
            )
        engine.seats[0].points = 35000
        engine.seats[1].points = 24000
        engine.seats[2].points = -1000
        engine.seats[3].points = 22000
        engine.seats[1].sticks += ScoringStick.P1000
        engine.seats[2].sticks += ScoringStick.P1000

        resolveDraw(engine, ExhaustiveDraw.KYUUSHU_KYUUHAI)

        assertTrue(engine.gameFinished)
        assertEquals(37000, engine.seats[0].points)
        assertTrue(engine.seats.all { it.sticks.isEmpty() })
        val scoreByUuid =
            engine.lastResolution!!
                .scoreSettlement!!
                .scoreList
                .associateBy { it.stringUUID }
        assertEquals(2000, scoreByUuid.getValue("a").scoreChange)
        engine.seats.forEach { player ->
            val score = scoreByUuid.getValue(player.uuid)
            assertEquals(player.points, score.scoreOrigin + score.scoreChange)
        }
    }

    @Test
    fun `all last dealer does not stop unless dealer is first`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(length = MahjongRule.GameLength.EAST),
            )
        engine.round = MahjongRound(wind = Wind.EAST, round = 3, honba = 0)
        setSpentRounds(engine.round, 3)
        engine.seats[0].points = 32000
        engine.seats[1].points = 33000
        engine.seats[2].points = 20000
        engine.seats[3].points = 15000

        finishRound(engine, dealerRemaining = true, clearRiichiSticks = false)

        assertFalse(engine.gameFinished)
        assertEquals(1, engine.round.honba)
        assertEquals(Wind.EAST, engine.round.wind)
        assertEquals(3, engine.round.round)
    }

    @Test
    fun `east game extends into south when all last rotates below the return target`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(length = MahjongRule.GameLength.EAST),
            )
        engine.round = MahjongRound(wind = Wind.EAST, round = 3, honba = 0)
        setSpentRounds(engine.round, 3)
        engine.seats[0].points = 29000
        engine.seats[1].points = 26000
        engine.seats[2].points = 25000
        engine.seats[3].points = 20000

        finishRound(engine, dealerRemaining = false, clearRiichiSticks = false)

        assertFalse(engine.gameFinished)
        assertEquals(Wind.SOUTH, engine.round.wind)
        assertEquals(0, engine.round.round)
    }

    @Test
    fun `east game stops after south four even when nobody reaches the return target`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(length = MahjongRule.GameLength.EAST),
            )
        engine.round = MahjongRound(wind = Wind.SOUTH, round = 3, honba = 0)
        setSpentRounds(engine.round, 7)
        engine.seats[0].points = 29000
        engine.seats[1].points = 26000
        engine.seats[2].points = 25000
        engine.seats[3].points = 20000

        finishRound(engine, dealerRemaining = false, clearRiichiSticks = false)

        assertTrue(engine.gameFinished)
        assertEquals(Wind.SOUTH, engine.round.wind)
        assertEquals(3, engine.round.round)
    }

    @Test
    fun `majsoul extension ends when a nondealer crosses the target on a dealer tenpai draw`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(length = MahjongRule.GameLength.EAST),
            )
        engine.round = MahjongRound(wind = Wind.SOUTH, round = 0, honba = 0)
        setSpentRounds(engine.round, 4)
        engine.seats[0].points = 29500
        engine.seats[1].points = 30500
        engine.seats[2].points = 21000
        engine.seats[3].points = 19000

        finishRound(engine, dealerRemaining = true, clearRiichiSticks = false)

        assertTrue(engine.gameFinished)
        assertEquals(0, engine.round.honba)
    }

    @Test
    fun `majsoul extension keeps dealer after a multi ron that includes the dealer`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(length = MahjongRule.GameLength.EAST),
            )
        engine.round = MahjongRound(wind = Wind.SOUTH, round = 0, honba = 0)
        setSpentRounds(engine.round, 4)
        engine.seats[0].points = 30500
        engine.seats[1].points = 29500
        engine.seats[2].points = 21000
        engine.seats[3].points = 19000

        finishRoundWithDealerMultiRon(engine)

        assertFalse(engine.gameFinished)
        assertEquals(1, engine.round.honba)
        assertEquals(Wind.SOUTH, engine.round.wind)
        assertEquals(0, engine.round.round)
    }
}

class RiichiRoundEngineKanDoraTest {
    @Test
    fun `majsoul open kan reveals dora before the callers next discard is judged`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        south.resetRoundState()
        west.resetRoundState()
        north.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
                MahjongTile.NORTH,
            )
        south.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.SOUTH,
            )
        west.hands +=
            tiles(
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.GREEN_DRAGON,
            )
        north.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P2,
                MahjongTile.S1,
                MahjongTile.S1,
                MahjongTile.RED_DRAGON,
            )

        assertEquals(1, engine.doraIndicators.size)
        val originalIndicator = engine.doraIndicators.single().id
        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(south.uuid)?.canMinkan == true)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.MINKAN)))
        assertEquals(1, engine.doraIndicators.size)
        assertEquals(originalIndicator, engine.doraIndicators.single().id)
        assertTrue(engine.discard(south.uuid, south.hands.lastIndex))
        assertEquals(2, engine.doraIndicators.size)
        assertEquals(originalIndicator, engine.doraIndicators.first().id)
    }

    @Test
    fun `majsoul daiminkan followed by rinshan ankan reveals and scores both kan dora`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        engine.seats.forEach { it.resetRoundState() }
        val east = engine.seats[0]
        val south = engine.seats[1]
        east.hands += TileInstance(mahjongTile = MahjongTile.EAST)
        south.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
            )
        engine.deadWall[8] = TileInstance(mahjongTile = MahjongTile.S9)
        engine.deadWall[6] = TileInstance(mahjongTile = MahjongTile.M9)
        engine.deadWall[4] = TileInstance(mahjongTile = MahjongTile.P9)
        engine.deadWall[13] = TileInstance(mahjongTile = MahjongTile.S5)
        engine.deadWall[11] = TileInstance(mahjongTile = MahjongTile.S5)
        engine.wall.clear()
        engine.wall += tiles(MahjongTile.NORTH, MahjongTile.GREEN_DRAGON)

        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(south.uuid)?.canMinkan == true)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.MINKAN)))
        assertEquals(1, engine.doraIndicators.size)
        assertEquals(MahjongTile.S5, south.lastDrawnTile?.mahjongTile)

        assertTrue(engine.tryAnkanOrKakan(south.uuid, MahjongTile.WHITE_DRAGON))
        assertEquals(2, engine.kanCount)
        assertEquals(MahjongTile.S5, south.lastDrawnTile?.mahjongTile)
        assertEquals(
            listOf(MahjongTile.S9, MahjongTile.M9, MahjongTile.P9),
            engine.doraIndicators.map { it.mahjongTile },
        )
        assertTrue(engine.tryTsumo(south.uuid))

        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertEquals(2, settlement.yakuList.count { it == "DORA" })
        assertEquals(
            listOf(MahjongTile.S9, MahjongTile.M9, MahjongTile.P9),
            settlement.doraIndicators,
        )
    }

    @Test
    fun `majsoul chankan reveals an older open kan dora without registering the robbed kakan`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        engine.seats.forEach { it.resetRoundState() }
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        east.hands += TileInstance(mahjongTile = MahjongTile.EAST)
        south.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.RED_DRAGON,
            )
        south.fuuroList += openPon(MahjongTile.RED_DRAGON)
        configureClosedRedDragonRonWait(west)
        engine.deadWall[8] = TileInstance(mahjongTile = MahjongTile.S9)
        engine.deadWall[6] = TileInstance(mahjongTile = MahjongTile.M9)
        engine.deadWall[13] = TileInstance(mahjongTile = MahjongTile.P5)
        engine.wall.clear()
        engine.wall += tiles(MahjongTile.NORTH, MahjongTile.GREEN_DRAGON)

        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.MINKAN)))
        assertEquals(1, engine.kanCount)
        assertEquals(listOf(MahjongTile.S9), engine.doraIndicators.map { it.mahjongTile })

        assertTrue(engine.tryAnkanOrKakan(south.uuid, MahjongTile.RED_DRAGON))
        assertTrue(engine.pendingReaction?.isChankan == true)
        assertEquals(MeldType.KAKAN, engine.pendingReaction?.pendingKanType)
        assertEquals(1, engine.kanCount)
        assertEquals(
            listOf(MahjongTile.S9, MahjongTile.M9),
            engine.doraIndicators.map { it.mahjongTile },
        )
        assertTrue(engine.react(west.uuid, ReactionResponse(ReactionType.RON)))

        assertEquals(1, engine.kanCount)
        assertTrue(south.fuuroList.any { it.isPon && it.claimTile.mahjongTile == MahjongTile.RED_DRAGON })
        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertEquals(1, settlement.yakuList.count { it == "DORA" })
        assertEquals(listOf(MahjongTile.S9, MahjongTile.M9), settlement.doraIndicators)
    }

    @Test
    fun `early kan dora profile reveals open kan dora before the rinshan draw`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(riichiProfile = MahjongRule.RiichiProfile.EARLY_KAN_DORA),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        east.resetRoundState()
        south.resetRoundState()
        west.resetRoundState()
        north.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.RED_DRAGON,
                MahjongTile.NORTH,
            )
        south.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M6,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.SOUTH,
            )
        west.hands +=
            tiles(
                MahjongTile.M7,
                MahjongTile.M8,
                MahjongTile.M9,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.S7,
                MahjongTile.S8,
                MahjongTile.S9,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.WHITE_DRAGON,
                MahjongTile.GREEN_DRAGON,
                MahjongTile.GREEN_DRAGON,
            )
        north.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P2,
                MahjongTile.S1,
                MahjongTile.S1,
                MahjongTile.RED_DRAGON,
            )

        assertEquals(1, engine.doraIndicators.size)
        val originalIndicator = engine.doraIndicators.single().id
        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(south.uuid)?.canMinkan == true)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.MINKAN)))
        assertEquals(2, engine.doraIndicators.size)
        assertEquals(originalIndicator, engine.doraIndicators.first().id)
    }

    @Test
    fun `ankan is rejected when no rinshan draw is available`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.currentPlayer
        east.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.WHITE_DRAGON,
            )
        engine.wall.clear()

        assertFalse(engine.tryAnkanOrKakan(east.uuid, MahjongTile.EAST))
        assertEquals(4, east.hands.count { it.mahjongTile == MahjongTile.EAST })
        assertTrue(engine.pendingReaction == null)

        engine.wall += TileInstance(mahjongTile = MahjongTile.M1)
        engine.deadWall.clear()
        assertFalse(engine.tryAnkanOrKakan(east.uuid, MahjongTile.EAST))
        assertEquals(4, east.hands.count { it.mahjongTile == MahjongTile.EAST })
        assertEquals(0, engine.kanCount)
    }
}

class RiichiRoundEngineKanResolutionTest {
    @Test
    fun `suukaikan resolves only after the fourth-kan player discards`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        engine.seats.forEach { it.resetRoundState() }
        val east = engine.seats[0]
        val south = engine.seats[1]
        east.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M1,
            )
        val southClaim = TileInstance(mahjongTile = MahjongTile.SOUTH)
        south.fuuroList +=
            Fuuro(
                MeldType.MINKAN,
                listOf(
                    southClaim,
                    TileInstance(mahjongTile = MahjongTile.SOUTH),
                    TileInstance(mahjongTile = MahjongTile.SOUTH),
                    TileInstance(mahjongTile = MahjongTile.SOUTH),
                ),
                ClaimTarget.RIGHT,
                southClaim,
            )
        kanCountField.setInt(engine, 3)
        engine.wall.clear()
        engine.wall += TileInstance(mahjongTile = MahjongTile.M2)

        assertTrue(engine.tryAnkanOrKakan(east.uuid, MahjongTile.EAST))
        assertEquals(4, engine.kanCount)
        val handBeforeAbort = east.hands.map { it.id }
        val riverBeforeAbort = east.discardedTiles.map { it.id }

        assertTrue(engine.discard(east.uuid, 0))

        assertFalse(engine.started)
        assertEquals(ExhaustiveDraw.SUUKAIKAN, engine.lastResolution?.draw)
        assertEquals(handBeforeAbort.drop(1), east.hands.map { it.id })
        assertEquals(riverBeforeAbort + handBeforeAbort.first(), east.discardedTiles.map { it.id })
    }

    @Test
    fun `riichi declared on the suukaikan discard remains deposited`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.currentPlayer
        east.resetRoundState()
        east.hands +=
            tiles(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.M4,
                MahjongTile.M5,
                MahjongTile.M9,
            )
        val riichiTile = east.tilePairsForRiichi.first().first
        val riichiIndex = east.hands.indexOfFirst { it.mahjongTile == riichiTile }
        val discardedId = east.hands[riichiIndex].id
        val pointsBefore = east.points
        pendingAbortiveDrawField.set(engine, ExhaustiveDraw.SUUKAIKAN)

        assertTrue(engine.declareRiichi(east.uuid, riichiIndex))

        assertTrue(east.riichi || east.doubleRiichi)
        assertEquals(pointsBefore - 1000, east.points)
        assertEquals(1, east.riichiStickAmount)
        assertEquals(listOf(discardedId), east.discardedTiles.map { it.id })
        assertEquals(ExhaustiveDraw.SUUKAIKAN, engine.lastResolution?.draw)
    }

    @Test
    fun `discard after rinshan cannot award houtei when the live wall becomes empty`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        engine.seats.forEach { it.resetRoundState() }
        east.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.RED_DRAGON,
            )
        configureClosedRedDragonRonWait(south)
        engine.wall.clear()
        engine.wall += TileInstance(mahjongTile = MahjongTile.M1)

        assertTrue(engine.tryAnkanOrKakan(east.uuid, MahjongTile.EAST))
        assertTrue(engine.wall.isEmpty())
        val redDragonIndex = east.hands.indexOfFirst { it.mahjongTile == MahjongTile.RED_DRAGON }
        assertTrue(redDragonIndex >= 0)
        assertTrue(engine.discard(east.uuid, redDragonIndex))
        assertTrue(engine.availableReactions(south.uuid)?.canRon == true)
        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.RON)))

        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertFalse("HOUTEI" in settlement.yakuList)
    }

    @Test
    fun `unrelated hand tile cannot be promoted to kan when another pon is promotable`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.currentPlayer
        east.resetRoundState()
        east.fuuroList += openPon(MahjongTile.P5)
        east.hands += tiles(MahjongTile.P5, MahjongTile.M2)

        assertTrue(east.canKakan)
        assertFalse(engine.tryAnkanOrKakan(east.uuid, MahjongTile.M2))
        assertEquals(0, engine.kanCount)
        assertTrue(east.fuuroList.single().isPon)
        assertTrue(east.hands.any { it.mahjongTile == MahjongTile.M2 })
    }

    @Test
    fun `skipped chankan completes the promoted kan and defers majsoul dora until discard`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        east.resetRoundState()
        south.resetRoundState()
        engine.seats[2].resetRoundState()
        engine.seats[3].resetRoundState()
        east.fuuroList += openPon(MahjongTile.RED_DRAGON)
        east.hands += TileInstance(mahjongTile = MahjongTile.RED_DRAGON)
        configureClosedRedDragonRonWait(south)

        assertTrue(engine.tryAnkanOrKakan(east.uuid, MahjongTile.RED_DRAGON))
        assertTrue(engine.availableReactions(south.uuid)?.canRon == true)
        assertEquals(0, engine.kanCount)

        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.SKIP)))
        assertEquals(1, engine.kanCount)
        assertEquals(1, engine.doraIndicators.size)
        assertEquals(MeldType.KAKAN, east.fuuroList.single().type)
        assertTrue(east.lastDrawnTile != null)
        assertTrue(engine.discard(east.uuid, east.hands.lastIndex))
        assertEquals(2, engine.doraIndicators.size)
    }

    @Test
    fun `chankan ron leaves the robbed promoted kan as its original pon`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        val east = engine.seats[0]
        val south = engine.seats[1]
        engine.seats.forEach { it.resetRoundState() }
        east.fuuroList += openPon(MahjongTile.RED_DRAGON)
        val promotedTile = TileInstance(mahjongTile = MahjongTile.RED_DRAGON)
        east.hands += promotedTile
        configureClosedRedDragonRonWait(south)

        assertTrue(engine.tryAnkanOrKakan(east.uuid, MahjongTile.RED_DRAGON))
        assertTrue(east.fuuroList.single().isPon)
        assertTrue(promotedTile in east.hands)

        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.RON)))
        assertEquals("Ron", engine.lastResolution?.title)
        assertTrue(east.fuuroList.single().isPon)
        assertFalse(promotedTile in east.hands)
        assertEquals(0, engine.kanCount)
        assertEquals(1, engine.doraIndicators.size)
    }

    @Test
    fun `ankan interrupts another players ippatsu window`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.startRound()
        engine.seats.forEach { it.resetRoundState() }
        val east = engine.seats[0]
        val south = engine.seats[1]
        val declarationTile = TileInstance(mahjongTile = MahjongTile.M9)
        east.riichi = true
        east.riichiSengenTile = declarationTile
        east.discardedTiles += declarationTile
        engine.discards += declarationTile
        south.hands +=
            tiles(
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
                MahjongTile.EAST,
            )
        currentPlayerIndexField.setInt(engine, 1)

        assertTrue(east.isIppatsu(engine.seats, engine.discards))
        assertTrue(engine.tryAnkanOrKakan(south.uuid, MahjongTile.EAST))
        assertFalse(east.isIppatsu(engine.seats, engine.discards))
    }
}

class RiichiRoundEngineSettlementTest {
    @Test
    fun `riichi winner receives their deposited stick exactly once`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val south = engine.seats[1]
        val west = engine.seats[2]
        configureClosedRedDragonRonWait(south)
        south.points = 24000
        south.riichi = true
        south.sticks += ScoringStick.P1000
        val declarationTile = TileInstance(mahjongTile = MahjongTile.M9)
        south.riichiSengenTile = declarationTile
        south.discardedTiles += declarationTile
        engine.discards += declarationTile

        resolveRon(engine, listOf(south), west, TileInstance(mahjongTile = MahjongTile.RED_DRAGON), false)

        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertEquals(24000 + settlement.score + 1000, south.points)
        assertEquals(100000, engine.seats.sumOf { it.points })
        assertTrue(engine.seats.all { it.sticks.none { stick -> stick == ScoringStick.P1000 } })
    }

    @Test
    fun `riichi deposits are not charged again on an abortive draw`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val east = engine.seats[0]
        east.points = 24000
        east.riichi = true
        east.sticks += ScoringStick.P1000

        resolveDraw(engine, ExhaustiveDraw.KYUUSHU_KYUUHAI)

        assertEquals(24000, east.points)
        assertEquals(1, east.riichiStickAmount)
        assertEquals(1, engine.round.honba)
    }

    @Test
    fun `nondealer tsumo returns their riichi deposit without creating or losing points`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val south = engine.seats[1]
        configureClosedRedDragonRonWait(south)
        val winningTile = TileInstance(mahjongTile = MahjongTile.RED_DRAGON)
        south.drawTile(winningTile)
        south.points = 24000
        south.riichi = true
        south.sticks += ScoringStick.P1000
        val declarationTile = TileInstance(mahjongTile = MahjongTile.M9)
        south.riichiSengenTile = declarationTile
        south.discardedTiles += declarationTile
        engine.discards += declarationTile
        engine.round.honba = 1

        resolveTsumo(engine, south, winningTile, false)

        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertEquals(24000 + settlement.score + 1000 + 300, south.points)
        assertEquals(100000, engine.seats.sumOf { it.points })
        assertEquals(3, settlement.paymentBreakdown.count { it.type == SettlementPaymentType.HONBA && it.amount == 100 })
    }

    @Test
    fun `normal exhaustive draw carries honba when dealer rotates`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        engine.round.honba = 2

        resolveDraw(engine, ExhaustiveDraw.NORMAL)

        assertEquals(1, engine.round.round)
        assertEquals(3, engine.round.honba)
    }

    @Test
    fun `nondealer nagashi mangan uses dealer-child shares and leaves the riichi pool on table`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val south = engine.seats[1]
        val west = engine.seats[2]
        west.points = 24000
        west.sticks += ScoringStick.P1000
        engine.round.honba = 1

        resolveNagashiMangan(engine, listOf(south))

        assertEquals(33000, south.points)
        assertEquals(21000, engine.seats[0].points)
        assertEquals(22000, west.points)
        assertEquals(23000, engine.seats[3].points)
        assertEquals(99000, engine.seats.sumOf { it.points })
        assertEquals(1, west.riichiStickAmount)
        assertEquals(2, engine.round.honba)
        val payments =
            engine.lastResolution!!
                .yakuSettlements
                .single()
                .paymentBreakdown
        assertEquals(listOf(2000, 2000, 4000), payments.filter { it.payerUuid.isNotBlank() }.map { it.amount }.sorted())
        assertFalse(payments.any { it.type == SettlementPaymentType.RIICHI_POOL })
    }

    @Test
    fun `pao ron splits yakuman payment between discarder and liable player`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        configureOpenDaisangenWait(south)
        setPaoLiability(engine, south.uuid, "DAISANGEN", east.uuid)

        resolveRon(engine, listOf(south), west, TileInstance(mahjongTile = MahjongTile.RED_DRAGON), false)

        assertEquals(57000, south.points)
        assertEquals(9000, east.points)
        assertEquals(9000, west.points)
        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertTrue(
            settlement.paymentBreakdown.any { it.payerUuid == west.uuid && it.amount == 16000 && it.type == SettlementPaymentType.RON },
        )
        assertTrue(
            settlement.paymentBreakdown.any { it.payerUuid == east.uuid && it.amount == 16000 && it.type == SettlementPaymentType.PAO },
        )
    }

    @Test
    fun `pao ron does not double-charge a discarder who is also liable`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val south = engine.seats[1]
        val west = engine.seats[2]
        configureOpenDaisangenWait(south)
        setPaoLiability(engine, south.uuid, "DAISANGEN", west.uuid)
        engine.round.honba = 1

        resolveRon(engine, listOf(south), west, TileInstance(mahjongTile = MahjongTile.RED_DRAGON), false)

        assertEquals(57300, south.points)
        assertEquals(-7300, west.points)
        val payments =
            engine.lastResolution!!
                .yakuSettlements
                .single()
                .paymentBreakdown
        assertEquals(32300, payments.filter { it.payerUuid == west.uuid }.sumOf { it.amount })
    }

    @Test
    fun `head bump ron mode resolves to nearest claimant only after all ron candidates respond`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(ronMode = MahjongRule.RonMode.HEAD_BUMP),
            )
        engine.startRound()
        setupDualRedDragonRonReaction(engine)
        val east = engine.seats[0]
        val south = engine.seats[1]
        val north = engine.seats[3]

        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(south.uuid)?.canRon == true)
        assertTrue(engine.availableReactions(north.uuid)?.canRon == true)

        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.RON)))
        assertTrue(engine.pendingReaction != null)
        assertTrue(engine.lastResolution == null)

        assertTrue(engine.react(north.uuid, ReactionResponse(ReactionType.RON)))
        assertTrue(engine.pendingReaction == null)
        assertEquals(listOf(south.uuid), engine.lastResolution!!.yakuSettlements.map { it.uuid })
        assertEquals(25000, north.points)
    }

    @Test
    fun `multi ron mode resolves all ron claimants after all ron candidates respond`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(ronMode = MahjongRule.RonMode.MULTI_RON),
            )
        engine.startRound()
        setupDualRedDragonRonReaction(engine)
        engine.round.honba = 1
        val east = engine.seats[0]
        val south = engine.seats[1]
        val north = engine.seats[3]

        assertTrue(engine.discard(east.uuid, 0))
        assertTrue(engine.availableReactions(south.uuid)?.canRon == true)
        assertTrue(engine.availableReactions(north.uuid)?.canRon == true)

        assertTrue(engine.react(south.uuid, ReactionResponse(ReactionType.RON)))
        assertTrue(engine.pendingReaction != null)
        assertTrue(engine.lastResolution == null)

        assertTrue(engine.react(north.uuid, ReactionResponse(ReactionType.RON)))
        val winnerUuids = engine.lastResolution!!.yakuSettlements.map { it.uuid }
        assertEquals(2, winnerUuids.size)
        assertTrue(south.uuid in winnerUuids)
        assertTrue(north.uuid in winnerUuids)
        val settlements = engine.lastResolution!!.yakuSettlements.associateBy { it.uuid }
        assertEquals(25000 + settlements.getValue(south.uuid).score + 300, south.points)
        assertEquals(25000 + settlements.getValue(north.uuid).score, north.points)
    }

    @Test
    fun `majsoul exhaustive draw treats a sole four-in-hand tanki as noten`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(riichiProfile = MahjongRule.RiichiProfile.MAJSOUL),
            )
        val east = engine.seats[0]
        val south = engine.seats[1]
        east.hands +=
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
        configureClosedRedDragonRonWait(south)

        resolveDraw(engine, ExhaustiveDraw.NORMAL)

        assertEquals(24000, east.points)
        assertEquals(28000, south.points)
    }

    @Test
    fun `pao tsumo charges the liable player the full yakuman value`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        configureOpenDaisangenWait(south)
        setPaoLiability(engine, south.uuid, "DAISANGEN", east.uuid)
        val winningTile = TileInstance(mahjongTile = MahjongTile.RED_DRAGON)
        south.drawTile(winningTile)

        resolveTsumo(engine, south, winningTile, false)

        assertEquals(57000, south.points)
        assertEquals(-7000, east.points)
        assertEquals(25000, west.points)
        assertEquals(25000, north.points)
        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertTrue(
            settlement.paymentBreakdown.any { it.payerUuid == east.uuid && it.amount == 32000 && it.type == SettlementPaymentType.PAO },
        )
        assertFalse(settlement.paymentBreakdown.any { it.payerUuid == west.uuid && it.amount > 0 })
    }

    @Test
    fun `composite yakuman tsumo keeps pao and normal shares separated`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        configureOpenDaisuushiTsuuiisouWait(south)
        setPaoLiability(engine, south.uuid, "DAISUUSHI", east.uuid)
        val winningTile = TileInstance(mahjongTile = MahjongTile.NORTH)
        south.drawTile(winningTile)

        resolveTsumo(engine, south, winningTile, false)

        val settlement = engine.lastResolution!!.yakuSettlements.single()
        assertEquals(121000, south.points)
        assertEquals(-55000, east.points)
        assertEquals(17000, west.points)
        assertEquals(17000, north.points)
        assertTrue(
            settlement.paymentBreakdown.any { it.payerUuid == east.uuid && it.amount == 64000 && it.type == SettlementPaymentType.PAO },
        )
        assertTrue(
            settlement.paymentBreakdown.any {
                it.payerUuid == east.uuid &&
                    it.amount == 16000 &&
                    it.type == SettlementPaymentType.TSUMO
            },
        )
        assertTrue(
            settlement.paymentBreakdown.any {
                it.payerUuid == west.uuid &&
                    it.amount == 8000 &&
                    it.type == SettlementPaymentType.TSUMO
            },
        )
        assertTrue(
            settlement.paymentBreakdown.any {
                it.payerUuid == north.uuid &&
                    it.amount == 8000 &&
                    it.type == SettlementPaymentType.TSUMO
            },
        )
    }

    @Test
    fun `multiple ron keeps pao scoped to the liable winner and riichi pool goes to atamahane`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val east = engine.seats[0]
        val south = engine.seats[1]
        val west = engine.seats[2]
        val north = engine.seats[3]
        configureOpenDaisangenWait(south)
        configureClosedRedDragonRonWait(north)
        setPaoLiability(engine, south.uuid, "DAISANGEN", east.uuid)
        west.sticks += ScoringStick.P1000

        resolveRon(engine, listOf(south, north), west, TileInstance(mahjongTile = MahjongTile.RED_DRAGON), false)

        val southSettlement = engine.lastResolution!!.yakuSettlements.first { it.uuid == south.uuid }
        val northSettlement = engine.lastResolution!!.yakuSettlements.first { it.uuid == north.uuid }
        assertEquals(25000 + southSettlement.score, south.points)
        assertEquals(25000 + northSettlement.score + 1000, north.points)
        assertEquals(9000, east.points)
        assertTrue(
            southSettlement.paymentBreakdown.any {
                it.payerUuid == east.uuid &&
                    it.amount == 16000 &&
                    it.type == SettlementPaymentType.PAO
            },
        )
        assertFalse(southSettlement.paymentBreakdown.any { it.type == SettlementPaymentType.RIICHI_POOL })
        assertTrue(northSettlement.paymentBreakdown.any { it.payerUuid == west.uuid && it.type == SettlementPaymentType.RON })
        assertTrue(northSettlement.paymentBreakdown.any { it.type == SettlementPaymentType.RIICHI_POOL && it.amount == 1000 })
        assertFalse(northSettlement.paymentBreakdown.any { it.payerUuid == east.uuid && it.type == SettlementPaymentType.PAO })
    }

    @Test
    fun `ron on riichi declaration tile refunds the declared riichi stick`() {
        val engine =
            RiichiRoundEngine(
                listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north"),
                ),
                MahjongRule(),
            )
        val east = engine.seats[0]
        val south = engine.seats[1]
        configureOpenDaisangenWait(south)
        east.points = 24000
        val declarationTile = TileInstance(mahjongTile = MahjongTile.RED_DRAGON)
        east.riichi = true
        east.riichiSengenTile = declarationTile
        east.sticks += ScoringStick.P1000

        resolveRon(engine, listOf(south), east, declarationTile, false)

        val settlement = engine.lastResolution!!.yakuSettlements.single()
        val eastPayment =
            settlement.paymentBreakdown
                .filter { payment -> payment.payerUuid == east.uuid && payment.type != SettlementPaymentType.RIICHI_POOL }
                .sumOf { it.amount }
        assertEquals(25000 - eastPayment, east.points)
        assertTrue(east.sticks.none { it == ScoringStick.P1000 })
        assertFalse(east.riichi)
        assertFalse(east.doubleRiichi)
    }
}

private fun tiles(vararg tiles: MahjongTile): List<TileInstance> = tiles.map { TileInstance(mahjongTile = it) }

private fun configureOpenDaisangenWait(player: RiichiPlayerState) {
    player.resetRoundState()
    player.hands +=
        tiles(
            MahjongTile.RED_DRAGON,
            MahjongTile.RED_DRAGON,
            MahjongTile.M1,
            MahjongTile.M2,
            MahjongTile.M3,
            MahjongTile.S5,
            MahjongTile.S5,
        )
    player.fuuroList += openPon(MahjongTile.WHITE_DRAGON)
    player.fuuroList += openPon(MahjongTile.GREEN_DRAGON)
}

private fun openPon(tile: MahjongTile): Fuuro {
    val claim = TileInstance(mahjongTile = tile)
    return Fuuro(
        MeldType.PON,
        listOf(claim, TileInstance(mahjongTile = tile), TileInstance(mahjongTile = tile)),
        ClaimTarget.RIGHT,
        claim,
    )
}

private fun configureOpenDaisuushiTsuuiisouWait(player: RiichiPlayerState) {
    player.resetRoundState()
    player.hands +=
        tiles(
            MahjongTile.NORTH,
            MahjongTile.NORTH,
            MahjongTile.WHITE_DRAGON,
            MahjongTile.WHITE_DRAGON,
        )
    player.fuuroList += openPon(MahjongTile.EAST)
    player.fuuroList += openPon(MahjongTile.SOUTH)
    player.fuuroList += openPon(MahjongTile.WEST)
}

private fun configureClosedRedDragonRonWait(player: RiichiPlayerState) {
    player.resetRoundState()
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
            MahjongTile.S2,
            MahjongTile.S2,
            MahjongTile.RED_DRAGON,
            MahjongTile.RED_DRAGON,
        )
}

private fun setupDualRedDragonRonReaction(engine: RiichiRoundEngine) {
    val east = engine.seats[0]
    val south = engine.seats[1]
    val west = engine.seats[2]
    val north = engine.seats[3]

    east.resetRoundState()
    south.resetRoundState()
    west.resetRoundState()
    north.resetRoundState()

    east.hands +=
        tiles(
            MahjongTile.RED_DRAGON,
            MahjongTile.M9,
            MahjongTile.P1,
            MahjongTile.P2,
            MahjongTile.P3,
            MahjongTile.S1,
            MahjongTile.S2,
            MahjongTile.S3,
            MahjongTile.EAST,
            MahjongTile.SOUTH,
            MahjongTile.WEST,
            MahjongTile.NORTH,
            MahjongTile.WHITE_DRAGON,
            MahjongTile.GREEN_DRAGON,
        )
    configureOpenDaisangenWait(south)
    west.hands +=
        tiles(
            MahjongTile.M1,
            MahjongTile.M4,
            MahjongTile.M7,
            MahjongTile.P1,
            MahjongTile.P4,
            MahjongTile.P7,
            MahjongTile.S1,
            MahjongTile.S4,
            MahjongTile.S7,
            MahjongTile.EAST,
            MahjongTile.SOUTH,
            MahjongTile.WEST,
            MahjongTile.NORTH,
        )
    configureClosedRedDragonRonWait(north)
}

private fun resolveAllPendingWithSkip(engine: RiichiRoundEngine) {
    while (true) {
        val pending = engine.pendingReaction ?: return
        val undecided = pending.options.keys.filter { it !in pending.responses.keys }
        if (undecided.isEmpty()) {
            return
        }
        undecided.forEach { responderUuid ->
            assertTrue(engine.react(responderUuid, ReactionResponse(ReactionType.SKIP)))
        }
    }
}

private fun setPaoLiability(
    engine: RiichiRoundEngine,
    winnerUuid: String,
    key: String,
    liableUuid: String,
) {
    @Suppress("UNCHECKED_CAST")
    val liabilities = paoField.get(engine) as MutableMap<String, MutableMap<String, String>>
    liabilities.getOrPut(winnerUuid) { linkedMapOf() }[key] = liableUuid
}

private fun finishRound(
    engine: RiichiRoundEngine,
    dealerRemaining: Boolean,
    clearRiichiSticks: Boolean,
) {
    finishRoundMethod.invoke(engine, dealerRemaining, clearRiichiSticks)
}

private fun finishRoundWithDealerMultiRon(engine: RiichiRoundEngine) {
    finishRoundWithDealerMultiRonMethod.invoke(engine, true, false, false, true)
}

private fun resolveRon(
    engine: RiichiRoundEngine,
    winners: List<RiichiPlayerState>,
    target: RiichiPlayerState,
    tile: TileInstance,
    isChankan: Boolean,
) {
    resolveRonMethod.invoke(engine, winners, target, tile, isChankan)
}

private fun resolveTsumo(
    engine: RiichiRoundEngine,
    winner: RiichiPlayerState,
    tile: TileInstance,
    isRinshanKaihou: Boolean,
) {
    resolveTsumoMethod.invoke(engine, winner, tile, isRinshanKaihou)
}

private fun resolveDraw(
    engine: RiichiRoundEngine,
    draw: ExhaustiveDraw,
) {
    resolveDrawMethod.invoke(engine, draw)
}

private fun resolveNagashiMangan(
    engine: RiichiRoundEngine,
    winners: List<RiichiPlayerState>,
) {
    resolveNagashiManganMethod.invoke(engine, winners)
}

private fun setSpentRounds(
    round: MahjongRound,
    value: Int,
) {
    spentRoundsField.setInt(round, value)
}

private val finishRoundMethod: Method =
    engineMethod(
        "finishRound",
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
    )

private val finishRoundWithDealerMultiRonMethod: Method =
    engineMethod(
        "finishRound",
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
    )

private val resolveRonMethod: Method =
    engineMethod(
        "resolveRon",
        List::class.java,
        RiichiPlayerState::class.java,
        TileInstance::class.java,
        Boolean::class.javaPrimitiveType,
    )

private val resolveTsumoMethod: Method =
    engineMethod(
        "resolveTsumo",
        RiichiPlayerState::class.java,
        TileInstance::class.java,
        Boolean::class.javaPrimitiveType,
    )

private val resolveDrawMethod: Method =
    engineMethod("resolveDraw", ExhaustiveDraw::class.java)

private val resolveNagashiManganMethod: Method =
    engineMethod("resolveNagashiMangan", List::class.java)

private val spentRoundsField =
    MahjongRound::class.java.getDeclaredField("spentRounds").apply { isAccessible = true }

private val paoField: Field =
    engineField("paoLiabilityByWinner")

private val currentPlayerIndexField: Field =
    engineField("currentPlayerIndex")

private val pendingReactionField: Field =
    engineField("pendingReaction")

private val pendingAbortiveDrawField: Field =
    engineField("pendingAbortiveDraw")

private val kanCountField: Field =
    engineField("kanCount")

private fun engineMethod(
    name: String,
    vararg parameterTypes: Class<*>?,
): Method =
    engineHierarchy()
        .firstNotNullOfOrNull { type ->
            runCatching { type.getDeclaredMethod(name, *parameterTypes) }.getOrNull()
        }?.apply { isAccessible = true }
        ?: error("Missing RiichiRoundEngine method: $name")

private fun engineField(name: String): Field =
    engineHierarchy()
        .firstNotNullOfOrNull { type ->
            runCatching { type.getDeclaredField(name) }.getOrNull()
        }?.apply { isAccessible = true }
        ?: error("Missing RiichiRoundEngine field: $name")

private fun engineHierarchy(): Sequence<Class<*>> = generateSequence(RiichiRoundEngine::class.java as Class<*>?) { type -> type.superclass }
