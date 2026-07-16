package top.ellan.mahjong.table.presentation

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.render.snapshot.TableViewerActionBarSnapshot
import top.ellan.mahjong.render.snapshot.TableViewerActionOverlaySnapshot
import top.ellan.mahjong.render.snapshot.TableViewerHudPresentationSnapshot
import top.ellan.mahjong.render.snapshot.TableViewerHudSnapshot
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot
import top.ellan.mahjong.render.snapshot.TableViewerPromptSnapshot
import top.ellan.mahjong.runtime.ServerScheduler
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableRuntimeServices
import java.util.Locale
import java.util.UUID
import kotlin.test.Test

class TableViewerPresentationCoordinatorTest {
    @Test
    fun `overlay refresh is throttled between periodic polls`() {
        val session = mock(MahjongTableSession::class.java)
        val coordinator = TableViewerPresentationCoordinator(session)
        setField(coordinator, "viewerHudDirty", false)

        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val viewer = mock(Player::class.java)
        `when`(viewer.uniqueId).thenReturn(viewerId)

        val snapshot =
            TableViewerOverlaySnapshot(
                viewerId,
                "viewer-overlay:$viewerId",
                false,
                Component.empty(),
                TableViewerPromptSnapshot(viewerId, "viewer-prompt:$viewerId", false, Component.empty(), "prompt-fingerprint"),
                TableViewerActionOverlaySnapshot(viewerId, "viewer-actions:$viewerId", emptyList(), "actions-fingerprint"),
                emptyList(),
                "overlay-fingerprint",
            )

        `when`(session.viewers()).thenReturn(listOf(viewer))
        `when`(session.captureViewerOverlaySnapshot(viewer)).thenReturn(snapshot)
        `when`(session.viewerOverlayRegionKeys()).thenReturn(listOf(snapshot.regionKey()))

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Int> { Bukkit.getCurrentTick() }.thenReturn(100, 120, 200)

            coordinator.flushIfNeeded()
            coordinator.flushIfNeeded()
            coordinator.flushIfNeeded()
        }

        verify(session, times(2)).updateViewerOverlayRegion(snapshot)
    }

    @Test
    fun `empty table does not rescan region keys on every hud poll`() {
        val session = mock(MahjongTableSession::class.java)
        val coordinator = TableViewerPresentationCoordinator(session)
        setField(coordinator, "viewerHudDirty", false)

        `when`(session.viewers()).thenReturn(emptyList())
        `when`(session.viewerOverlayRegionKeys()).thenReturn(emptyList())

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Int> { Bukkit.getCurrentTick() }.thenReturn(100, 120)

            coordinator.flushIfNeeded()
            coordinator.flushIfNeeded()
        }

        verify(session, times(1)).viewerOverlayRegionKeys()
    }

    @Test
    fun `hud refresh is throttled between periodic polls`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val messages = mock(MessageService::class.java)
        val coordinator = TableViewerPresentationCoordinator(session)
        setField(coordinator, "viewerOverlayDirty", false)
        setField(coordinator, "viewerHudDirty", false)

        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val viewer = mock(Player::class.java)
        `when`(viewer.uniqueId).thenReturn(viewerId)

        val hudSnapshot =
            TableViewerHudSnapshot(
                Component.text("hud"),
                0.75F,
                BossBar.Color.BLUE,
                "hud-state",
            )
        val existingBar = mock(BossBar::class.java)

        @Suppress("UNCHECKED_CAST")
        val bars = getField(coordinator, "viewerHudBars") as MutableMap<UUID, BossBar>
        bars[viewerId] = existingBar

        `when`(session.viewers()).thenReturn(listOf(viewer))
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.messages()).thenReturn(messages)
        `when`(messages.resolveLocale(viewer)).thenReturn(Locale.SIMPLIFIED_CHINESE)
        `when`(session.captureViewerHudPresentationSnapshot(Locale.SIMPLIFIED_CHINESE, viewerId))
            .thenReturn(TableViewerHudPresentationSnapshot(hudSnapshot, TableViewerActionBarSnapshot.hidden()))

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Int> { Bukkit.getCurrentTick() }.thenReturn(200, 205, 220)

            coordinator.flushIfNeeded()
            coordinator.flushIfNeeded()
            coordinator.flushIfNeeded()
        }

        verify(session, times(2)).captureViewerHudPresentationSnapshot(Locale.SIMPLIFIED_CHINESE, viewerId)
        verify(session, Mockito.never()).captureViewerHudSnapshot(Locale.SIMPLIFIED_CHINESE, viewerId)
    }

    @Test
    fun `unchanged action bar is not sent again on the next hud poll`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val messages = mock(MessageService::class.java)
        val scheduler = mock(ServerScheduler::class.java)
        val coordinator = TableViewerPresentationCoordinator(session)
        setField(coordinator, "viewerOverlayDirty", false)
        setField(coordinator, "viewerHudDirty", false)

        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000303")
        val viewer = mock(Player::class.java)
        val existingBar = mock(BossBar::class.java)
        val actionBar = TableViewerActionBarSnapshot(Component.text("discard in 10s"), true)
        val hudSnapshot = TableViewerHudSnapshot(Component.text("hud"), 0.75F, BossBar.Color.BLUE, "hud-state")

        @Suppress("UNCHECKED_CAST")
        val bars = getField(coordinator, "viewerHudBars") as MutableMap<UUID, BossBar>
        bars[viewerId] = existingBar

        `when`(viewer.uniqueId).thenReturn(viewerId)
        `when`(session.viewers()).thenReturn(listOf(viewer))
        `when`(session.viewerOverlayRegionKeys()).thenReturn(emptyList())
        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.messages()).thenReturn(messages)
        `when`(plugin.scheduler()).thenReturn(scheduler)
        `when`(messages.resolveLocale(viewer)).thenReturn(Locale.SIMPLIFIED_CHINESE)
        `when`(session.captureViewerHudPresentationSnapshot(Locale.SIMPLIFIED_CHINESE, viewerId))
            .thenReturn(TableViewerHudPresentationSnapshot(hudSnapshot, actionBar))

        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Int> { Bukkit.getCurrentTick() }.thenReturn(300, 320)

            coordinator.flushIfNeeded()
            coordinator.flushIfNeeded()
        }

        verify(scheduler, times(1)).runEntity(Mockito.eq(viewer), Mockito.any(Runnable::class.java))
    }

    private fun setField(
        target: Any,
        fieldName: String,
        value: Any,
    ) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(
        target: Any,
        fieldName: String,
    ): Any? {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target)
    }
}
