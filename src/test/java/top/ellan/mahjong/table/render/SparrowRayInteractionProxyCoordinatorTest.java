package top.ellan.mahjong.table.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.render.display.ClientInteractionProxyRegistry;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.table.core.TableSessionContext;

final class SparrowRayInteractionProxyCoordinatorTest {
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");

    @AfterEach
    void clearRegistry() {
        ClientInteractionProxyRegistry.clear();
    }

    @Test
    void replaceAndRemoveKeepUnknownEntityOwnershipInLockstep() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator.Backend backend = mock(
            SparrowRayInteractionProxyCoordinator.Backend.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy proxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        Player viewer = mock(Player.class);
        World world = mock(World.class);
        when(session.id()).thenReturn("table-a");
        when(session.onlinePlayer(VIEWER_ID)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(backend.available()).thenReturn(true);
        when(backend.create(eq(viewer), any())).thenReturn(List.of(proxy));
        when(proxy.entityId()).thenReturn(1201);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        SparrowRayInteractionProxyCoordinator coordinator = new SparrowRayInteractionProxyCoordinator(
            session,
            backend
        );

        coordinator.replace("hand:0", Map.of(VIEWER_ID, List.of(interaction())));

        assertEquals("table-a", ClientInteractionProxyRegistry.tableIdFor(1201, VIEWER_ID));
        assertTrue(coordinator.isCurrent("hand:0", Set.of(VIEWER_ID)));
        assertEquals(1, coordinator.entityCount());
        verify(backend).spawn(viewer, List.of(proxy));

        coordinator.remove("hand:0");

        assertNull(ClientInteractionProxyRegistry.tableIdFor(1201, VIEWER_ID));
        assertEquals(0, coordinator.entityCount());
        verify(backend).destroy(viewer, List.of(proxy));
        verify(session, times(1)).id();
        verify(proxy, times(1)).entityId();
    }

    @Test
    void clearedViewerOwnershipMakesAnUnchangedRegionStaleForReconnect() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator.Backend backend = mock(
            SparrowRayInteractionProxyCoordinator.Backend.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy firstProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy reconnectedProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        Player viewer = mock(Player.class);
        when(session.id()).thenReturn("table-a");
        when(session.onlinePlayer(VIEWER_ID)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(backend.available()).thenReturn(true);
        when(backend.create(eq(viewer), any()))
            .thenReturn(List.of(firstProxy))
            .thenReturn(List.of(reconnectedProxy));
        when(firstProxy.entityId()).thenReturn(1202);
        when(reconnectedProxy.entityId()).thenReturn(1203);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        SparrowRayInteractionProxyCoordinator coordinator = new SparrowRayInteractionProxyCoordinator(
            session,
            backend
        );
        coordinator.replace("actions", Map.of(VIEWER_ID, List.of(interaction())));

        ClientInteractionProxyRegistry.clearViewer(VIEWER_ID);

        assertFalse(coordinator.isCurrent("actions", Set.of(VIEWER_ID)));

        coordinator.replace("actions", Map.of(VIEWER_ID, List.of(interaction())));

        assertNull(ClientInteractionProxyRegistry.tableIdFor(1202, VIEWER_ID));
        assertEquals("table-a", ClientInteractionProxyRegistry.tableIdFor(1203, VIEWER_ID));
        assertTrue(coordinator.isCurrent("actions", Set.of(VIEWER_ID)));
        verify(backend).destroy(viewer, List.of(firstProxy));
        verify(backend).spawn(viewer, List.of(reconnectedProxy));
    }

    @Test
    void sameImmutableInteractionSnapshotReusesWithoutRepeatedIdentityReads() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator.Backend backend = mock(
            SparrowRayInteractionProxyCoordinator.Backend.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy proxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        Player viewer = mock(Player.class);
        when(session.id()).thenReturn("table-a");
        when(session.onlinePlayer(VIEWER_ID)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(backend.available()).thenReturn(true);
        when(backend.create(eq(viewer), any())).thenReturn(List.of(proxy));
        when(proxy.entityId()).thenReturn(1204);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        SparrowRayInteractionProxyCoordinator coordinator = new SparrowRayInteractionProxyCoordinator(
            session,
            backend
        );
        List<DisplayInteractionRayRegistry.RayInteraction> interactions = List.of(interaction());
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactionsByViewer = Map.of(
            VIEWER_ID,
            interactions
        );

        coordinator.replace("actions", interactionsByViewer);
        coordinator.replace("actions", interactionsByViewer);

        verify(backend, times(1)).create(eq(viewer), any());
        verify(backend, times(1)).spawn(viewer, List.of(proxy));
        verify(proxy, times(1)).entityId();
        verify(session, times(1)).id();
        assertEquals(1, coordinator.entityCount());
    }

    @Test
    void mutableInteractionListGeometryChangeCannotHitIdentityFastPath() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator.Backend backend = mock(
            SparrowRayInteractionProxyCoordinator.Backend.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy firstProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy changedProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        Player viewer = mock(Player.class);
        when(session.id()).thenReturn("table-a");
        when(session.onlinePlayer(VIEWER_ID)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(backend.available()).thenReturn(true);
        when(backend.create(eq(viewer), any()))
            .thenReturn(List.of(firstProxy))
            .thenReturn(List.of(changedProxy));
        when(firstProxy.entityId()).thenReturn(1205);
        when(changedProxy.entityId()).thenReturn(1206);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        SparrowRayInteractionProxyCoordinator coordinator = new SparrowRayInteractionProxyCoordinator(
            session,
            backend
        );
        List<DisplayInteractionRayRegistry.RayInteraction> interactions = new ArrayList<>();
        interactions.add(interaction());
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactionsByViewer = Map.of(
            VIEWER_ID,
            interactions
        );

        coordinator.replace("actions", interactionsByViewer);
        interactions.set(0, interaction("view:river", 0.75F));
        coordinator.replace("actions", interactionsByViewer);

        verify(backend, times(2)).create(eq(viewer), any());
        verify(backend).destroy(viewer, List.of(firstProxy));
        verify(backend).spawn(viewer, List.of(firstProxy));
        verify(backend).spawn(viewer, List.of(changedProxy));
        assertNull(ClientInteractionProxyRegistry.tableIdFor(1205, VIEWER_ID));
        assertEquals("table-a", ClientInteractionProxyRegistry.tableIdFor(1206, VIEWER_ID));
        assertEquals(1, coordinator.entityCount());
    }

    @Test
    void multipleProxyOwnershipRemainsCompleteWithCachedFirstEntityId() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator.Backend backend = mock(
            SparrowRayInteractionProxyCoordinator.Backend.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy firstProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy secondProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        Player viewer = mock(Player.class);
        when(session.id()).thenReturn("table-a");
        when(session.onlinePlayer(VIEWER_ID)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(backend.available()).thenReturn(true);
        when(backend.create(eq(viewer), any())).thenReturn(List.of(firstProxy, secondProxy));
        when(firstProxy.entityId()).thenReturn(1207);
        when(secondProxy.entityId()).thenReturn(1208);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        SparrowRayInteractionProxyCoordinator coordinator = new SparrowRayInteractionProxyCoordinator(
            session,
            backend
        );

        coordinator.replace("actions", Map.of(VIEWER_ID, List.of(interaction())));

        assertTrue(coordinator.isCurrent("actions", Set.of(VIEWER_ID)));
        assertEquals("table-a", ClientInteractionProxyRegistry.tableIdFor(1207, VIEWER_ID));
        assertEquals("table-a", ClientInteractionProxyRegistry.tableIdFor(1208, VIEWER_ID));
        coordinator.remove("actions");
        assertNull(ClientInteractionProxyRegistry.tableIdFor(1207, VIEWER_ID));
        assertNull(ClientInteractionProxyRegistry.tableIdFor(1208, VIEWER_ID));
        verify(firstProxy, times(1)).entityId();
        verify(secondProxy, times(3)).entityId();
    }

    @Test
    void cachedEntityCountTracksTheSameViewerAcrossMultipleRegions() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator.Backend backend = mock(
            SparrowRayInteractionProxyCoordinator.Backend.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy firstProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy secondProxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        Player viewer = mock(Player.class);
        when(session.id()).thenReturn("table-a");
        when(session.onlinePlayer(VIEWER_ID)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(backend.available()).thenReturn(true);
        when(backend.create(eq(viewer), any()))
            .thenReturn(List.of(firstProxy))
            .thenReturn(List.of(secondProxy));
        when(firstProxy.entityId()).thenReturn(1209);
        when(secondProxy.entityId()).thenReturn(1210);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        SparrowRayInteractionProxyCoordinator coordinator = new SparrowRayInteractionProxyCoordinator(
            session,
            backend
        );

        coordinator.replace("actions", Map.of(VIEWER_ID, List.of(interaction())));
        coordinator.replace("hand", Map.of(VIEWER_ID, List.of(interaction())));

        assertEquals(2, coordinator.entityCount());
        coordinator.removeViewer(VIEWER_ID);
        assertEquals(0, coordinator.entityCount());
        assertNull(ClientInteractionProxyRegistry.tableIdFor(1209, VIEWER_ID));
        assertNull(ClientInteractionProxyRegistry.tableIdFor(1210, VIEWER_ID));
    }

    @Test
    void unchangedGeometryWithDifferentActionReusesClientProxyWithoutMorePackets() {
        TableSessionContext session = mock(TableSessionContext.class);
        SparrowRayInteractionProxyCoordinator.Backend backend = mock(
            SparrowRayInteractionProxyCoordinator.Backend.class
        );
        SparrowRayInteractionProxyCoordinator.ClientProxy proxy = mock(
            SparrowRayInteractionProxyCoordinator.ClientProxy.class
        );
        Player viewer = mock(Player.class);
        when(session.id()).thenReturn("table-a");
        when(session.onlinePlayer(VIEWER_ID)).thenReturn(viewer);
        when(viewer.isOnline()).thenReturn(true);
        when(backend.available()).thenReturn(true);
        when(backend.create(eq(viewer), any())).thenReturn(List.of(proxy));
        when(proxy.entityId()).thenReturn(1204);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(session).runForViewer(eq(viewer), any(Runnable.class));
        SparrowRayInteractionProxyCoordinator coordinator = new SparrowRayInteractionProxyCoordinator(
            session,
            backend
        );

        coordinator.replace("actions", Map.of(VIEWER_ID, List.of(interaction())));
        coordinator.replace(
            "actions",
            Map.of(VIEWER_ID, List.of(interaction("view:river:updated", 0.5F)))
        );

        verify(backend, times(1)).create(eq(viewer), any());
        verify(backend, times(1)).spawn(viewer, List.of(proxy));
        verify(backend, never()).destroy(viewer, List.of(proxy));
        assertEquals(1, coordinator.entityCount());
    }

    private static DisplayInteractionRayRegistry.RayInteraction interaction() {
        return interaction("view:river", 0.5F);
    }

    private static DisplayInteractionRayRegistry.RayInteraction interaction(String command, float width) {
        return new DisplayInteractionRayRegistry.RayInteraction(
            WORLD_ID,
            0.0D,
            1.5D,
            3.0D,
            1.0D,
            0.0D,
            width,
            0.3F,
            0.0F,
            DisplayClickAction.playerCommand("table-a", VIEWER_ID, command)
        );
    }
}
