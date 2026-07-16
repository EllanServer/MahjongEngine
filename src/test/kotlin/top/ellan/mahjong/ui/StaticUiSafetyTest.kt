package top.ellan.mahjong.ui

import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.junit.jupiter.api.Test
import org.mockito.Mockito.lenient
import org.mockito.Mockito.mock
import top.ellan.mahjong.table.core.MahjongTableSession
import java.util.UUID
import kotlin.test.assertFalse

/**
 * Lightweight contract tests for the static UI entry points. We do not
 * exercise inventory rendering itself (that requires a live Bukkit server),
 * but the null-input early-return contract is small and observable from
 * the JVM, and it has prevented at least one historical NPE during reload.
 */
class StaticUiSafetyTest {
    @Test
    fun `isRuleInventory rejects null inventory`() {
        assertFalse(RuleSettingsUi.isRuleInventory(null))
    }

    @Test
    fun `isRuleInventory rejects inventories without our holder`() {
        val inventory = mock(Inventory::class.java)
        lenient().`when`(inventory.holder).thenReturn(null)
        assertFalse(RuleSettingsUi.isRuleInventory(inventory))
    }

    @Test
    fun `isTableControlInventory rejects null inventory`() {
        assertFalse(TableControlUi.isTableControlInventory(null))
    }

    @Test
    fun `isTableControlInventory rejects inventories without our holder`() {
        val inventory = mock(Inventory::class.java)
        lenient().`when`(inventory.holder).thenReturn(null)
        assertFalse(TableControlUi.isTableControlInventory(inventory))
    }

    @Test
    fun `RuleSettingsUi open is a no-op when player is null`() {
        // Should not throw or interact with the session; if open() ever stops
        // null-checking we will see it as a NullPointerException here.
        val session = mock(MahjongTableSession::class.java)
        RuleSettingsUi.open(null, session)
    }

    @Test
    fun `RuleSettingsUi open is a no-op when session is null`() {
        val player = mock(Player::class.java)
        lenient().`when`(player.uniqueId).thenReturn(UUID.randomUUID())
        RuleSettingsUi.open(player, null)
    }

    @Test
    fun `TableControlUi open is a no-op when player is null`() {
        val session = mock(MahjongTableSession::class.java)
        TableControlUi.open(null, session)
    }

    @Test
    fun `TableControlUi open is a no-op when session is null`() {
        val player = mock(Player::class.java)
        lenient().`when`(player.uniqueId).thenReturn(UUID.randomUUID())
        TableControlUi.open(player, null)
    }
}
