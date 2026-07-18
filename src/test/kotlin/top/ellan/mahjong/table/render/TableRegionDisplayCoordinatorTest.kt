package top.ellan.mahjong.table.render

import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.config.PluginSettings
import top.ellan.mahjong.metrics.InMemoryMetricsCollector
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.display.DisplayClickAction
import top.ellan.mahjong.render.display.DisplayEntities
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry
import top.ellan.mahjong.render.layout.TableRenderLayout
import top.ellan.mahjong.render.scene.HandRenderer
import top.ellan.mahjong.render.scene.SeatRenderer
import top.ellan.mahjong.render.scene.TableRenderer
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableRuntimeServices
import java.lang.reflect.Proxy
import java.util.EnumMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableRegionDisplayCoordinatorTest {
    @Test
    fun `managed seat region cleanup removes its public join ray`() {
        val tableId = "table-a"
        val regionKey = "seat-label:EAST"
        val worldId = UUID.fromString("00000000-0000-0000-0000-00000000a101")
        val session = mock(MahjongTableSession::class.java)
        `when`(session.id()).thenReturn(tableId)
        val coordinator = TableRegionDisplayCoordinator(session, mock(TableRegionFingerprintService::class.java))
        val interaction =
            DisplayInteractionRayRegistry.RayInteraction(
                worldId,
                0.0,
                1.5,
                3.0,
                1.0,
                0.0,
                1.0f,
                0.8f,
                0.0f,
                DisplayClickAction.joinSeat(tableId, SeatWind.EAST),
            )
        DisplayInteractionRayRegistry.clear()
        DisplayInteractionRayRegistry.replacePublicJoinRegion(tableId, regionKey, listOf(interaction))

        coordinator.removeManagedRegionDisplays(regionKey)

        assertFalse(DisplayInteractionRayRegistry.isPublicJoinRegionCurrent(tableId, regionKey))
        DisplayInteractionRayRegistry.clear()
    }

    @Test
    fun `regionKeysWithPrefix returns only matching managed regions`() {
        val session = mock(MahjongTableSession::class.java)
        val coordinator = TableRegionDisplayCoordinator(session, mock(TableRegionFingerprintService::class.java))
        val regionsField = TableRegionDisplayCoordinator::class.java.getDeclaredField("regionDisplays")
        regionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val regions = regionsField.get(coordinator) as MutableMap<String, List<Entity>>
        val fingerprintsField = TableRegionDisplayCoordinator::class.java.getDeclaredField("regionFingerprints")
        fingerprintsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val fingerprints = fingerprintsField.get(coordinator) as MutableMap<String, Long>

        regions["viewer-overlay:viewer-a"] = emptyList()
        regions["hand-public-0:EAST"] = emptyList()
        regions["viewer-overlay:viewer-b"] = emptyList()
        fingerprints["viewer-overlay:fingerprint-only"] = 1L

        assertEquals(
            setOf("viewer-overlay:viewer-a", "viewer-overlay:viewer-b", "viewer-overlay:fingerprint-only"),
            coordinator.regionKeysWithPrefix("viewer-overlay:").toSet(),
        )
    }

    @Test
    fun `exhausted spawn budget still allows an existing region to reconcile`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.bukkitPlugin()).thenReturn(mock(Plugin::class.java))
        val coordinator = TableRegionDisplayCoordinator(session, mock(TableRegionFingerprintService::class.java))
        val entity = mock(Entity::class.java)
        `when`(entity.isValid).thenReturn(true)
        val applications = intArrayOf(0)
        val spec =
            object : DisplayEntities.EntitySpec {
                override fun spawn(runtime: top.ellan.mahjong.render.display.DisplayEntityRuntime): Entity = entity

                override fun canReuse(
                    runtime: top.ellan.mahjong.render.display.DisplayEntityRuntime,
                    entity: Entity,
                ): Boolean = true

                override fun apply(
                    runtime: top.ellan.mahjong.render.display.DisplayEntityRuntime,
                    entity: Entity,
                ) {
                    applications[0]++
                }

                override fun managesOwnReuse(): Boolean = true
            }

        val regionsField = TableRegionDisplayCoordinator::class.java.getDeclaredField("regionDisplays")
        regionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val regions = regionsField.get(coordinator) as MutableMap<String, List<Entity>>
        regions["existing"] = listOf(entity)
        val fingerprintsField = TableRegionDisplayCoordinator::class.java.getDeclaredField("regionFingerprints")
        fingerprintsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val fingerprints = fingerprintsField.get(coordinator) as MutableMap<String, Long>
        fingerprints["existing"] = 1L

        val budgetClass = TableRegionDisplayCoordinator::class.java.declaredClasses.single { it.simpleName == "ApplyBudget" }
        val budgetConstructor = budgetClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        budgetConstructor.isAccessible = true
        val exhaustedSpawnBudget = budgetConstructor.newInstance(1, 0)
        val rendererClass = TableRegionDisplayCoordinator::class.java.declaredClasses.single { it.simpleName == "RegionSpecRenderer" }
        val renderer =
            Proxy.newProxyInstance(rendererClass.classLoader, arrayOf(rendererClass)) { _, method, _ ->
                if (method.name == "render") listOf(spec) else null
            }
        val update =
            TableRegionDisplayCoordinator::class.java.getDeclaredMethod(
                "updateRegionWithSpecs",
                String::class.java,
                Long::class.javaPrimitiveType,
                budgetClass,
                rendererClass,
            )
        update.isAccessible = true

        assertTrue(update.invoke(coordinator, "existing", 2L, exhaustedSpawnBudget, renderer) as Boolean)
        assertEquals(1, applications[0])
    }

    @Test
    fun `applyRenderPrecompute preserves insertion order inside all five priority buckets`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val renderer = mock(TableRenderer::class.java)
        val fingerprintService = mock(TableRegionFingerprintService::class.java)
        val metrics = InMemoryMetricsCollector()
        val calls = mutableListOf<String>()

        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.metrics()).thenReturn(metrics)
        `when`(session.renderer()).thenReturn(renderer)

        `when`(
            renderer.renderCenterLabelSpecs(
                eq(session),
                any(TableRenderSnapshot::class.java),
                any(TableRenderLayout.LayoutPlan::class.java),
            ),
        ).thenAnswer {
            calls.add("reaction:center")
            emptySpecs()
        }
        `when`(
            renderer.renderSeatLabelPlan(
                eq(session),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
            ),
        ).thenAnswer {
            val seat = it.getArgument<TableSeatRenderSnapshot>(1)
            calls.add("reaction:label:${seat.wind().name}")
            SeatRenderer.SeatLabelRenderPlan(emptySpecs(), emptyMap())
        }
        `when`(
            renderer.renderHandPublicTileSpecs(
                eq(session),
                any(TableRenderSnapshot::class.java),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
                eq(0),
            ),
        ).thenAnswer {
            calls.add("hand:public")
            emptySpecs()
        }
        `when`(
            renderer.renderHandPrivateTilePlan(
                eq(session),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
                eq(0),
            ),
        ).thenAnswer {
            calls.add("hand:private")
            HandRenderer.HandTileRenderPlan(emptySpecs(), emptyList())
        }
        `when`(
            renderer.renderSticks(
                eq(session),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
            ),
        ).thenAnswer {
            val seat = it.getArgument<TableSeatRenderSnapshot>(1)
            calls.add("turn:sticks:${seat.wind().name}")
            emptyEntities()
        }
        `when`(renderer.renderDoraSpecs(eq(session), any(TableRenderLayout.LayoutPlan::class.java))).thenAnswer {
            calls.add("board:dora")
            emptySpecs()
        }
        `when`(renderer.renderTableStructure(eq(session), any(TableRenderLayout.LayoutPlan::class.java))).thenAnswer {
            calls.add("board:table")
            emptyEntities()
        }
        `when`(renderer.renderSeatVisual(eq(session), any(SeatWind::class.java))).thenAnswer {
            val wind = it.getArgument<SeatWind>(1)
            calls.add("background:visual:${wind.name}")
            emptyEntities()
        }

        `when`(
            fingerprintService.handPublicTileFingerprint(
                any(TableRenderSnapshot::class.java),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
                eq(0),
            ),
        ).thenReturn(101L)
        `when`(
            fingerprintService.handPrivateTileFingerprint(
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
                eq(0),
            ),
        ).thenReturn(102L)

        val coordinator = TableRegionDisplayCoordinator(session, fingerprintService, 16, 64)
        val deferred = coordinator.applyRenderPrecompute(precomputeResult())

        assertTrue(deferred, "Budget should defer lower-priority regions.")
        assertEquals(
            listOf(
                "reaction:center",
                "reaction:label:EAST",
                "reaction:label:SOUTH",
                "reaction:label:WEST",
                "reaction:label:NORTH",
                "hand:public",
                "hand:private",
                "turn:sticks:EAST",
                "turn:sticks:SOUTH",
                "turn:sticks:WEST",
                "turn:sticks:NORTH",
                "board:table",
                "board:dora",
                "background:visual:EAST",
                "background:visual:SOUTH",
                "background:visual:WEST",
            ),
            calls,
        )

        assertEquals(1L, metrics.counterValue("table.render.region.apply.calls"))
        assertEquals(16L, metrics.counterValue("table.render.region.apply.processed"))
        assertEquals(1L, metrics.counterValue("table.render.region.apply.deferred"))
        assertTrue(metrics.gaugeValue("table.render.region.queue.size") >= 16L)
        assertTrue(metrics.timerCount("table.render.region.plan.nanos") >= 1L)
        assertTrue(metrics.timerCount("table.render.region.apply.nanos") >= 1L)

        assertFalse(coordinator.applyRenderPrecompute(precomputeResult()))
        assertEquals(listOf("hand:private", "background:visual:NORTH"), calls.takeLast(2))
        assertEquals(18, calls.size, "Only the private ray and deferred region should render on the retry tick.")
        assertEquals(33L, metrics.counterValue("table.render.region.apply.processed"))
        assertEquals(15L, metrics.counterValue("table.render.region.apply.skipped"))
        assertEquals(1L, metrics.counterValue("table.render.region.apply.deferred"))
        assertEquals(0L, metrics.gaugeValue("table.render.region.queue.remaining"))
        assertTrue(metrics.timerCount("table.render.region.plan.nanos") >= 2L)
    }

    @Test
    fun `applyRenderPrecompute records managed entity gauges for entity-heavy tables`() {
        val session = mock(MahjongTableSession::class.java)
        val plugin = mock(TableRuntimeServices::class.java)
        val renderer = mock(TableRenderer::class.java)
        val fingerprintService = mock(TableRegionFingerprintService::class.java)
        val metrics = InMemoryMetricsCollector()

        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.metrics()).thenReturn(metrics)
        `when`(session.renderer()).thenReturn(renderer)

        `when`(renderer.renderTableStructure(eq(session), any(TableRenderLayout.LayoutPlan::class.java))).thenReturn(
            listOf(mock(Entity::class.java), mock(Entity::class.java)),
        )
        `when`(renderer.renderSeatVisual(eq(session), any(SeatWind::class.java))).thenReturn(listOf(mock(Entity::class.java)))
        `when`(
            renderer.renderSticks(
                eq(session),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
            ),
        ).thenReturn(emptyEntities())
        `when`(renderer.renderDoraSpecs(eq(session), any(TableRenderLayout.LayoutPlan::class.java))).thenReturn(emptySpecs())
        `when`(
            renderer.renderCenterLabelSpecs(
                eq(session),
                any(TableRenderSnapshot::class.java),
                any(TableRenderLayout.LayoutPlan::class.java),
            ),
        ).thenReturn(emptySpecs())
        `when`(
            renderer.renderSeatLabelPlan(
                eq(session),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
            ),
        ).thenReturn(SeatRenderer.SeatLabelRenderPlan(emptySpecs(), emptyMap()))
        `when`(
            renderer.renderHandPublicTileSpecs(
                eq(session),
                any(TableRenderSnapshot::class.java),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
                eq(0),
            ),
        ).thenReturn(emptySpecs())
        `when`(
            renderer.renderHandPrivateTilePlan(
                eq(session),
                any(TableSeatRenderSnapshot::class.java),
                any(TableRenderLayout.SeatLayoutPlan::class.java),
                eq(0),
            ),
        ).thenReturn(HandRenderer.HandTileRenderPlan(emptySpecs(), emptyList()))

        val deferred = TableRegionDisplayCoordinator(session, fingerprintService).applyRenderPrecompute(precomputeResult())

        assertFalse(deferred)
        assertEquals(17L, metrics.counterValue("table.render.region.apply.processed"))
        assertEquals(6L, metrics.gaugeValue("table.render.region.managed_entities"))
        assertEquals(5L, metrics.gaugeValue("table.render.region.regions_with_entities"))
        assertEquals(17L, metrics.gaugeValue("table.render.region.active_regions"))
        assertEquals(0L, metrics.gaugeValue("table.render.region.viewer_overlay_regions"))
        assertEquals(0L, metrics.gaugeValue("table.render.region.viewer_overlay_entities"))
    }

    @Test
    fun `complete async fingerprint map preserves exact per-region values`() {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.settings()).thenReturn(PluginSettings.defaults())
        val service = TableRegionFingerprintService()
        val basic = precomputeResult()
        val east = basic.layout().seat(SeatWind.EAST)
        val discardPlacement =
            TableRenderLayout.TilePlacement(
                point(),
                0.0F,
                MahjongTile.EAST,
                DisplayEntities.TileRenderPose.FLAT_FACE_UP,
            )
        val meldPlacement =
            TableRenderLayout.TilePlacement(
                point(),
                90.0F,
                MahjongTile.P2,
                DisplayEntities.TileRenderPose.FLAT_FACE_UP,
            )
        val wallPlacement =
            TableRenderLayout.TilePlacement(
                point(),
                180.0F,
                MahjongTile.UNKNOWN,
                DisplayEntities.TileRenderPose.FLAT_FACE_DOWN,
            )
        val seatPlans = EnumMap(basic.layout().seats())
        seatPlans[SeatWind.EAST] =
            TableRenderLayout.SeatLayoutPlan(
                east.wind(),
                east.handBase(),
                east.statusLabelLocation(),
                east.playerNameLocation(),
                east.interactionLocation(),
                east.yaw(),
                east.publicHandPoints(),
                east.privateHandPoints(),
                listOf(discardPlacement),
                listOf(meldPlacement),
                east.stickPlacements(),
            )
        val layout =
            TableRenderLayout.LayoutPlan(
                basic.layout().displayCenter(),
                basic.layout().tableCenter(),
                basic.layout().tableVisualAnchor(),
                basic.layout().borderSpanX(),
                basic.layout().borderSpanZ(),
                seatPlans,
                listOf(wallPlacement),
                basic.layout().doraTiles(),
            )

        val coarse = service.precomputeRegionFingerprints(session, basic.snapshot())
        val complete = service.precomputeRegionFingerprints(session, basic.snapshot(), layout)
        coarse.forEach { (key, value) -> assertEquals(value, complete[key], key) }

        val eastSnapshot = basic.snapshot().seat(SeatWind.EAST)
        assertEquals(
            service.handPublicTileFingerprint(basic.snapshot(), eastSnapshot, layout.seat(SeatWind.EAST), 0),
            complete[TableRegionDisplayCoordinator.handPublicRegionKey(SeatWind.EAST, 0)],
        )
        assertEquals(
            service.handPrivateTileFingerprint(eastSnapshot, layout.seat(SeatWind.EAST), 0),
            complete[TableRegionDisplayCoordinator.handPrivateRegionKey(SeatWind.EAST, 0)],
        )
        assertEquals(
            service.discardTileFingerprint(eastSnapshot, layout.seat(SeatWind.EAST), 0),
            complete[TableRegionDisplayCoordinator.discardRegionKey(SeatWind.EAST, 0)],
        )
        assertEquals(
            service.meldTileFingerprint(eastSnapshot, layout.seat(SeatWind.EAST), 0),
            complete[TableRegionDisplayCoordinator.meldRegionKey(SeatWind.EAST, 0)],
        )
        assertEquals(
            service.wallTileFingerprint(layout, 0),
            complete[TableRegionDisplayCoordinator.wallRegionKey(0)],
        )
        assertEquals(coarse.size + 5, complete.size)
    }

    private fun precomputeResult(): TableRenderPrecomputeResult {
        val eastPlayerId = UUID.fromString("00000000-0000-0000-0000-00000000e001")
        val seatSnapshots = EnumMap<SeatWind, TableSeatRenderSnapshot>(SeatWind::class.java)
        val seatPlans = EnumMap<SeatWind, TableRenderLayout.SeatLayoutPlan>(SeatWind::class.java)
        for (wind in SeatWind.values()) {
            val occupied = wind == SeatWind.EAST
            seatSnapshots[wind] =
                TableSeatRenderSnapshot(
                    wind,
                    if (occupied) eastPlayerId else null,
                    if (occupied) "east-player" else "",
                    "",
                    0,
                    false,
                    false,
                    false,
                    true,
                    "",
                    -1,
                    emptyList(),
                    -1,
                    0,
                    emptyList(),
                    if (occupied) listOf(MahjongTile.M1) else emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                )
            seatPlans[wind] =
                TableRenderLayout.SeatLayoutPlan(
                    wind,
                    point(),
                    point(),
                    point(),
                    point(),
                    0.0F,
                    if (occupied) listOf(point()) else emptyList(),
                    if (occupied) listOf(point()) else emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                )
        }

        val snapshot =
            TableRenderSnapshot(
                1L,
                0L,
                "world",
                0.0,
                0.0,
                0.0,
                true,
                false,
                false,
                0,
                0,
                2,
                2,
                0,
                0,
                SeatWind.EAST,
                SeatWind.EAST,
                SeatWind.EAST,
                "",
                "",
                "",
                null,
                null,
                emptyList(),
                seatSnapshots,
            )
        val layout =
            TableRenderLayout.LayoutPlan(
                point(),
                point(),
                point(),
                0.0,
                0.0,
                seatPlans,
                emptyList(),
                emptyList(),
            )
        return TableRenderPrecomputeResult(snapshot, emptyMap(), layout)
    }

    private fun point() = TableRenderLayout.Point(0.0, 0.0, 0.0)

    private fun emptySpecs(): List<DisplayEntities.EntitySpec> = emptyList()

    private fun emptyEntities(): List<Entity> = emptyList()
}
