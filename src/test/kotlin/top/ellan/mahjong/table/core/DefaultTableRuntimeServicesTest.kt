package top.ellan.mahjong.table.core

import org.bukkit.plugin.Plugin
import org.mockito.Mockito.mock
import top.ellan.mahjong.compat.CraftEngineService
import top.ellan.mahjong.compat.protection.ProtectionService
import top.ellan.mahjong.config.PluginSettings
import top.ellan.mahjong.db.DatabaseService
import top.ellan.mahjong.debug.DebugService
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.metrics.MetricsCollector
import top.ellan.mahjong.pluginSettings
import top.ellan.mahjong.runtime.AsyncService
import top.ellan.mahjong.runtime.ServerScheduler
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class DefaultTableRuntimeServicesTest {
    @Test
    fun `reload-sensitive services are read from current suppliers`() {
        val firstSettings = pluginSettings()
        val secondSettings = pluginSettings("debug.enabled" to true)
        val settings = AtomicReference(firstSettings)
        val firstCraftEngine = mock(CraftEngineService::class.java)
        val secondCraftEngine = mock(CraftEngineService::class.java)
        val craftEngine = AtomicReference(firstCraftEngine)
        val firstDatabase = mock(DatabaseService::class.java)
        val database = AtomicReference<DatabaseService?>(firstDatabase)
        val firstTableManager = mock(MahjongTableManager::class.java)
        val secondTableManager = mock(MahjongTableManager::class.java)
        val tableManager = AtomicReference(firstTableManager)
        val services =
            DefaultTableRuntimeServices(
                mock(Plugin::class.java),
                mock(MessageService::class.java),
                mock(DebugService::class.java),
                mock(ServerScheduler::class.java),
                mock(AsyncService::class.java),
                { settings.get() },
                { craftEngine.get() },
                mock(ProtectionService::class.java),
                { database.get() },
                mock(MetricsCollector::class.java),
                { tableManager.get() },
                null,
            )

        assertSame(firstSettings, services.settings())
        assertSame(firstCraftEngine, services.craftEngine())
        assertSame(firstDatabase, services.database())
        assertSame(firstTableManager, services.tableManager())

        settings.set(secondSettings)
        craftEngine.set(secondCraftEngine)
        database.set(null)
        tableManager.set(secondTableManager)

        assertSame(secondSettings, services.settings())
        assertSame(secondCraftEngine, services.craftEngine())
        assertNull(services.database())
        assertSame(secondTableManager, services.tableManager())
    }
}
