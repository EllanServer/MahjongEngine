package top.ellan.mahjong.perf

import top.ellan.mahjong.table.core.TableRuntimeServices
import top.ellan.mahjong.config.PluginSettings
import top.ellan.mahjong.gb.jni.GbFanEntry
import top.ellan.mahjong.gb.jni.GbTingCandidate
import top.ellan.mahjong.gb.jni.GbTingRequest
import top.ellan.mahjong.gb.jni.GbTingResponse
import top.ellan.mahjong.gb.runtime.GbNativeRulesGateway
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.layout.TableRenderLayout
import top.ellan.mahjong.riichi.ReactionResponse
import top.ellan.mahjong.riichi.ReactionType
import top.ellan.mahjong.riichi.RiichiPlayerState
import top.ellan.mahjong.riichi.RiichiRoundEngine
import top.ellan.mahjong.riichi.model.MahjongTile as RiichiTile
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.ScoringStick
import top.ellan.mahjong.riichi.model.TileInstance as RiichiTileInstance
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot
import top.ellan.mahjong.table.core.round.GbTableRoundController
import top.ellan.mahjong.table.render.TableRenderSnapshotFactory
import top.ellan.mahjong.table.render.TableRegionFingerprintService
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.EnumMap
import java.util.UUID

@Tag("perf")
class CorePerformanceBenchmarksTest {
    @Test
    fun `benchmark table render snapshot creation`() {
        val factory = TableRenderSnapshotFactory()
        val session = renderSnapshotSession()

        PerformanceBenchmarkSupport.run(
            name = "render.snapshot.create.started_session",
            batch = 250
        ) {
            factory.create(session, 1L, 0L)
        }
    }

    @Test
    fun `benchmark table render layout precompute`() {
        val snapshot = startedSnapshot()

        PerformanceBenchmarkSupport.run(
            name = "render.layout.precompute.started_snapshot",
            batch = 400
        ) {
            TableRenderLayout.precompute(snapshot)
        }
    }

    @Test
    fun `benchmark table region fingerprint precompute`() {
        val snapshot = startedSnapshot()
        val service = TableRegionFingerprintService()
        val session = fingerprintSession()

        PerformanceBenchmarkSupport.run(
            name = "render.region_fingerprints.precompute.started_snapshot",
            batch = 300
        ) {
            service.precomputeRegionFingerprints(session, snapshot)
        }
    }

    @Test
    fun `benchmark riichi round start`() {
        PerformanceBenchmarkSupport.run(
            name = "riichi.round_engine.start_round",
            batch = 120
        ) {
            RiichiRoundEngine(
                players = listOf(
                    RiichiPlayerState("East", "east"),
                    RiichiPlayerState("South", "south"),
                    RiichiPlayerState("West", "west"),
                    RiichiPlayerState("North", "north")
                ),
                rule = MahjongRule()
            ).startRound()
        }
    }

    @Test
    fun `benchmark riichi discard reaction chain`() {
        PerformanceBenchmarkSupport.run(
            name = "riichi.round_engine.discard_reaction_chain",
            batch = 80
        ) {
            val engine = riichiReactionChainEngine()
            val east = engine.seats[0]
            check(engine.discard(east.uuid, 0))
            val pending = engine.pendingReaction
            if (pending != null) {
                pending.options.keys.forEach { responderUuid ->
                    check(engine.react(responderUuid, ReactionResponse(ReactionType.SKIP)))
                }
            }
            PerformanceBenchmarkSupport.consume(engine.discards.size + engine.currentPlayerIndex)
        }
    }

    @Test
    fun `benchmark gb round start`() {
        PerformanceBenchmarkSupport.run(
            name = "gb.round_controller.start_round",
            batch = 120
        ) {
            gbController().startRound()
        }
    }

    @Test
    fun `benchmark gb bot discard suggestion`() {
        val controller = gbBotSuggestionController()
        val playerId = controller.playerAt(controller.currentSeat())!!

        PerformanceBenchmarkSupport.run(
            name = "gb.bot.suggest_discard.duplicate_hand",
            batch = 300
        ) {
            controller.suggestedBotDiscardIndex(playerId)
        }
    }

    private fun fingerprintSession(): MahjongTableSession {
        val plugin = mock(TableRuntimeServices::class.java)
        val settings = mock(PluginSettings::class.java)
        val session = mock(MahjongTableSession::class.java)
        `when`(plugin.settings()).thenReturn(settings)
        `when`(settings.craftEngineTableFurnitureId()).thenReturn("mahjongpaper:table_visual")
        `when`(settings.craftEngineSeatFurnitureId()).thenReturn("mahjongpaper:seat_chair")
        `when`(session.plugin()).thenReturn(plugin)
        `when`(session.settings()).thenReturn(settings)
        return session
    }

