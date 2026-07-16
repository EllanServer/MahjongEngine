package top.ellan.mahjong.config

import net.momirealms.sparrow.yaml.SparrowYaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfigTemplateParseTest {
    @Test
    fun `all bundled config templates parse as yaml`() {
        val yaml = SparrowYaml.builder().build()
        listOf("config.yml", "config_zh_CN.yml", "config_zh_TW.yml").forEach { resource ->
            val stream =
                assertNotNull(
                    javaClass.classLoader.getResourceAsStream(resource),
                    "Missing config template resource: $resource",
                )
            val document = stream.use { yaml.load(it) }
            val settings = PluginSettings.from(document)

            assertEquals("mahjongpaper:", settings.craftEngineTileItemIdPrefix())
            assertEquals("mahjongpaper:", settings.craftEngineRiichiTileItemIdPrefix())
            assertEquals("mahjongpaper:", settings.craftEngineGbTileItemIdPrefix())
            assertEquals(4.5, settings.tables().overheadView().height())
            assertEquals(16, settings.tables().overheadView().transitionTicks())
        }
    }
}
