package top.ellan.mahjong.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.lenient
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.debug.DebugService
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.table.core.MahjongTableManager
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers MahjongCommand's permission, dispatch, and tab-completion behaviour without
 * exercising any of the 35 production subcommand implementations. Each subcommand
 * is replaced with a counter-backed fake so that "did dispatch" / "did suggest" can
 * be asserted directly. Bukkit Player/CommandSender are mocked with Mockito.
 *
 * Mocks are recreated per test to keep verification state isolated; mixing
 * times(...) verifications across tests on shared mocks reliably produces
 * UnfinishedVerification noise.
 */
class MahjongCommandDispatchTest {
    private lateinit var messages: MessageService
    private lateinit var tableManager: MahjongTableManager
    private lateinit var debug: DebugService
    private lateinit var context: MahjongCommandContext
    private lateinit var command: MahjongCommand
    private lateinit var bukkitCommand: Command
    private val joinCalls = AtomicInteger()
    private val createCalls = AtomicInteger()
    private val joinSuggestion = listOf("alpha", "beta")

    @BeforeEach
    fun setUp() {
        messages = mock(MessageService::class.java)
        tableManager = mock(MahjongTableManager::class.java)
        debug = mock(DebugService::class.java)
        bukkitCommand = mock(Command::class.java)
        stubHelpRendering()
        context =
            MahjongCommandContext(
                messages,
                tableManager,
                debug,
                mock(top.ellan.mahjong.runtime.AsyncService::class.java),
                mock(top.ellan.mahjong.runtime.ServerScheduler::class.java),
                { null },
                { null },
                { null },
                null,
            )
        joinCalls.set(0)
        createCalls.set(0)
        val join =
            MahjongSubcommand(
                "join",
                listOf("j"),
                false,
                true,
                { _, _, _ -> joinCalls.incrementAndGet() },
                { _, _ -> joinSuggestion },
            )
        val create =
            MahjongSubcommand(
                "create",
                listOf(),
                true,
                false,
                { _, _, _ -> createCalls.incrementAndGet() },
                { _, _ -> listOf("created") },
            )
        command = MahjongCommand(context, listOf(join, create))
    }

    @Test
    fun `sender without root permission is rejected before any dispatch`() {
        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("mahjongpaper.command")).thenReturn(false)

        assertTrue(command.onCommand(sender, bukkitCommand, "mahjong", arrayOf("join")))