    private fun renderSnapshotSession(): MahjongTableSession {
        val session = mock(MahjongTableSession::class.java)
        val eastViewer = mock(Player::class.java)
        val southViewer = mock(Player::class.java)
        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val southId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        `when`(eastViewer.uniqueId).thenReturn(eastId)
        `when`(southViewer.uniqueId).thenReturn(southId)
        `when`(session.center()).thenReturn(Location(null, 0.0, 64.0, 0.0))
        `when`(session.viewers()).thenReturn(listOf(eastViewer, southViewer))
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.isRoundFinished()).thenReturn(false)
        `when`(session.remainingWallCount()).thenReturn(70)
        `when`(session.kanCount()).thenReturn(1)
        `when`(session.dicePoints()).thenReturn(7)
        `when`(session.roundIndex()).thenReturn(1)
        `when`(session.honbaCount()).thenReturn(2)
        `when`(session.dealerSeat()).thenReturn(SeatWind.SOUTH)
        `when`(session.currentSeat()).thenReturn(SeatWind.WEST)
        `when`(session.openDoorSeat()).thenReturn(SeatWind.WEST)
        `when`(session.waitingDisplaySummary()).thenReturn("waiting")
        `when`(session.ruleDisplaySummary()).thenReturn("rules")
        `when`(session.publicCenterText()).thenReturn("center")
        `when`(session.lastPublicDiscardPlayerIdValue()).thenReturn(eastId)
        `when`(session.lastPublicDiscardTile()).thenReturn(MahjongTile.RED_DRAGON)
        `when`(session.doraIndicators()).thenReturn(listOf(MahjongTile.M1, MahjongTile.P1, MahjongTile.S1))

