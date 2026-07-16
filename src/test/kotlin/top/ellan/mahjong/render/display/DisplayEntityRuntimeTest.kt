package top.ellan.mahjong.render.display

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.mockito.Mockito.mock
import top.ellan.mahjong.compat.CraftEngineService
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayEntityRuntimeTest {
    @Test
    fun `CraftEngine service presence does not force visibility resync`() {
        val plugin = mock(Plugin::class.java)
        val craftEngine = mock(CraftEngineService::class.java)
        val runtime = runtime(plugin, craftEngine)

        assertFalse(runtime.requiresVisibilityResync())
    }

    @Test
    fun `runtime can explicitly request a lifecycle visibility resync`() {
        val plugin = mock(Plugin::class.java)
        val runtime =
            object : DisplayEntityRuntime {
                override fun bukkitPlugin(): Plugin = plugin

                override fun requiresVisibilityResync(): Boolean = true
            }

        assertTrue(runtime.requiresVisibilityResync())
    }

    @Test
    fun `reconcile does not copy online players when spec keeps visibility unchanged`() {
        val plugin = mock(Plugin::class.java)
        val entity = mock(Entity::class.java)
        var onlinePlayerReads = 0
        val runtime =
            object : DisplayEntityRuntime {
                override fun bukkitPlugin(): Plugin = plugin

                override fun onlinePlayers(): Collection<Player> {
                    onlinePlayerReads++
                    return emptyList()
                }
            }
        val spec =
            object : DisplayEntities.EntitySpec {
                override fun spawn(runtime: DisplayEntityRuntime): Entity? = null

                override fun canReuse(
                    runtime: DisplayEntityRuntime,
                    entity: Entity,
                ): Boolean = true

                override fun apply(
                    runtime: DisplayEntityRuntime,
                    entity: Entity,
                ) = Unit

                override fun managesOwnReuse(): Boolean = true
            }

        assertTrue(DisplayEntities.reconcile(runtime, listOf(entity), listOf(spec)))
        assertEquals(0, onlinePlayerReads)
    }

    private fun runtime(
        plugin: Plugin,
        craftEngine: CraftEngineService,
    ): DisplayEntityRuntime =
        object : DisplayEntityRuntime {
            override fun bukkitPlugin(): Plugin = plugin

            override fun craftEngineSupplier(): Supplier<CraftEngineService> = Supplier { craftEngine }
        }
}