        verify(messages).send(sender, "command.admin_required")
        assertEquals(0, joinCalls.get())
        assertEquals(0, createCalls.get())
    }

    @Test
    fun `empty args from player triggers help`() {
        val player = playerWith(rootPermission = true)

        command.onCommand(player, bukkitCommand, "mahjong", emptyArray())

        verify(messages, times(1)).render(Locale.ENGLISH, "command.help.header")
    }

    @Test
    fun `empty args from console replies with usage`() {
        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("mahjongpaper.command")).thenReturn(true)

        command.onCommand(sender, bukkitCommand, "mahjong", emptyArray())

        verify(messages).send(sender, "command.usage")
    }

    @Test
    fun `unknown sub from console emits only_players`() {
        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("mahjongpaper.command")).thenReturn(true)

        command.onCommand(sender, bukkitCommand, "mahjong", arrayOf("nothing"))

        verify(messages).send(sender, "common.only_players")
    }

    @Test
    fun `unknown sub from player falls through to help`() {
        val player = playerWith(rootPermission = true)
        command.onCommand(player, bukkitCommand, "mahjong", arrayOf("nothing"))

        verify(messages, times(1)).render(Locale.ENGLISH, "command.help.header")
        verify(debug).log(safeEq("command"), anyString())
    }

    @Test
    fun `playerOnly sub rejects console with only_players`() {
        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("mahjongpaper.command")).thenReturn(true)

        command.onCommand(sender, bukkitCommand, "mahjong", arrayOf("join"))

        verify(messages).send(sender, "common.only_players")
        assertEquals(0, joinCalls.get())
    }

    @Test
    fun `adminOnly sub blocks non-admin player and notifies`() {
        val player = playerWith(rootPermission = true, adminPermission = false)

        command.onCommand(player, bukkitCommand, "mahjong", arrayOf("create"))

        verify(messages).send(player, "command.admin_required")
        assertEquals(0, createCalls.get())
    }

    @Test
    fun `happy path executes the matching subcommand once`() {
        val player = playerWith(rootPermission = true)

        command.onCommand(player, bukkitCommand, "mahjong", arrayOf("join", "extra"))

        assertEquals(1, joinCalls.get())
        assertEquals(0, createCalls.get())
    }

    @Test
    fun `subcommand alias dispatches to the same executor`() {
        val player = playerWith(rootPermission = true)

        command.onCommand(player, bukkitCommand, "mahjong", arrayOf("j"))

        assertEquals(1, joinCalls.get())
    }

    @Test
    fun `tab complete on empty args lists visible commands for non-admin`() {
        val player = playerWith(rootPermission = true, adminPermission = false)
        // tableFor returns null so visibleRootCommands does not need to filter on variant.
        `when`(tableManager.tableFor(any(UUID::class.java))).thenReturn(null)

        val suggestions = command.onTabComplete(player, bukkitCommand, "mahjong", emptyArray())!!

        assertTrue(suggestions.contains("join"), "join must be visible to non-admin")
        assertFalse(suggestions.contains("create"), "create is admin-only")
    }

    @Test
    fun `tab complete on empty args includes admin commands for admin`() {
        val player = playerWith(rootPermission = true, adminPermission = true)
        `when`(tableManager.tableFor(any(UUID::class.java))).thenReturn(null)

        val suggestions = command.onTabComplete(player, bukkitCommand, "mahjong", emptyArray())!!

        assertTrue(suggestions.contains("create"))
        assertTrue(suggestions.contains("join"))
    }

    @Test
    fun `tab complete on unknown subcommand returns empty`() {
        val player = playerWith(rootPermission = true)

        val suggestions = command.onTabComplete(player, bukkitCommand, "mahjong", arrayOf("nope", ""))!!

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `tab complete on known subcommand delegates to its suggestion provider`() {
        val player = playerWith(rootPermission = true)

        val suggestions = command.onTabComplete(player, bukkitCommand, "mahjong", arrayOf("join", "al"))!!

        assertEquals(joinSuggestion, suggestions)
    }

    @Test
    fun `tab complete for admin sub hides itself from non-admin`() {
        val player = playerWith(rootPermission = true, adminPermission = false)

        val suggestions = command.onTabComplete(player, bukkitCommand, "mahjong", arrayOf("create", ""))!!

        assertTrue(suggestions.isEmpty(), "non-admin must not see create suggestions")
    }

    private fun playerWith(
        rootPermission: Boolean,
        adminPermission: Boolean = false,
    ): Player {
        val player = mock(Player::class.java)
        lenient().`when`(player.hasPermission("mahjongpaper.command")).thenReturn(rootPermission)
        lenient().`when`(player.hasPermission("mahjongpaper.admin")).thenReturn(adminPermission)
        lenient().`when`(player.uniqueId).thenReturn(UUID.randomUUID())
        lenient().`when`(player.name).thenReturn("tester")
        return player
    }

    private fun stubHelpRendering() {
        lenient().`when`(messages.resolveLocale(any(CommandSender::class.java))).thenReturn(Locale.ENGLISH)
        lenient().`when`(messages.render(any(Locale::class.java), anyString())).thenAnswer { invocation ->
            Component.text(invocation.getArgument<String>(1))
        }
        lenient()
            .`when`(
                messages.render(any(Locale::class.java), anyString(), any(TagResolver::class.java), any(TagResolver::class.java)),
            ).thenAnswer { invocation ->
                Component.text(invocation.getArgument<String>(1))
            }
        lenient()
            .`when`(
                messages.render(
                    any(Locale::class.java),
                    anyString(),
                    any(TagResolver::class.java),
                    any(TagResolver::class.java),
                    any(TagResolver::class.java),
                ),
            ).thenAnswer { invocation ->
                Component.text(invocation.getArgument<String>(1))
            }
        lenient().`when`(messages.plain(any(Locale::class.java), anyString())).thenAnswer { invocation ->
            invocation.getArgument<String>(1)
        }
        lenient().`when`(messages.number(any(Locale::class.java), anyString(), any(Number::class.java))).thenReturn(TagResolver.empty())
    }

    private fun eq(value: String): String = org.mockito.ArgumentMatchers.eq(value)

    /**
     * Workaround for Mockito's `eq` returning null in Kotlin's strict null-checking
     * compiler: replace null with the same value so the matcher still records a
     * non-null argument matcher in the test thread.
     */
    private fun safeEq(value: String): String = org.mockito.ArgumentMatchers.eq(value) ?: value
}
