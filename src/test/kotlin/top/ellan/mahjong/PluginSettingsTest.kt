package top.ellan.mahjong

import top.ellan.mahjong.config.PluginSettings
import top.ellan.mahjong.model.MahjongVariant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSettingsTest {
    @Test
    fun `from applies defaults and clamps startup rebuild batch size`() {
        val settings =
            pluginSettings(
                "tables.startupRebuildBatchSize" to 0,
                "ranking.enabled" to false,
            )

        assertEquals(1, settings.tableStartupRebuildBatchSize())
        assertFalse(settings.tableFreeMoveDuringRound())
        assertFalse(settings.rankingEnabled())
        assertTrue(settings.tablePersistenceEnabled())
        assertEquals("tables.yml", settings.tablePersistenceFile())
        assertEquals("mahjongpaper:", settings.craftEngineTileItemIdPrefix())
        assertEquals("mahjongpaper:", settings.craftEngineRiichiTileItemIdPrefix())
        assertEquals("mahjongpaper:", settings.craftEngineGbTileItemIdPrefix())
        assertEquals("mahjongpaper:", settings.craftEngineTileItemIdPrefix(MahjongVariant.RIICHI))
        assertEquals("mahjongpaper:", settings.craftEngineTileItemIdPrefix(MahjongVariant.GB))
        assertEquals("mahjongpaper:", settings.craftEngineTileItemIdPrefix(MahjongVariant.SICHUAN))
        assertEquals("mahjongpaper:table_visual", settings.craftEngineTableFurnitureId())
        assertEquals("mahjongpaper:seat_chair", settings.craftEngineSeatFurnitureId())
        assertEquals("SILVER", settings.rankingEastRoom())
        assertEquals("GOLD", settings.rankingSouthRoom())
        assertTrue(settings.rankingInvSyncEnabled())
        assertTrue(settings.rankingInvSyncFallbackToDatabase())
    }

    @Test
    fun `from can disable InvSync and database fallback independently`() {
        val settings =
            pluginSettings(
                "ranking.playerStorage.invSync.enabled" to false,
                "ranking.playerStorage.invSync.fallbackToDatabase" to false,
            )

        assertFalse(settings.rankingInvSyncEnabled())
        assertFalse(settings.rankingInvSyncFallbackToDatabase())
    }

    @Test
    fun `from supports legacy aliases for table persistence and batch size`() {
        val settings =
            pluginSettings(
                "tablePersistence.enabled" to false,
                "tablePersistence.file" to "custom.yml",
                "tables.startup-rebuild-batch-size" to 7,
                "tables.allow-free-move-during-round" to true,
                "craftengine.items.tile-item-id-prefix" to "custom:tile_",
                "craftengine.furniture.table-furniture-id" to "custom:table",
                "craftengine.furniture.seat-furniture-id" to "custom:chair",
                "ranking.eastRoom" to "jade",
                "ranking.southRoom" to "throne",
            )

        assertFalse(settings.tablePersistenceEnabled())
        assertEquals("custom.yml", settings.tablePersistenceFile())
        assertEquals(7, settings.tableStartupRebuildBatchSize())
        assertTrue(settings.tableFreeMoveDuringRound())
        assertEquals("custom:tile_", settings.craftEngineTileItemIdPrefix())
        assertEquals("custom:tile_", settings.craftEngineRiichiTileItemIdPrefix())
        assertEquals("custom:tile_", settings.craftEngineGbTileItemIdPrefix())
        assertEquals("custom:table", settings.craftEngineTableFurnitureId())
        assertEquals("custom:chair", settings.craftEngineSeatFurnitureId())
        assertEquals("jade", settings.rankingEastRoom())
        assertEquals("throne", settings.rankingSouthRoom())
    }

    @Test
    fun `from supports separate riichi and gb tile item prefixes`() {
        val settings =
            pluginSettings(
                "integrations.craftengine.items.tileItemIdPrefix" to "shared:",
                "integrations.craftengine.items.riichiTileItemIdPrefix" to "riichi:",
                "integrations.craftengine.items.gbTileItemIdPrefix" to "gb:",
            )

        assertEquals("shared:", settings.craftEngineTileItemIdPrefix())
        assertEquals("riichi:", settings.craftEngineTileItemIdPrefix(MahjongVariant.RIICHI))
        assertEquals("gb:", settings.craftEngineTileItemIdPrefix(MahjongVariant.GB))
        assertEquals("gb:", settings.craftEngineTileItemIdPrefix(MahjongVariant.SICHUAN))
    }

    @Test
    fun `from exposes grouped strong typed snapshots`() {
        val settings =
            pluginSettings(
                "debug.enabled" to true,
                "debug.categories" to listOf("database", "render"),
                "database.connection.type" to "mariadb",
                "database.connection.host" to "db.local",
                "database.connection.port" to 3307,
                "database.connection.name" to "mahjong",
                "database.credentials.username" to "mahjong",
                "database.credentials.password" to "secret",
                "tables.persistence.enabled" to false,
                "tables.persistence.file" to "persist.yml",
                "integrations.craftengine.bundle.folder" to "pack-a",
                "integrations.craftengine.compatibility.injectAntiCheatPacketEventsMappings" to false,
                "integrations.craftengine.furniture.preferHitboxInteraction" to false,
            )

        assertTrue(settings.debug().enabled())
        assertEquals(listOf("database", "render"), settings.debug().categories())
        assertEquals("mariadb", settings.database().type())
        assertEquals("db.local", settings.database().connection().host())
        assertEquals(3307, settings.database().connection().port())
        assertEquals("mahjong", settings.database().connection().name())
        assertEquals("mahjong", settings.database().credentials().username())
        assertEquals("secret", settings.database().credentials().password())
        assertFalse(settings.tables().persistence().enabled())
        assertEquals("persist.yml", settings.tables().persistence().file())
        assertEquals("pack-a", settings.craftEngine().bundleFolder())
        assertFalse(settings.craftEngine().injectAntiCheatPacketEventsMappings())
        assertFalse(settings.craftEngine().furniture().preferHitboxInteraction())
    }

    @Test
    fun `from parses and clamps game room settings including legacy aliases`() {
        val settings =
            pluginSettings(
                "gamerooms.enabled" to false,
                "gamerooms.restrict-new-tables" to false,
                "gamerooms.enter-exit-messages" to false,
                "gamerooms.leave-countdown-seconds" to 1,
                "gamerooms.default-radius" to 1,
                "gamerooms.default-height" to 2,
                "gamerooms.file" to "rooms-custom.yml",
            )

        assertFalse(settings.gameRooms().enabled())
        assertFalse(settings.gameRooms().restrictNewTables())
        assertFalse(settings.gameRooms().enterExitMessages())
        assertEquals(5, settings.gameRooms().leaveCountdownSeconds())
        assertEquals(2, settings.gameRooms().defaultRadius())
        assertEquals(3, settings.gameRooms().defaultHeight())
        assertEquals("rooms-custom.yml", settings.gameRooms().file())
    }

    @Test
    fun `from parses overhead view aliases and clamps camera settings`() {
        val settings =
            pluginSettings(
                "tables.overhead-view.enabled" to false,
                "tables.overhead-view.height" to 99.0,
                "tables.overhead-view.transition-ticks" to 0,
            )

        assertFalse(settings.tables().overheadView().enabled())
        assertEquals(6.0, settings.tables().overheadView().height())
        assertEquals(1, settings.tables().overheadView().transitionTicks())
    }

    @Test
    fun `load reads settings directly from a filesystem path`() {
        val configPath = Files.createTempFile("mahjongpaper-sparrow-yaml", ".yml")
        try {
            Files.writeString(
                configPath,
                """
                database:
                  connection:
                    type: mysql
                tables:
                  overheadView:
                    height: 5.75
                """.trimIndent(),
            )

            val settings = PluginSettings.load(configPath)

            assertEquals("mysql", settings.database().type())
            assertEquals(5.75, settings.tables().overheadView().height())
        } finally {
            Files.deleteIfExists(configPath)
        }
    }
}
