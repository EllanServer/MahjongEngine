package top.ellan.mahjong.table.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.table.core.TableSessionContext;

final class SparrowViewerOverlayCoordinatorTest {
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000991");
    private static final String REGION_KEY = "viewer-overlay:" + VIEWER_ID;

    private TableSessionContext session;
    private Player viewer;
    private FakeBackend backend;
    private SparrowViewerOverlayCoordinator coordinator;

    @BeforeEach
    void setUp() {
        this.session = mock(TableSessionContext.class);
        this.viewer = mock(Player.class);
        this.backend = new FakeBackend();
        this.coordinator = new SparrowViewerOverlayCoordinator(this.session, this.backend);
        when(this.viewer.isOnline()).thenReturn(true);
        when(this.session.onlinePlayer(VIEWER_ID)).thenReturn(this.viewer);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(this.session).runForViewer(eq(this.viewer), any(Runnable.class));
    }

    @Test
    void equalFingerprintDoesNotCreateOrResendFakeEntities() {
        List<DisplayEntities.EntitySpec> specs = List.of(centerLabel(64.0D), centerLabel(65.0D));

        assertTrue(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 41L, specs, () -> { }));
        assertTrue(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 41L, specs, () -> { }));

        assertEquals(2, this.backend.created);
        assertEquals(1, this.backend.spawnBatches);
        assertEquals(2, this.coordinator.entityCount());
    }

    @Test
    void changedFingerprintReplacesThePreviousClientOverlay() {
        assertTrue(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 41L, List.of(centerLabel(64.0D)), () -> { }));
        assertTrue(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 42L, List.of(centerLabel(66.0D)), () -> { }));

        assertEquals(2, this.backend.created);
        assertEquals(2, this.backend.spawnBatches);
        assertEquals(1, this.backend.destroyedEntityIds.size());
        assertTrue(this.coordinator.isCurrent(REGION_KEY, VIEWER_ID, 42L));
    }

    @Test
    void fixedLabelsAndInteractionsStayOnTheServerEntityFallbackPath() {
        DisplayEntities.LabelSpec fixed = new DisplayEntities.LabelSpec(
            new Location(null, 0.0D, 64.0D, 0.0D),
            net.kyori.adventure.text.Component.text("prompt"),
            Color.BLACK,
            List.of(VIEWER_ID),
            Display.Billboard.FIXED,
            0.0F,
            0.0F,
            true
        );
        DisplayEntities.InteractionSpec interaction = new DisplayEntities.InteractionSpec(
            new Location(null, 0.0D, 64.0D, 0.0D),
            1.0F,
            1.0F,
            null,
            List.of(VIEWER_ID)
        );

        assertFalse(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 1L, List.of(fixed), () -> { }));
        assertFalse(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 1L, List.of(interaction), () -> { }));
        assertEquals(0, this.backend.created);
        assertFalse(this.coordinator.hasRegions());
    }

    @Test
    void unavailableBackendReturnsControlToTheRealTextDisplayFallback() {
        this.backend.available = false;

        assertFalse(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 1L, List.of(centerLabel(64.0D)), () -> { }));
        assertEquals(0, this.backend.created);
        assertFalse(this.coordinator.hasRegions());
    }

    @Test
    void spawnFailureDropsClientStateAndRequestsTheFallback() {
        AtomicInteger fallbackRequests = new AtomicInteger();
        this.backend.failSpawn = true;

        assertTrue(this.coordinator.tryUpdate(
            REGION_KEY,
            VIEWER_ID,
            1L,
            List.of(centerLabel(64.0D)),
            fallbackRequests::incrementAndGet
        ));

        assertEquals(1, fallbackRequests.get());
        assertFalse(this.coordinator.hasRegions());
        assertFalse(this.backend.available);
    }

    @Test
    void offlineViewerRemovalDropsLifecycleStateWithoutAnotherPacket() {
        assertTrue(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 1L, List.of(centerLabel(64.0D)), () -> { }));
        when(this.session.onlinePlayer(VIEWER_ID)).thenReturn(null);

        this.coordinator.remove(REGION_KEY);

        assertFalse(this.coordinator.hasRegions());
        assertEquals(0, this.backend.destroyedEntityIds.size());
    }

    @Test
    void clearAndShutdownRemoveEveryTrackedClientEntity() {
        assertTrue(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 1L, List.of(centerLabel(64.0D)), () -> { }));
        assertTrue(this.coordinator.tryUpdate(REGION_KEY + ":seat", VIEWER_ID, 2L, List.of(centerLabel(65.0D)), () -> { }));

        this.coordinator.clear();

        assertFalse(this.coordinator.hasRegions());
        assertEquals(2, this.backend.destroyedEntityIds.size());

        assertTrue(this.coordinator.tryUpdate(REGION_KEY, VIEWER_ID, 3L, List.of(centerLabel(66.0D)), () -> { }));
        this.coordinator.shutdown();

        assertFalse(this.coordinator.hasRegions());
        assertEquals(3, this.backend.destroyedEntityIds.size());
    }

    private static DisplayEntities.LabelSpec centerLabel(double y) {
        return new DisplayEntities.LabelSpec(
            new Location(null, 0.0D, y, 0.0D),
            net.kyori.adventure.text.Component.text("spectator"),
            Color.BLACK,
            List.of(VIEWER_ID),
            Display.Billboard.CENTER,
            0.0F,
            0.0F,
            true
        );
    }

    private static final class FakeBackend implements SparrowViewerOverlayCoordinator.Backend {
        private boolean available = true;
        private boolean failSpawn;
        private int created;
        private int spawnBatches;
        private final List<Integer> destroyedEntityIds = new ArrayList<>();

        @Override
        public boolean available() {
            return this.available;
        }

        @Override
        public SparrowViewerOverlayCoordinator.ClientTextDisplay create(DisplayEntities.LabelSpec spec) {
            return new FakeDisplay(++this.created);
        }

        @Override
        public void spawn(Player viewer, List<SparrowViewerOverlayCoordinator.ClientTextDisplay> displays) {
            this.spawnBatches++;
            if (this.failSpawn) {
                throw new IllegalStateException("spawn failed");
            }
        }

        @Override
        public void destroy(Player viewer, List<SparrowViewerOverlayCoordinator.ClientTextDisplay> displays) {
            displays.stream().map(SparrowViewerOverlayCoordinator.ClientTextDisplay::entityId).forEach(this.destroyedEntityIds::add);
        }

        @Override
        public void disable() {
            this.available = false;
        }
    }

    private record FakeDisplay(int entityId) implements SparrowViewerOverlayCoordinator.ClientTextDisplay {
    }
}
