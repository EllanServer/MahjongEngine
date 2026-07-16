package top.ellan.mahjong.table.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.scene.SeatRenderer;
import top.ellan.mahjong.table.core.TableSessionContext;

final class TrackedPublicJoinProxyCoordinatorTest {
    private static final String TABLE_ID = "table-a";
    private static final String REGION_KEY = "seat-label:EAST";
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000603");

    @AfterEach
    void clearRegistry() {
        DisplayInteractionRayRegistry.clear();
    }

    @Test
    void exactJoinTextDisplayTrackingCreatesAndRemovesViewerScopedProxyOnViewerThread() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator proxies = mock(
            SparrowRayInteractionProxyCoordinator.class
        );
        Player viewer = mock(Player.class);
        TextDisplay statusLabel = textDisplay(3100, UUID.randomUUID());
        TextDisplay joinLabel = textDisplay(3101, SOURCE_ID);
        List<Runnable> viewerTasks = queuedViewerTasks(session, viewer);
        when(session.id()).thenReturn(TABLE_ID);
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(viewer.isOnline()).thenReturn(true);
        TrackedPublicJoinProxyCoordinator coordinator = new TrackedPublicJoinProxyCoordinator(
            session,
            proxies
        );

        coordinator.replaceRegion(
            REGION_KEY,
            List.of(new SeatRenderer.PublicJoinBinding(1, interaction())),
            List.of(statusLabel, joinLabel)
        );

        assertNull(DisplayInteractionRayRegistry.publicJoinSource(3100));
        DisplayInteractionRayRegistry.PublicJoinSource source =
            DisplayInteractionRayRegistry.publicJoinSource(3101);
        assertEquals(TABLE_ID, source.tableId());
        assertEquals(REGION_KEY, source.regionKey());
        assertEquals(SOURCE_ID, source.entityUuid());

        coordinator.track(viewer, REGION_KEY, 3101, SOURCE_ID);

        verify(proxies, never()).replace(any(), any());
        assertEquals(1, viewerTasks.size());
        viewerTasks.remove(0).run();
        verify(proxies).replace(
            eq("tracked-public-join:" + REGION_KEY + ':' + VIEWER_ID),
            eq(Map.of(VIEWER_ID, List.of(interaction())))
        );

        coordinator.untrack(viewer, REGION_KEY, 3101, SOURCE_ID);

        verify(proxies, never()).remove(any());
        assertEquals(1, viewerTasks.size());
        viewerTasks.remove(0).run();
        verify(proxies).remove("tracked-public-join:" + REGION_KEY + ':' + VIEWER_ID);
    }

    @Test
    void staleQueuedTrackCannotSpawnAfterRegionGenerationChanges() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator proxies = mock(
            SparrowRayInteractionProxyCoordinator.class
        );
        Player viewer = mock(Player.class);
        TextDisplay joinLabel = textDisplay(3201, SOURCE_ID);
        List<Runnable> viewerTasks = queuedViewerTasks(session, viewer);
        when(session.id()).thenReturn(TABLE_ID);
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(viewer.isOnline()).thenReturn(true);
        TrackedPublicJoinProxyCoordinator coordinator = new TrackedPublicJoinProxyCoordinator(
            session,
            proxies
        );
        SeatRenderer.PublicJoinBinding binding = new SeatRenderer.PublicJoinBinding(
            0,
            interaction()
        );
        coordinator.replaceRegion(REGION_KEY, List.of(binding), List.of(joinLabel));
        coordinator.track(viewer, REGION_KEY, 3201, SOURCE_ID);

        coordinator.replaceRegion(REGION_KEY, List.of(binding), List.of(joinLabel));
        viewerTasks.remove(0).run();

        verify(proxies, never()).replace(any(), any());
        assertSame(
            SOURCE_ID,
            DisplayInteractionRayRegistry.publicJoinSource(3201).entityUuid()
        );
    }

    @Test
    void regionRefreshRemovesProxyAfterViewerJoinsTheTable() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator proxies = mock(
            SparrowRayInteractionProxyCoordinator.class
        );
        Player viewer = mock(Player.class);
        TextDisplay joinLabel = textDisplay(3301, SOURCE_ID);
        List<Runnable> viewerTasks = queuedViewerTasks(session, viewer);
        when(session.id()).thenReturn(TABLE_ID);
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(viewer.isOnline()).thenReturn(true);
        TrackedPublicJoinProxyCoordinator coordinator = new TrackedPublicJoinProxyCoordinator(
            session,
            proxies
        );
        SeatRenderer.PublicJoinBinding binding = new SeatRenderer.PublicJoinBinding(
            0,
            interaction()
        );
        coordinator.replaceRegion(REGION_KEY, List.of(binding), List.of(joinLabel));
        coordinator.track(viewer, REGION_KEY, 3301, SOURCE_ID);
        viewerTasks.remove(0).run();

        when(session.seatOf(VIEWER_ID)).thenReturn(SeatWind.EAST);
        coordinator.replaceRegion(REGION_KEY, List.of(binding), List.of(joinLabel));
        viewerTasks.remove(0).run();

        verify(proxies).remove("tracked-public-join:" + REGION_KEY + ':' + VIEWER_ID);
        verify(proxies).replace(
            eq("tracked-public-join:" + REGION_KEY + ':' + VIEWER_ID),
            eq(Map.of(VIEWER_ID, List.of(interaction())))
        );
    }

    private static List<Runnable> queuedViewerTasks(TableSessionContext session, Player viewer) {
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(invocation -> {
            tasks.add(invocation.getArgument(1));
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        return tasks;
    }

    private static TextDisplay textDisplay(int entityId, UUID entityUuid) {
        TextDisplay display = mock(TextDisplay.class);
        when(display.getEntityId()).thenReturn(entityId);
        when(display.getUniqueId()).thenReturn(entityUuid);
        when(display.getTrackedPlayers()).thenReturn(Set.of());
        return display;
    }

    private static DisplayInteractionRayRegistry.RayInteraction interaction() {
        return new DisplayInteractionRayRegistry.RayInteraction(
            WORLD_ID,
            1.0D,
            2.0D,
            3.0D,
            1.0D,
            0.0D,
            0.9F,
            0.3F,
            0.0F,
            DisplayClickAction.joinSeat(TABLE_ID, SeatWind.EAST)
        );
    }
}
