package top.ellan.mahjong.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.debug.DebugService
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.pluginSettings
import top.ellan.mahjong.riichi.RoundResolution
import top.ellan.mahjong.riichi.model.DoubleYakuman
import top.ellan.mahjong.riichi.model.ExhaustiveDraw
import top.ellan.mahjong.riichi.model.MahjongRule
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.ScoreItem
import top.ellan.mahjong.riichi.model.ScoreSettlement
import top.ellan.mahjong.riichi.model.YakuSettlement
import top.ellan.mahjong.runtime.AsyncService
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableFinalStanding
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.ellan.mahjong.model.MahjongTile as DisplayMahjongTile

class DatabaseIntegrationTest {
    private lateinit var tempDir: java.nio.file.Path
    private lateinit var async: AsyncService
    private lateinit var service: DatabaseService

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("mahjongpaper-db-test")
        async = AsyncService(Logger.getLogger("DatabaseIntegrationTest-Async"))

        service =
            DatabaseService(
                pluginSettings(
                    "database.pool.maxSize" to 2,
                    "database.pool.minIdle" to 1,
                    "database.connection.type" to "h2",
                    "database.h2.path" to "data/test-db",
                ).database(),
                mock(DebugService::class.java),
                async,
                Logger.getLogger("DatabaseIntegrationTest"),
                tempDir,
                true,
                "SILVER",
                "GOLD",
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
        async.close()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `persist round result sync writes round and player settlement rows`() {
        val session = mock(MahjongTableSession::class.java)
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        `when`(session.id()).thenReturn("TABLE01")
        `when`(session.roundDisplay()).thenReturn("East 1")
        `when`(session.dealerName()).thenReturn("Dealer")
        `when`(session.remainingWallCount()).thenReturn(42)
        `when`(session.dicePoints()).thenReturn(7)
        `when`(session.doraIndicators()).thenReturn(listOf(DisplayMahjongTile.M1, DisplayMahjongTile.P1))
        `when`(session.uraDoraIndicators()).thenReturn(listOf(DisplayMahjongTile.S1))
        `when`(session.displayName(playerId)).thenReturn("Alice")

        val resolution =
            RoundResolution(
                title = "Ron",
                scoreSettlement =
                    ScoreSettlement(
                        "Ron",
                        listOf(ScoreItem("Alice", playerId.toString(), 25000, 8000)),
                    ),
                yakuSettlements =
                    listOf(
                        YakuSettlement(
                            displayName = "Alice",
                            uuid = playerId.toString(),
                            yakuList = listOf("reach"),
                            yakumanList = emptyList(),
                            doubleYakumanList = listOf(DoubleYakuman.SUANKO_TANKI),
                            riichi = true,
                            winningTile = MahjongTile.M1,
                            hands = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3),
                            fuuroList = listOf(true to listOf(MahjongTile.P1, MahjongTile.P2, MahjongTile.P3)),
                            doraIndicators = listOf(MahjongTile.M1),
                            uraDoraIndicators = listOf(MahjongTile.P1),
                            fu = 40,
                            han = 3,
                            score = 7700,
                        ),
                    ),
                draw = ExhaustiveDraw.NORMAL,
            )

        service.persistRoundResultSync("round-operation-1", session, resolution)
        service.persistRoundResultSync("round-operation-1", session, resolution)

        withConnection(service) { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT table_id, resolution_title, round_display, dealer_name, wall_count, dice_points, dora_indicators FROM round_history",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("TABLE01", result.getString("table_id"))
                        assertEquals("Ron", result.getString("resolution_title"))
                        assertEquals("East 1", result.getString("round_display"))
                        assertEquals("Dealer", result.getString("dealer_name"))
                        assertEquals(42, result.getInt("wall_count"))
                        assertEquals(7, result.getInt("dice_points"))
                        assertEquals("m1 p1", result.getString("dora_indicators"))
                        assertFalse(result.next())
                    }
                statement
                    .executeQuery(
                        "SELECT player_uuid, display_name, score_total, winning, yaku_summary, meld_summary FROM round_player_result",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(playerId.toString(), result.getString("player_uuid"))
                        assertEquals("Alice", result.getString("display_name"))
                        assertEquals(33000, result.getInt("score_total"))
                        assertEquals(true, result.getBoolean("winning"))
                        assertEquals("reach, SUANKO_TANKI", result.getString("yaku_summary"))
                        assertEquals("open:p1 p2 p3", result.getString("meld_summary"))
                    }
            }
        }
    }

    @Test
    fun `queued round persistence snapshot is independent from later session state`() {
        val session = mock(MahjongTableSession::class.java)
        val resolution = RoundResolution("DRAW", draw = ExhaustiveDraw.NORMAL)

        `when`(session.id()).thenReturn("TABLE-OLD")
        `when`(session.roundDisplay()).thenReturn("East 4")
        `when`(session.dealerName()).thenReturn("Old Dealer")
        `when`(session.remainingWallCount()).thenReturn(0)
        `when`(session.dicePoints()).thenReturn(5)
        `when`(session.doraIndicators()).thenReturn(listOf(DisplayMahjongTile.M1))
        `when`(session.uraDoraIndicators()).thenReturn(listOf(DisplayMahjongTile.P1))

        val snapshot = service.snapshotRoundResult(session, resolution)

        `when`(session.id()).thenReturn("TABLE-NEW")
        `when`(session.roundDisplay()).thenReturn("South 1")
        `when`(session.dealerName()).thenReturn("New Dealer")
        `when`(session.remainingWallCount()).thenReturn(69)
        `when`(session.dicePoints()).thenReturn(12)
        `when`(session.doraIndicators()).thenReturn(listOf(DisplayMahjongTile.S1))
        `when`(session.uraDoraIndicators()).thenReturn(emptyList())

        assertEquals("TABLE-OLD", snapshot.tableId())
        assertEquals("East 4", snapshot.roundDisplay())
        assertEquals("Old Dealer", snapshot.dealerName())
        assertEquals(0, snapshot.remainingWallCount())
        assertEquals(5, snapshot.dicePoints())
        assertEquals(listOf(DisplayMahjongTile.M1), snapshot.doraIndicators())
        assertEquals(listOf(DisplayMahjongTile.P1), snapshot.uraDoraIndicators())
        assertEquals("DRAW", snapshot.resolution().title)
    }

    @Test
    fun `persist match ranks sync updates profile and writes history`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val thirdId = UUID.fromString("00000000-0000-0000-0000-000000000023")
        val fourthId = UUID.fromString("00000000-0000-0000-0000-000000000024")
        val standings =
            listOf(
                TableFinalStanding(playerId, "Alice", 1, 42000, 57.0, false),
                TableFinalStanding(secondId, "Bob", 2, 30000, 10.0, false),
                TableFinalStanding(thirdId, "Carol", 3, 20000, -20.0, false),
                TableFinalStanding(fourthId, "Dave", 4, 8000, -47.0, false),
            )

        service.persistMatchRanksSync(
            "rank-operation-1",
            "TABLE99",
            MahjongVariant.RIICHI,
            MahjongRule.GameLength.TWO_WIND,
            standings,
        )
        service.persistMatchRanksSync(
            "rank-operation-1",
            "TABLE99",
            MahjongVariant.RIICHI,
            MahjongRule.GameLength.TWO_WIND,
            standings,
        )

        val profile = service.loadRankProfile(playerId, "Alice")
        assertNotNull(profile)
        assertEquals(MahjongSoulRankRules.Tier.ADEPT, profile.tier())
        assertEquals(1, profile.level())
        assertEquals(392, profile.rankPoints())
        assertEquals(1, profile.totalMatches())

        withConnection(service) { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT table_id, mode_code, room_code, match_length, place, rank_point_change FROM rank_history WHERE player_uuid = '$playerId'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("TABLE99", result.getString("table_id"))
                        assertEquals("RIICHI", result.getString("mode_code"))
                        assertEquals("GOLD", result.getString("room_code"))
                        assertEquals("SOUTH", result.getString("match_length"))
                        assertEquals(1, result.getInt("place"))
                        assertEquals(112, result.getInt("rank_point_change"))
                    }
                statement.executeQuery("SELECT COUNT(*) FROM rank_history").use { result ->
                    assertTrue(result.next())
                    assertEquals(4, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `mahjong soul ranks ignore gb and sichuan scores while riichi still persists`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000029")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val thirdId = UUID.fromString("00000000-0000-0000-0000-000000000031")
        val fourthId = UUID.fromString("00000000-0000-0000-0000-000000000032")

        service.persistMatchRanksSync(
            "TABLE-RIICHI",
            MahjongVariant.RIICHI,
            MahjongRule.GameLength.TWO_WIND,
            listOf(
                TableFinalStanding(playerId, "Alice", 1, 42000, 57.0, false),
                TableFinalStanding(secondId, "Bob", 2, 30000, 10.0, false),
                TableFinalStanding(thirdId, "Carol", 3, 20000, -20.0, false),
                TableFinalStanding(fourthId, "Dave", 4, 8000, -47.0, false),
            ),
        )
        service.persistMatchRanksSync(
            "TABLE-GB",
            MahjongVariant.GB,
            MahjongRule.GameLength.FOUR_WIND,
            listOf(
                TableFinalStanding(secondId, "Bob", 1, 650, 150.0, false),
                TableFinalStanding(thirdId, "Carol", 2, 550, 50.0, false),
                TableFinalStanding(fourthId, "Dave", 3, 450, -50.0, false),
                TableFinalStanding(playerId, "Alice", 4, 350, -150.0, false),
            ),
        )
        service.persistMatchRanksSync(
            "TABLE-SICHUAN",
            MahjongVariant.SICHUAN,
            MahjongRule.GameLength.TWO_WIND,
            listOf(
                TableFinalStanding(secondId, "Bob", 1, 40000, 15000.0, false),
                TableFinalStanding(thirdId, "Carol", 2, 30000, 5000.0, false),
                TableFinalStanding(fourthId, "Dave", 3, 20000, -5000.0, false),
                TableFinalStanding(playerId, "Alice", 4, 10000, -15000.0, false),
            ),
        )

        val riichiProfile = service.loadRankProfile(playerId, "Alice", MahjongVariant.RIICHI)
        val gbProfile = service.loadRankProfile(playerId, "Alice", MahjongVariant.GB)
        val sichuanProfile = service.loadRankProfile(playerId, "Alice", MahjongVariant.SICHUAN)

        assertEquals(1, riichiProfile.totalMatches())
        assertEquals(1, riichiProfile.firstPlaces())
        assertEquals(392, riichiProfile.rankPoints())
        assertEquals(0, gbProfile.totalMatches())
        assertEquals(0, gbProfile.fourthPlaces())
        assertEquals(0, gbProfile.rankPoints())
        assertEquals(0, sichuanProfile.totalMatches())
        assertEquals(0, sichuanProfile.fourthPlaces())
        assertEquals(0, sichuanProfile.rankPoints())
        assertEquals(
            setOf(MahjongVariant.RIICHI, MahjongVariant.GB, MahjongVariant.SICHUAN),
            service.loadRankProfiles(playerId, "Alice").keys,
        )

        withConnection(service) { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT mode_code, COUNT(*) AS rows FROM rank_history WHERE player_uuid = '$playerId' GROUP BY mode_code",
                    ).use { result ->
                        val counts = mutableMapOf<String, Int>()
                        while (result.next()) {
                            counts[result.getString("mode_code")] = result.getInt("rows")
                        }
                        assertEquals(1, counts["RIICHI"])
                        assertEquals(null, counts["GB"])
                        assertEquals(null, counts["SICHUAN"])
                    }
            }
        }
    }

    @Test
    fun `leaderboard returns ranked profiles for requested mode`() {
        val firstId = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000042")
        val thirdId = UUID.fromString("00000000-0000-0000-0000-000000000043")
        val fourthId = UUID.fromString("00000000-0000-0000-0000-000000000044")

        service.persistMatchRanksSync(
            "TABLE-LB",
            MahjongVariant.RIICHI,
            MahjongRule.GameLength.TWO_WIND,
            listOf(
                TableFinalStanding(firstId, "Alice", 1, 52000, 87.0, false),
                TableFinalStanding(secondId, "Bob", 2, 30000, 10.0, false),
                TableFinalStanding(thirdId, "Carol", 3, 18000, -22.0, false),
                TableFinalStanding(fourthId, "Dave", 4, 0, -75.0, false),
            ),
        )

        val leaderboard = service.loadLeaderboard(MahjongVariant.RIICHI, 3)
        val gbLeaderboard = service.loadLeaderboard(MahjongVariant.GB, 3)

        assertEquals(3, leaderboard.size)
        assertEquals(1, leaderboard[0].position())
        assertEquals("Alice", leaderboard[0].profile().displayName())
        assertEquals(firstId, leaderboard[0].profile().playerId())
        assertEquals(0, gbLeaderboard.size)
    }

    @Test
    fun `InvSync rank projection updates leaderboard and history without becoming a second transition source`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000045")
        val previous = MahjongSoulRankProfile.defaultProfile(playerId, "InvSyncPlayer")
        val result =
            MahjongSoulRankRules.applyMatch(
                previous,
                MahjongSoulRankRules.Room.SILVER,
                MahjongSoulRankRules.MatchLength.EAST,
                1,
                42000,
                false,
            )

        service
            .persistRankProjectionAsync(
                "projection-op",
                "TABLE-INVSYNC",
                MahjongVariant.RIICHI,
                listOf(DatabaseService.RankProjectionEntry("InvSyncPlayer", result)),
            ).join()
        // A replay from a fresh rank-storage instance uses the same durable operation ID.
        service
            .persistRankProjectionAsync(
                "projection-op",
                "TABLE-INVSYNC",
                MahjongVariant.RIICHI,
                listOf(DatabaseService.RankProjectionEntry("InvSyncPlayer", result)),
            ).join()

        assertEquals(result.updated(), service.loadRankProfile(playerId, "InvSyncPlayer"))
        assertEquals(
            "InvSyncPlayer",
            service
                .loadLeaderboard(MahjongVariant.RIICHI, 10)
                .single()
                .profile()
                .displayName(),
        )

        val updated = result.updated()
        val repaired =
            MahjongSoulRankProfile(
                updated.playerId(),
                updated.displayName(),
                updated.tier(),
                updated.level(),
                updated.rankPoints() + 7,
                updated.totalMatches(),
                updated.firstPlaces(),
                updated.secondPlaces(),
                updated.thirdPlaces(),
                updated.fourthPlaces(),
            )
        service.repairRankProjectionAsync(mapOf(MahjongVariant.RIICHI to repaired)).join()
        assertEquals(repaired, service.loadRankProfile(playerId, "InvSyncPlayer"))
        withConnection(service) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) AS rows FROM rank_history WHERE player_uuid = '$playerId'").use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt("rows"))
                }
            }
        }
    }

    @Test
    fun `persist match ranks ignores incomplete human tables`() {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000025")

        service.persistMatchRanksSync(
            "TABLE-BOT",
            MahjongRule.GameLength.TWO_WIND,
            listOf(
                TableFinalStanding(playerId, "Alice", 1, 42000, 57.0, false),
                TableFinalStanding(UUID.fromString("00000000-0000-0000-0000-000000000026"), "Bot-1", 2, 30000, 10.0, true),
                TableFinalStanding(UUID.fromString("00000000-0000-0000-0000-000000000027"), "Bot-2", 3, 20000, -20.0, true),
                TableFinalStanding(UUID.fromString("00000000-0000-0000-0000-000000000028"), "Bot-3", 4, 8000, -47.0, true),
            ),
        )

        val profile = service.loadRankProfile(playerId, "Alice")
        assertEquals(0, profile.totalMatches())
        withConnection(service) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT player_uuid FROM rank_history").use { result ->
                    assertFalse(result.next())
                }
            }
        }
    }

    @Test
    fun `replace and load persistent tables round-trips through database`() {
        val legacyTournamentProfile = MahjongRule.RiichiProfile.valueOf("TOURNAMENT")
        val rule =
            MahjongRule().apply {
                length = MahjongRule.GameLength.SOUTH
                thinkingTime = MahjongRule.ThinkingTime.LONG
                startingPoints = 30000
                minPointsToWin = 35000
                minimumHan = MahjongRule.MinimumHan.TWO
                spectate = false
                redFive = MahjongRule.RedFive.FOUR
                openTanyao = true
                localYaku = true
                ronMode = MahjongRule.RonMode.MULTI_RON
                riichiProfile = legacyTournamentProfile
            }
        service.replacePersistentTables(
            listOf(
                DatabaseService.PersistentTableRecord(
                    "TABLE88",
                    "world",
                    100.5,
                    64.0,
                    -22.25,
                    UUID.fromString("00000000-0000-0000-0000-000000000088"),
                    MahjongVariant.GB,
                    rule,
                    true,
                ),
            ),
        )

        val loaded = service.loadPersistentTables()

        assertEquals(1, loaded.size)
        val table = loaded.single()
        assertEquals("TABLE88", table.id())
        assertEquals("world", table.worldName())
        assertEquals(100.5, table.x())
        assertEquals(64.0, table.y())
        assertEquals(-22.25, table.z())
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000088"), table.ownerId())
        assertEquals(MahjongVariant.GB, table.variant())
        assertTrue(table.botMatch())
        assertEquals(MahjongRule.GameLength.SOUTH, table.rule().length)
        assertEquals(MahjongRule.ThinkingTime.LONG, table.rule().thinkingTime)
        assertEquals(30000, table.rule().startingPoints)
        assertEquals(35000, table.rule().minPointsToWin)
        assertEquals(MahjongRule.MinimumHan.TWO, table.rule().minimumHan)
        assertEquals(false, table.rule().spectate)
        assertEquals(MahjongRule.RedFive.FOUR, table.rule().redFive)
        assertEquals(true, table.rule().openTanyao)
        assertEquals(true, table.rule().localYaku)
        assertEquals(MahjongRule.RonMode.MULTI_RON, table.rule().ronMode)
        assertEquals(legacyTournamentProfile, table.rule().riichiProfile)
    }

    private fun withConnection(
        service: DatabaseService,
        block: (java.sql.Connection) -> Unit,
    ) {
        val field = DatabaseService::class.java.getDeclaredField("dataSource")
        field.isAccessible = true
        val dataSource = field.get(service) as DataSource
        dataSource.connection.use(block)
    }
}