        for (wind in SeatWind.values()) {
            val playerId = UUID.nameUUIDFromBytes(wind.name.toByteArray())
            `when`(session.playerAt(wind)).thenReturn(playerId)
            `when`(session.displayName(playerId)).thenReturn(wind.name)
            `when`(session.publicSeatStatus(wind)).thenReturn(wind.name)
            `when`(session.points(playerId)).thenReturn(25_000)
            `when`(session.isRiichi(playerId)).thenReturn(wind == SeatWind.EAST)
            `when`(session.isReady(playerId)).thenReturn(true)
            `when`(session.isQueuedToLeave(playerId)).thenReturn(false)
            `when`(session.selectedHandTileIndex(playerId)).thenReturn(-1)
            `when`(session.riichiDiscardIndex(playerId)).thenReturn(if (wind == SeatWind.EAST) 1 else -1)
            `when`(session.stickLayoutCount(wind)).thenReturn(if (wind == SeatWind.EAST) 3 else 0)
            `when`(session.hand(playerId)).thenReturn(List(13) { MahjongTile.M1 })
            `when`(session.discards(playerId)).thenReturn(listOf(MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST))
            `when`(session.fuuro(playerId)).thenReturn(emptyList())
            `when`(session.scoringSticks(playerId)).thenReturn(
                if (wind == SeatWind.EAST) listOf(ScoringStick.P1000) else emptyList(),
            )
            `when`(session.cornerSticks(wind)).thenReturn(
                if (wind == SeatWind.EAST) listOf(ScoringStick.P100, ScoringStick.P100) else emptyList(),
            )
        }
        return session
    }

    private fun gbController(): GbTableRoundController {
        val seats = EnumMap<SeatWind, UUID>(SeatWind::class.java)
        val names = mutableMapOf<UUID, String>()
        SeatWind.values().forEach { wind ->
            val playerId = UUID.nameUUIDFromBytes(wind.name.toByteArray())
            seats[wind] = playerId
            names[playerId] = wind.name
        }
        return GbTableRoundController(MahjongRule(), seats, names, object : GbNativeRulesGateway() {})
    }

    private fun riichiReactionChainEngine(): RiichiRoundEngine {
        val engine = RiichiRoundEngine(
            players = listOf(
                RiichiPlayerState("East", "east"),
                RiichiPlayerState("South", "south"),
                RiichiPlayerState("West", "west"),
                RiichiPlayerState("North", "north")
            ),
            rule = MahjongRule()
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

        east.hands += riichiTiles(
            RiichiTile.M2,
            RiichiTile.P1,
            RiichiTile.P2,
            RiichiTile.P3,
            RiichiTile.S1,
            RiichiTile.S2,
            RiichiTile.S3,
            RiichiTile.EAST,
            RiichiTile.SOUTH,
            RiichiTile.WEST,
            RiichiTile.NORTH,
            RiichiTile.WHITE_DRAGON,
            RiichiTile.GREEN_DRAGON,
            RiichiTile.RED_DRAGON
        )
        south.hands += riichiTiles(
            RiichiTile.M1,
            RiichiTile.M3,
            RiichiTile.P4,
            RiichiTile.P5,
            RiichiTile.P6,
            RiichiTile.S4,
            RiichiTile.S5,
            RiichiTile.S6,
            RiichiTile.EAST,
            RiichiTile.SOUTH,
            RiichiTile.WEST,
            RiichiTile.NORTH,
            RiichiTile.WHITE_DRAGON
        )
        west.hands += riichiTiles(
            RiichiTile.M4,
            RiichiTile.M5,
            RiichiTile.M6,
            RiichiTile.P1,
            RiichiTile.P4,
            RiichiTile.P7,
            RiichiTile.S1,
            RiichiTile.S4,
            RiichiTile.S7,
            RiichiTile.EAST,
            RiichiTile.SOUTH,
            RiichiTile.WEST,
            RiichiTile.NORTH
        )
        north.hands += riichiTiles(
            RiichiTile.M7,
            RiichiTile.M8,
            RiichiTile.M9,
            RiichiTile.P7,
            RiichiTile.P8,
            RiichiTile.P9,
            RiichiTile.S7,
            RiichiTile.S8,
            RiichiTile.S9,
            RiichiTile.EAST,
            RiichiTile.SOUTH,
            RiichiTile.WEST,
            RiichiTile.GREEN_DRAGON
        )
        engine.wall.clear()
        engine.wall += RiichiTileInstance(mahjongTile = RiichiTile.P9)
        return engine
    }

    private fun riichiTiles(vararg tiles: RiichiTile): List<RiichiTileInstance> =
        tiles.map { RiichiTileInstance(mahjongTile = it) }

    private fun gbBotSuggestionController(): GbTableRoundController {
        val seats = EnumMap<SeatWind, UUID>(SeatWind::class.java)
        val names = mutableMapOf<UUID, String>()
        SeatWind.values().forEach { wind ->
            val playerId = UUID.nameUUIDFromBytes(("bot-" + wind.name).toByteArray())
            seats[wind] = playerId
            names[playerId] = wind.name
        }
        val wall = List(136) { MahjongTile.M1 }
        val controller = GbTableRoundController(
            MahjongRule(),
            seats,
            names,
            object : GbNativeRulesGateway() {
                override fun evaluateTingNative(
                    request: GbTingRequest,
                ): GbTingResponse {
                    var score = 0
                    repeat(512) {
                        request.handTiles.forEachIndexed { index, tile ->
                            score += tile.hashCode() * (index + 1)
                        }
                        request.melds.forEachIndexed { index, meld ->
                            score += meld.type.hashCode() * (index + 3)
                            meld.tiles.forEach { tile ->
                                score += tile.hashCode()
                            }
                        }
                    }
                    PerformanceBenchmarkSupport.consume(score)
                    val fan = score.mod(3) + 1
                    return GbTingResponse(
                        true,
                        listOf(
                            GbTingCandidate(
                                "M1",
                                fan,
                                listOf(GbFanEntry("TEST", fan))
                            )
                        ),
                        null
                    )
                }
            },
            { 7 },
            { wall }
        )
        controller.startRound()
        return controller
    }

    private fun startedSnapshot(): TableRenderSnapshot {
        val seats = EnumMap<SeatWind, TableSeatRenderSnapshot>(SeatWind::class.java)
        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        seats[SeatWind.EAST] = seatSnapshot(
            wind = SeatWind.EAST,
            playerId = eastId,
            hand = List(13) { MahjongTile.M1 },
            discards = listOf(MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST, MahjongTile.NORTH),
            riichi = true,
            riichiDiscardIndex = 1,
            scoringSticks = listOf(ScoringStick.P1000),
            cornerSticks = listOf(ScoringStick.P100, ScoringStick.P100)
        )
        seats[SeatWind.SOUTH] = seatSnapshot(
            SeatWind.SOUTH,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            hand = List(13) { MahjongTile.P1 },
            discards = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3)
        )
        seats[SeatWind.WEST] = seatSnapshot(
            SeatWind.WEST,
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            hand = List(13) { MahjongTile.S1 },
            discards = listOf(MahjongTile.P1, MahjongTile.P2)
        )
        seats[SeatWind.NORTH] = seatSnapshot(
            SeatWind.NORTH,
            UUID.fromString("00000000-0000-0000-0000-000000000004"),
            hand = List(13) { MahjongTile.EAST },
            discards = listOf(MahjongTile.S1)
        )
        return TableRenderSnapshot(
            1L,
            0L,
            "world",
            0.0,
            64.0,
            0.0,
            true,
            false,
            false,
            70,
            1,
            7,
            7,
            1,
            2,
            SeatWind.SOUTH,
            SeatWind.WEST,
            SeatWind.WEST,
            "waiting",
            "rules",
            "center",
            eastId,
            MahjongTile.RED_DRAGON,
            listOf(MahjongTile.M1, MahjongTile.P1, MahjongTile.S1),
            seats
        )
    }

    private fun seatSnapshot(
        wind: SeatWind,
        playerId: UUID,
        hand: List<MahjongTile> = emptyList(),
        discards: List<MahjongTile> = emptyList(),
        riichi: Boolean = false,
        riichiDiscardIndex: Int = -1,
        scoringSticks: List<ScoringStick> = emptyList(),
        cornerSticks: List<ScoringStick> = emptyList()
    ) = TableSeatRenderSnapshot(
        wind,
        playerId,
        wind.name.lowercase(),
        wind.name,
        25000,
        riichi,
        true,
        false,
        true,
        "viewer-a;viewer-b",
        -1,
        emptyList(),
        riichiDiscardIndex,
        scoringSticks.size + cornerSticks.size,
        emptyList(),
        hand,
        discards,
        emptyList(),
        scoringSticks,
        cornerSticks
    )
}


