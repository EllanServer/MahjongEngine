package top.ellan.mahjong.command

import org.mockito.Mockito.mock
import top.ellan.mahjong.debug.DebugService
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.runtime.AsyncService
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.core.MahjongTableManager
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural / meta tests for the subcommand registry. These do not invoke any
 * subcommand executor; instead they prove the production wiring matches the
 * filesystem and the help metadata stays consistent.
 *
 * The intent is that adding a new subcommand class without registering it in
 * MahjongCommand.productionSubcommands(...), or marking it admin-only without
 * adding the matching command.help.* key to ADMIN_HELP_KEY_SET, fails this
 * test before it reaches a server.
 */
class SubcommandRegistryTest {
    private val factoryGeneratedCommands = setOf("ron", "pon", "minkan", "skip")
    private val context = newContext()
    private val production = MahjongCommand.productionSubcommands(context)

    @Test
    fun `every concrete subcommand class is registered`() {
        val subcommandDir = File("src/main/java/top/ellan/mahjong/command/subcommand")
        assertTrue(subcommandDir.isDirectory, "expected subcommand sources at ${subcommandDir.absolutePath}")

        // Concrete = ends in Subcommand.java but is not the abstract base.
        val concreteClassNames =
            subcommandDir
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith("Subcommand.java") && !it.name.startsWith("Abstract") }
                ?.map { it.nameWithoutExtension }
                ?.toSet()
                ?: emptySet()

        assertEquals(
            34,
            concreteClassNames.size,
            "expected 34 concrete subcommand source files; found ${concreteClassNames.size}: $concreteClassNames",
        )
        assertTrue("SimpleReactionSubcommand" in concreteClassNames, "expected SimpleReactionSubcommand to be present")
        assertEquals(
            concreteClassNames.size - 1 + factoryGeneratedCommands.size,
            production.size,
            "MahjongCommand.productionSubcommands() must account for factory-generated reaction commands",
        )
        assertTrue(
            production.map { it.name() }.containsAll(factoryGeneratedCommands),
            "SimpleReactionSubcommand should generate $factoryGeneratedCommands",
        )
    }

    @Test
    fun `subcommand names are unique`() {
        val byName = production.groupBy { it.name() }
        val duplicates = byName.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate subcommand names detected: $duplicates")
    }

    @Test
    fun `subcommand aliases are unique across the registry`() {
        val seen = mutableSetOf<String>()
        val collisions = mutableListOf<String>()
        for (command in production) {
            val keys = mutableListOf(command.name())
            keys.addAll(command.aliases())
            for (key in keys) {
                val normalized = key.lowercase(Locale.ROOT)
                if (!seen.add(normalized)) {
                    collisions.add("$normalized (from ${command.name()})")
                }
            }
        }
        assertTrue(collisions.isEmpty(), "alias collisions detected: $collisions")
    }

    @Test
    fun `every admin-only subcommand has a matching admin help key`() {
        val mismatches = mutableListOf<String>()
        for (command in production) {
            if (!command.adminOnly()) continue
            val helpKey = "command.help.${command.name()}"
            // help is optional (e.g. "help" itself) but every admin-only command
            // that *has* a help line must mark it admin so non-admins do not see it.
            if (helpKey in MahjongCommandContext.HELP_KEY_ORDER && helpKey !in MahjongCommandContext.ADMIN_HELP_KEY_SET) {
                mismatches.add("$helpKey listed in HELP_KEY_ORDER but missing from ADMIN_HELP_KEY_SET")
            }
        }
        assertTrue(mismatches.isEmpty(), "admin/help inconsistencies: $mismatches")
    }

    @Test
    fun `every help key in ADMIN_HELP_KEY_SET maps to an admin-only subcommand`() {
        val mismatches = mutableListOf<String>()
        for (helpKey in MahjongCommandContext.ADMIN_HELP_KEY_SET) {
            val name = helpKey.removePrefix("command.help.")
            val command = production.firstOrNull { it.name() == name }
            assertNotNull(command, "ADMIN_HELP_KEY_SET references missing subcommand $name")
            if (!command.adminOnly()) {
                mismatches.add("$name is in ADMIN_HELP_KEY_SET but not adminOnly()")
            }
        }
        assertTrue(mismatches.isEmpty(), "admin/help inconsistencies: $mismatches")
    }

    @Test
    fun `every HELP_KEY_ORDER entry maps to a registered subcommand`() {
        val knownNames = production.map { it.name() }.toSet()
        val unknown =
            MahjongCommandContext.HELP_KEY_ORDER
                .map { it.removePrefix("command.help.") }
                .filter { it !in knownNames }
        assertTrue(unknown.isEmpty(), "HELP_KEY_ORDER references unknown subcommands: $unknown")
    }

    @Test
    fun `every subcommand exposes a non-blank name and non-null suggestions provider`() {
        for (command in production) {
            assertTrue(command.name().isNotBlank(), "subcommand has blank name: $command")
            assertNotNull(command.executor(), "${command.name()} has null executor")
            assertNotNull(command.suggestions(), "${command.name()} has null suggestion provider")
        }
    }

    private fun newContext(): MahjongCommandContext =
        MahjongCommandContext(
            mock(MessageService::class.java),
            mock(MahjongTableManager::class.java),
            mock(DebugService::class.java),
            mock(AsyncService::class.java),
            mock(ServerScheduler::class.java),
            { null },
            { null },
            { null },
            null,
        )
}
