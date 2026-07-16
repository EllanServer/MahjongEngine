package top.ellan.mahjong.table.render;

import top.ellan.mahjong.metrics.MetricsCollector;
import top.ellan.mahjong.metrics.NoopMetricsCollector;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayVisibilityRegistry;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.scene.HandRenderer;
import top.ellan.mahjong.render.scene.SeatRenderer;
import top.ellan.mahjong.render.scene.ViewerOverlayRenderer;
import top.ellan.mahjong.table.core.TableSessionContext;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionButtonSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerPromptSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public final class TableRegionDisplayCoordinator {
    private static final String REGION_TABLE = "table";
    private static final String REGION_WALL = "wall";
    private static final String REGION_DORA = "dora";
    private static final String REGION_CENTER = "center";
    private static final String REGION_VIEWER_OVERLAY_PREFIX = "viewer-overlay:";
    private static final String REGION_VIEWER_ACTIONS_PREFIX = "viewer-actions:";
    private static final int MAX_WALL_TILE_REGIONS = 144;
    private static final int MAX_HAND_TILE_REGIONS = 14;
    private static final int MAX_DISCARD_TILE_REGIONS = 24;
    private static final int MAX_MELD_TILE_REGIONS = 20;
    private static final int SEAT_COUNT = SeatWind.values().length;
    private static final int DEFAULT_MAX_REGION_UPDATES_PER_APPLY = 64;
    private static final int DEFAULT_MAX_ENTITY_SPAWNS_PER_APPLY = 192;
    private static final String[] VISUAL_REGION_KEYS = createSeatRegionKeys("visual");
    private static final String[] LABEL_REGION_KEYS = createSeatRegionKeys("labels");
    private static final String[] STICK_REGION_KEYS = createSeatRegionKeys("sticks");
    private static final String[] HAND_PUBLIC_REGION_KEYS = createSeatRegionKeys("hand-public");
    private static final String[] HAND_PRIVATE_REGION_KEYS = createSeatRegionKeys("hand-private");
    private static final String[] DISCARD_REGION_KEYS = createSeatRegionKeys("discards");
    private static final String[] MELD_REGION_KEYS = createSeatRegionKeys("melds");
    private static final String[][] HAND_PUBLIC_TILE_REGION_KEYS = createIndexedSeatRegionKeys(
        "hand-public",
        MAX_HAND_TILE_REGIONS
    );
    private static final String[][] HAND_PRIVATE_TILE_REGION_KEYS = createIndexedSeatRegionKeys(
        "hand-private",
        MAX_HAND_TILE_REGIONS
    );
    private static final String[][] DISCARD_TILE_REGION_KEYS = createIndexedSeatRegionKeys(
        "discards",
        MAX_DISCARD_TILE_REGIONS
    );
    private static final String[][] MELD_TILE_REGION_KEYS = createIndexedSeatRegionKeys(
        "melds",
        MAX_MELD_TILE_REGIONS
    );
    private static final String[] WALL_TILE_REGION_KEYS = createIndexedRegionKeys(REGION_WALL, MAX_WALL_TILE_REGIONS);
    private static final int BUCKET_REACTION_PROMPT = 0;
    private static final int BUCKET_HAND = 1;
    private static final int BUCKET_TURN_STATE = 2;
    private static final int BUCKET_BOARD = 3;
    private static final int BUCKET_BACKGROUND = 4;

    private final TableSessionContext session;
    private final TableRegionFingerprintService fingerprintService;
    private final int maxRegionUpdatesPerApply;
    private final int maxEntitySpawnsPerApply;
    private final Map<String, List<Entity>> regionDisplays = new LinkedHashMap<>();
    private final Map<String, Long> regionFingerprints = new HashMap<>();
    private final Map<String, Set<UUID>> rayInteractionOwners = new HashMap<>();
    private final Set<String> publicJoinRayRegions = new LinkedHashSet<>();
    private final SparrowViewerOverlayCoordinator clientViewerOverlays;
    private final SparrowRayInteractionProxyCoordinator rayInputProxies;
    private final TrackedPublicJoinProxyCoordinator trackedPublicJoinProxies;

    public TableRegionDisplayCoordinator(TableSessionContext session, TableRegionFingerprintService fingerprintService) {
        this(session, fingerprintService, DEFAULT_MAX_REGION_UPDATES_PER_APPLY, DEFAULT_MAX_ENTITY_SPAWNS_PER_APPLY);
    }

    TableRegionDisplayCoordinator(
        TableSessionContext session,
        TableRegionFingerprintService fingerprintService,
        int maxRegionUpdatesPerApply,
        int maxEntitySpawnsPerApply
    ) {
        this.session = session;
        this.fingerprintService = fingerprintService;
        this.maxRegionUpdatesPerApply = Math.max(1, maxRegionUpdatesPerApply);
        this.maxEntitySpawnsPerApply = Math.max(1, maxEntitySpawnsPerApply);
        this.clientViewerOverlays = new SparrowViewerOverlayCoordinator(session);
        this.rayInputProxies = new SparrowRayInteractionProxyCoordinator(session);
        this.trackedPublicJoinProxies = new TrackedPublicJoinProxyCoordinator(session);
    }

    public boolean applyRenderPrecompute(TableRenderPrecomputeResult result) {
        long startedAt = System.nanoTime();
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.region.apply.calls");
        metrics.recordGauge("table.render.region.heap_used_bytes", usedHeapBytes());

        ApplyBudget budget = new ApplyBudget(this.maxRegionUpdatesPerApply, this.maxEntitySpawnsPerApply);
        TableRenderSnapshot snapshot = result.snapshot();
        TableRenderLayout.LayoutPlan plan = result.layout();
        Map<String, Long> fingerprints = result.regionFingerprints();
        RegionUpdateQueue queue = new RegionUpdateQueue();

        this.enqueue(queue, BUCKET_BOARD, () -> this.updateStaticRegion(
            REGION_TABLE,
            fingerprintOf(fingerprints, REGION_TABLE),
            budget,
            () -> this.session.renderer().renderTableStructure(this.session, plan)
        ));
        this.enqueueWallRegionUpdates(plan, budget, queue);
        this.enqueue(queue, BUCKET_BOARD, () -> this.updateRegionWithSpecs(
            REGION_DORA,
            fingerprintOf(fingerprints, REGION_DORA),
            budget,
            () -> this.session.renderer().renderDoraSpecs(this.session, plan)
        ));
        this.enqueue(queue, BUCKET_REACTION_PROMPT, () -> this.updateRegionWithSpecs(
            REGION_CENTER,
            fingerprintOf(fingerprints, REGION_CENTER),
            budget,
            () -> this.session.renderer().renderCenterLabelSpecs(this.session, snapshot, plan)
        ));

        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan seatPlan = plan.seat(wind);
            String visualRegionKey = this.seatRegionKey("visual", wind);
            String labelsRegionKey = this.seatRegionKey("labels", wind);
            String sticksRegionKey = this.seatRegionKey("sticks", wind);
            this.enqueue(queue, BUCKET_BACKGROUND, () -> this.updateStaticRegion(
                visualRegionKey,
                fingerprintOf(fingerprints, visualRegionKey),
                budget,
                () -> this.session.renderer().renderSeatVisual(this.session, wind)
            ));
            this.enqueue(queue, BUCKET_REACTION_PROMPT, () -> this.updateSeatLabelRegion(
                labelsRegionKey,
                fingerprintOf(fingerprints, labelsRegionKey),
                budget,
                snapshot,
                seat,
                seatPlan
            ));
            this.enqueue(queue, BUCKET_TURN_STATE, () -> this.updateRegion(
                sticksRegionKey,
                fingerprintOf(fingerprints, sticksRegionKey),
                budget,
                () -> this.session.renderer().renderSticks(this.session, seat, seatPlan)
            ));
            this.enqueuePublicHandRegionUpdates(snapshot, seat, seatPlan, budget, queue);
            this.enqueuePrivateHandRegionUpdates(seat, seatPlan, budget, queue);
            this.enqueueDiscardRegionUpdates(seat, seatPlan, budget, queue);
            this.enqueueMeldRegionUpdates(seat, seatPlan, budget, queue);
        }

        metrics.recordGauge("table.render.region.queue.size", queue.size());
        QueueExecution execution = this.applyQueue(queue);
        metrics.incrementCounter("table.render.region.apply.processed", execution.processedUpdates());
        if (execution.deferred()) {
            metrics.incrementCounter("table.render.region.apply.deferred");
            metrics.recordGauge("table.render.region.queue.remaining", queue.size() - execution.processedUpdates());
        } else {
            metrics.recordGauge("table.render.region.queue.remaining", 0L);
        }
        this.recordRegionLoadMetrics(metrics);
        metrics.recordGauge("table.render.region.heap_used_bytes", usedHeapBytes());
        metrics.recordTimerNanos("table.render.region.apply.nanos", System.nanoTime() - startedAt);
        return execution.deferred();
    }

    public void refreshPrivateHandRegions(TableSeatRenderSnapshot seat, TableRenderLayout.SeatLayoutPlan plan) {
        this.updatePrivateHandRegions(seat, plan, ApplyBudget.unlimited());
    }

    public void updateViewerOverlayRegion(TableViewerOverlaySnapshot snapshot) {
        long startedAt = System.nanoTime();
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.viewer_overlay.apply.calls");
        ApplyBudget budget = new ApplyBudget(this.maxRegionUpdatesPerApply, this.maxEntitySpawnsPerApply);
        this.updateViewerVisualOverlayRegion(snapshot, budget);
        this.updateViewerPromptRegion(snapshot.prompt(), budget);
        this.updateViewerActionOverlayRegion(snapshot.actions(), budget);
        metrics.recordTimerNanos("table.render.viewer_overlay.apply.nanos", System.nanoTime() - startedAt);
    }

    public void updateViewerActionRegions(TableViewerOverlaySnapshot snapshot) {
        long startedAt = System.nanoTime();
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.viewer_actions.apply.calls");
        ApplyBudget budget = new ApplyBudget(this.maxRegionUpdatesPerApply, this.maxEntitySpawnsPerApply);
        this.updateViewerPromptRegion(snapshot.prompt(), budget);
        this.updateViewerActionOverlayRegion(snapshot.actions(), budget);
        metrics.recordTimerNanos("table.render.viewer_actions.apply.nanos", System.nanoTime() - startedAt);
    }

    private void updateViewerVisualOverlayRegion(TableViewerOverlaySnapshot snapshot, ApplyBudget budget) {
        String regionKey = snapshot.regionKey();
        long fingerprint = this.fingerprintService.opaqueFingerprint(snapshot.fingerprint());
        if (snapshot.spectator() && this.clientViewerOverlays.isCurrent(regionKey, snapshot.viewerId(), fingerprint)) {
            this.clearRegion(regionKey);
            return;
        }
        if (!snapshot.spectator() || !this.clientViewerOverlays.canAttempt()) {
            this.clientViewerOverlays.remove(regionKey);
            this.updateRegionWithSpecs(
                regionKey,
                fingerprint,
                budget,
                () -> this.session.renderer().renderViewerOverlaySpecs(this.session, snapshot)
            );
            return;
        }

        List<DisplayEntities.EntitySpec> specs = this.session.renderer().renderViewerOverlaySpecs(this.session, snapshot);
        if (!budget.canConsumeRegionUpdate(1) || !budget.canConsumeEntitySpawns(specs.size())) {
            return;
        }
        if (this.clientViewerOverlays.tryUpdate(
                regionKey,
                snapshot.viewerId(),
                fingerprint,
                specs,
                () -> this.scheduleViewerOverlayFallback(snapshot.viewerId(), regionKey)
            )) {
            budget.consumeRegionUpdate(1);
            budget.consumeEntitySpawns(specs.size());
            this.clearRegion(regionKey);
            return;
        }

        this.clientViewerOverlays.remove(regionKey);
        this.updateRegionWithSpecs(regionKey, fingerprint, budget, () -> specs);
    }

    private void scheduleViewerOverlayFallback(UUID viewerId, String regionKey) {
        this.session.plugin().scheduler().runRegion(this.session.center(), () -> {
            Player viewer = this.session.onlinePlayer(viewerId);
            if (viewer == null || !viewer.isOnline() || !this.session.isSpectator(viewerId) || this.clientViewerOverlays.hasRegion(regionKey)) {
                return;
            }
            TableViewerOverlaySnapshot current = this.session.captureViewerOverlaySnapshot(viewer);
            if (!regionKey.equals(current.regionKey())) {
                return;
            }
            long fingerprint = this.fingerprintService.opaqueFingerprint(current.fingerprint());
            List<DisplayEntities.EntitySpec> specs = this.session.renderer().renderViewerOverlaySpecs(this.session, current);
            this.updateRegionWithSpecs(
                regionKey,
                fingerprint,
                new ApplyBudget(1, this.maxEntitySpawnsPerApply),
                () -> specs
            );
        });
    }

    private void updateViewerPromptRegion(TableViewerPromptSnapshot snapshot, ApplyBudget budget) {
        if (snapshot == null) {
            return;
        }
        this.updateRegionWithSpecs(
            snapshot.regionKey(),
            this.fingerprintService.opaqueFingerprint(snapshot.fingerprint()),
            budget,
            () -> this.session.renderer().renderViewerPromptSpecs(this.session, snapshot)
        );
    }

    private void updateViewerActionOverlayRegion(TableViewerActionOverlaySnapshot snapshot, ApplyBudget budget) {
        if (snapshot == null) {
            return;
        }
        String regionKey = snapshot.regionKey();
        long fingerprint = this.fingerprintService.opaqueFingerprint(snapshot.fingerprint());
        this.updateRegionWithRayInteractions(
            regionKey,
            fingerprint,
            budget,
            this.requiresRayInteractions(snapshot),
            () -> {
                ViewerOverlayRenderer.ViewerActionOverlayPlan renderPlan = this.session.renderer()
                    .renderViewerActionOverlayPlan(this.session, snapshot);
                return new RayRegionRenderPlan(
                    renderPlan.entitySpecs(),
                    singleViewerRayInteractions(snapshot.viewerId(), renderPlan.flatInteractions())
                );
            }
        );
    }

    private boolean requiresRayInteractions(TableViewerActionOverlaySnapshot snapshot) {
        for (TableViewerActionButtonSnapshot button : snapshot.actionButtons()) {
            if (button.placement() != TableViewerActionButtonSnapshot.Placement.OVERHEAD_CENTER) {
                return true;
            }
        }
        return false;
    }

    private boolean updateSeatLabelRegion(
        String regionKey,
        long fingerprint,
        ApplyBudget budget,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan seatPlan
    ) {
        boolean requiresRayInteractions = !snapshot.started() && !snapshot.roundStartInProgress();
        return this.updateRegionWithRayInteractions(
            regionKey,
            fingerprint,
            budget,
            requiresRayInteractions,
            () -> {
                SeatRenderer.SeatLabelRenderPlan renderPlan = this.session.renderer()
                    .renderSeatLabelPlan(this.session, seat, seatPlan);
                return new RayRegionRenderPlan(
                    renderPlan.entitySpecs(),
                    renderPlan.rayInteractions(),
                    renderPlan.publicJoinBindings()
                );
            }
        );
    }

    public List<String> regionKeys() {
        Set<String> keys = new LinkedHashSet<>(this.regionFingerprints.keySet());
        keys.addAll(this.regionDisplays.keySet());
        keys.addAll(this.clientViewerOverlays.regionKeys());
        return List.copyOf(keys);
    }

    public List<String> regionKeysWithPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return this.regionKeys();
        }
        List<String> keys = new ArrayList<>();
        for (String regionKey : this.regionKeys()) {
            if (regionKey.startsWith(prefix)) {
                keys.add(regionKey);
            }
        }
        return List.copyOf(keys);
    }

    public void removeManagedRegionDisplays(String regionKey) {
        this.clientViewerOverlays.remove(regionKey);
        this.removeRegionDisplays(regionKey);
        this.regionFingerprints.remove(regionKey);
    }

    public void discardViewerClientOverlay(UUID viewerId) {
        if (viewerId != null) {
            this.clientViewerOverlays.remove(REGION_VIEWER_OVERLAY_PREFIX + viewerId);
            this.rayInputProxies.removeViewer(viewerId);
            this.trackedPublicJoinProxies.discardViewer(viewerId);
        }
    }

    public void trackPublicJoinSource(
        Player player,
        String regionKey,
        int sourceEntityId,
        UUID sourceEntityUuid
    ) {
        this.trackedPublicJoinProxies.track(
            player,
            regionKey,
            sourceEntityId,
            sourceEntityUuid
        );
    }

    public void untrackPublicJoinSource(
        Player player,
        String regionKey,
        int sourceEntityId,
        UUID sourceEntityUuid
    ) {
        this.trackedPublicJoinProxies.untrack(
            player,
            regionKey,
            sourceEntityId,
            sourceEntityUuid
        );
    }

    public void clearRenderDisplays() {
        this.clientViewerOverlays.clear();
        this.rayInputProxies.clear();
        this.trackedPublicJoinProxies.clear();
        DisplayInteractionRayRegistry.clearTable(this.session.id());
        this.rayInteractionOwners.clear();
        this.publicJoinRayRegions.clear();
        this.regionFingerprints.clear();
        this.removeAllDisplays();
        this.recordRegionLoadMetrics(this.metrics());
    }

    /**
     * Synchronous shutdown removal that bypasses the scheduler. During
     * plugin disable the Bukkit scheduler cancels all pending tasks for the
     * plugin, so removeEntity()'s delayed runTask would never execute and
     * entities would leak into the saved chunk data. This method calls
     * entity.remove() directly since onDisable runs on the main thread.
     */
    public void shutdown() {
        this.clientViewerOverlays.shutdown();
        this.rayInputProxies.shutdown();
        this.trackedPublicJoinProxies.shutdown();
        DisplayInteractionRayRegistry.clearTable(this.session.id());
        this.rayInteractionOwners.clear();
        this.publicJoinRayRegions.clear();
        this.regionFingerprints.clear();
        for (String regionKey : List.copyOf(this.regionDisplays.keySet())) {
            List<Entity> entities = this.regionDisplays.remove(regionKey);
            if (entities == null) {
                continue;
            }
            for (Entity entity : entities) {
                TableDisplayRegistry.unregister(entity.getEntityId());
                DisplayVisibilityRegistry.unregister(entity.getEntityId());
                if (this.session.plugin().craftEngine() != null) {
                    this.session.plugin().craftEngine().unregisterCullableEntity(entity);
                }
                boolean removedByCraftEngine = this.session.plugin().craftEngine() != null
                    && this.session.plugin().craftEngine().removeFurniture(entity);
                if (!removedByCraftEngine && !entity.isDead()) {
                    try {
                        entity.remove();
                    } catch (RuntimeException ignored) {
                        // Entity might be in an unloaded chunk or already invalid.
                    }
                }
            }
        }
        this.regionDisplays.clear();
    }

    public void invalidateFingerprints() {
        this.clientViewerOverlays.clear();
        this.regionFingerprints.clear();
        this.recordRegionLoadMetrics(this.metrics());
    }

    public boolean hasRegionDisplays() {
        return !this.regionDisplays.isEmpty() || this.clientViewerOverlays.hasRegions();
    }

    public boolean hasStaleDisplayRegions() {
        if (this.clientViewerOverlays.hasStaleRegions()) {
            return true;
        }
        for (List<Entity> entities : this.regionDisplays.values()) {
            if (this.hasInvalidDisplayEntity(entities)) {
                return true;
            }
        }
        return false;
    }

    private QueueExecution applyQueue(RegionUpdateQueue updates) {
        int processed = 0;
        for (int bucketIndex = 0; bucketIndex < updates.bucketCount(); bucketIndex++) {
            List<RegionUpdateAction> bucket = updates.bucket(bucketIndex);
            for (int updateIndex = 0, bucketSize = bucket.size(); updateIndex < bucketSize; updateIndex++) {
                if (!bucket.get(updateIndex).apply()) {
                    return new QueueExecution(true, processed);
                }
                processed++;
            }
        }
        return new QueueExecution(false, processed);
    }

    private void enqueue(RegionUpdateQueue queue, int bucketIndex, RegionUpdateAction action) {
        queue.add(bucketIndex, action);
    }

    private void enqueuePrivateHandRegionUpdates(
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        ApplyBudget budget,
        RegionUpdateQueue queue
    ) {
        this.clearRegion(this.seatRegionKey("hand-private", seat.wind()));
        int handSize = seat.playerId() == null ? 0 : seat.hand().size();
        for (int tileIndex = 0; tileIndex < MAX_HAND_TILE_REGIONS; tileIndex++) {
            String regionKey = this.handPrivateRegionKey(seat.wind(), tileIndex);
            if (tileIndex >= handSize) {
                this.clearRegion(regionKey);
                continue;
            }
            int index = tileIndex;
            this.enqueue(queue, BUCKET_HAND, () -> this.updatePrivateHandTileRegion(
                regionKey,
                this.fingerprintService.handPrivateTileFingerprint(seat, plan, index),
                budget,
                seat,
                plan,
                index
            ));
        }
    }

    private void enqueuePublicHandRegionUpdates(
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        ApplyBudget budget,
        RegionUpdateQueue queue
    ) {
        this.clearRegion(this.seatRegionKey("hand-public", seat.wind()));
        int handSize = seat.playerId() == null ? 0 : seat.hand().size();
        for (int tileIndex = 0; tileIndex < MAX_HAND_TILE_REGIONS; tileIndex++) {
            String regionKey = this.handPublicRegionKey(seat.wind(), tileIndex);
            if (tileIndex >= handSize) {
                this.clearRegion(regionKey);
                continue;
            }
            int index = tileIndex;
            this.enqueue(queue, BUCKET_HAND, () -> this.updateRegionWithSpecs(
                regionKey,
                this.fingerprintService.handPublicTileFingerprint(snapshot, seat, plan, index),
                budget,
                () -> this.session.renderer().renderHandPublicTileSpecs(this.session, snapshot, seat, plan, index)
            ));
        }
    }

    private void enqueueDiscardRegionUpdates(
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        ApplyBudget budget,
        RegionUpdateQueue queue
    ) {
        this.clearRegion(this.seatRegionKey("discards", seat.wind()));
        int discardCount = seat.playerId() == null ? 0 : plan.discardPlacements().size();
        for (int discardIndex = 0; discardIndex < MAX_DISCARD_TILE_REGIONS; discardIndex++) {
            String regionKey = this.discardRegionKey(seat.wind(), discardIndex);
            if (discardIndex >= discardCount) {
                this.clearRegion(regionKey);
                continue;
            }
            int index = discardIndex;
            this.enqueue(queue, BUCKET_TURN_STATE, () -> this.updateRegionWithSpecs(
                regionKey,
                this.fingerprintService.discardTileFingerprint(seat, plan, index),
                budget,
                () -> this.session.renderer().renderDiscardTileSpecs(this.session, seat, plan, index)
            ));
        }
    }

    private void enqueueMeldRegionUpdates(
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        ApplyBudget budget,
        RegionUpdateQueue queue
    ) {
        this.clearRegion(this.seatRegionKey("melds", seat.wind()));
        int meldCount = seat.playerId() == null ? 0 : plan.meldPlacements().size();
        for (int meldIndex = 0; meldIndex < MAX_MELD_TILE_REGIONS; meldIndex++) {
            String regionKey = this.meldRegionKey(seat.wind(), meldIndex);
            if (meldIndex >= meldCount) {
                this.clearRegion(regionKey);
                continue;
            }
            int index = meldIndex;
            this.enqueue(queue, BUCKET_TURN_STATE, () -> this.updateRegionWithSpecs(
                regionKey,
                this.fingerprintService.meldTileFingerprint(seat, plan, index),
                budget,
                () -> this.session.renderer().renderMeldTileSpecs(this.session, seat, plan, index)
            ));
        }
    }

    private void enqueueWallRegionUpdates(
        TableRenderLayout.LayoutPlan plan,
        ApplyBudget budget,
        RegionUpdateQueue queue
    ) {
        this.clearRegion(REGION_WALL);
        int wallTileCount = plan.wallTiles().size();
        for (int wallIndex = 0; wallIndex < MAX_WALL_TILE_REGIONS; wallIndex++) {
            String regionKey = this.wallRegionKey(wallIndex);
            if (wallIndex >= wallTileCount) {
                this.clearRegion(regionKey);
                continue;
            }
            int index = wallIndex;
            this.enqueue(queue, BUCKET_BACKGROUND, () -> this.updateRegionWithSpecs(
                regionKey,
                this.fingerprintService.wallTileFingerprint(plan, index),
                budget,
                () -> this.session.renderer().renderWallTileSpecs(this.session, plan, index)
            ));
        }
    }

    private boolean updatePrivateHandRegions(TableSeatRenderSnapshot seat, TableRenderLayout.SeatLayoutPlan plan, ApplyBudget budget) {
        this.clearRegion(this.seatRegionKey("hand-private", seat.wind()));
        int handSize = seat.playerId() == null ? 0 : seat.hand().size();
        for (int tileIndex = 0; tileIndex < MAX_HAND_TILE_REGIONS; tileIndex++) {
            String regionKey = this.handPrivateRegionKey(seat.wind(), tileIndex);
            if (tileIndex >= handSize) {
                this.clearRegion(regionKey);
                continue;
            }
            int index = tileIndex;
            if (!this.updatePrivateHandTileRegion(
                regionKey,
                this.fingerprintService.handPrivateTileFingerprint(seat, plan, tileIndex),
                budget,
                seat,
                plan,
                index
            )) {
                return false;
            }
        }
        return true;
    }

    private boolean updatePrivateHandTileRegion(
        String regionKey,
        long fingerprint,
        ApplyBudget budget,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        UUID viewerId = seat.playerId();
        if (viewerId == null) {
            this.clearRegion(regionKey);
            return true;
        }
        return this.updateRegionWithRayInteractions(
            regionKey,
            fingerprint,
            budget,
            true,
            () -> {
                HandRenderer.HandTileRenderPlan renderPlan = this.session.renderer()
                    .renderHandPrivateTilePlan(this.session, seat, plan, tileIndex);
                return new RayRegionRenderPlan(
                    renderPlan.entitySpecs(),
                    singleViewerRayInteractions(viewerId, renderPlan.rayInteractions())
                );
            }
        );
    }

    private boolean updateRegionWithRayInteractions(
        String regionKey,
        long fingerprint,
        ApplyBudget budget,
        boolean requiresRayInteractions,
        RayRegionRenderer renderer
    ) {
        Long previousFingerprint = this.regionFingerprints.get(regionKey);
        List<Entity> currentEntities = this.regionDisplays.get(regionKey);
        if (this.hasInvalidDisplayEntity(currentEntities)) {
            this.removeRegionDisplays(regionKey);
            previousFingerprint = null;
            currentEntities = null;
        }
        if (previousFingerprint != null
            && previousFingerprint == fingerprint
            && (currentEntities != null || this.regionFingerprints.containsKey(regionKey))
            && (!requiresRayInteractions || this.rayInteractionsCurrent(regionKey))) {
            return true;
        }
        if (!budget.canConsumeRegionUpdate(1)) {
            return false;
        }
        RayRegionRenderPlan renderPlan = renderer.render();
        List<DisplayEntities.EntitySpec> specs = renderPlan.entitySpecs();
        if (currentEntities != null && DisplayEntities.reconcile(this.session, currentEntities, specs)) {
            budget.consumeRegionUpdate(1);
            this.regionFingerprints.put(regionKey, fingerprint);
            this.replaceRayInteractions(
                regionKey,
                renderPlan.rayInteractions(),
                renderPlan.publicJoinBindings(),
                currentEntities
            );
            return true;
        }
        if (!budget.canConsumeEntitySpawns(specs.size())) {
            return false;
        }
        budget.consumeRegionUpdate(1);
        budget.consumeEntitySpawns(specs.size());
        this.removeRegionDisplays(regionKey);
        List<Entity> entities = DisplayEntities.spawnAll(this.session, specs);
        if (!entities.isEmpty()) {
            this.regionDisplays.put(regionKey, entities);
        }
        this.regionFingerprints.put(regionKey, fingerprint);
        this.replaceRayInteractions(
            regionKey,
            renderPlan.rayInteractions(),
            renderPlan.publicJoinBindings(),
            entities
        );
        return true;
    }

    private boolean rayInteractionsCurrent(String regionKey) {
        Set<UUID> owners = this.rayInteractionOwners.get(regionKey);
        boolean hasPublicJoin = this.publicJoinRayRegions.contains(regionKey);
        if (hasPublicJoin
            && (!DisplayInteractionRayRegistry.isPublicJoinRegionCurrent(this.session.id(), regionKey)
                || !DisplayInteractionRayRegistry.isPublicJoinSourceCurrent(this.session.id(), regionKey))) {
            return false;
        }
        if (owners != null) {
            for (UUID ownerId : owners) {
                if (!DisplayInteractionRayRegistry.isRegionCurrent(ownerId, this.session.id(), regionKey)) {
                    return false;
                }
            }
        }
        boolean hasPrivateInteractions = owners != null && !owners.isEmpty();
        return (hasPrivateInteractions || hasPublicJoin)
            && (!hasPrivateInteractions || this.rayInputProxies.isCurrent(regionKey, owners));
    }

    private void replaceRayInteractions(
        String regionKey,
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactionsByViewer,
        List<SeatRenderer.PublicJoinBinding> publicJoinBindings,
        List<Entity> regionEntities
    ) {
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactions = interactionsByViewer == null
            ? Map.of()
            : interactionsByViewer;
        Set<UUID> nextOwners = new LinkedHashSet<>();
        interactions.forEach((viewerId, viewerInteractions) -> {
            if (viewerId != null && viewerInteractions != null && !viewerInteractions.isEmpty()) {
                nextOwners.add(viewerId);
            }
        });
        Set<UUID> previousOwners = this.rayInteractionOwners.get(regionKey);
        if (previousOwners != null) {
            for (UUID previousOwner : previousOwners) {
                if (!nextOwners.contains(previousOwner)) {
                    DisplayInteractionRayRegistry.clearRegion(previousOwner, this.session.id(), regionKey);
                }
            }
        }
        interactions.forEach((viewerId, viewerInteractions) -> {
            if (viewerId != null) {
                DisplayInteractionRayRegistry.replaceRegion(
                    viewerId,
                    this.session.id(),
                    regionKey,
                    viewerInteractions
                );
            }
        });
        List<DisplayInteractionRayRegistry.RayInteraction> publicJoinInteractions = publicJoinBindings == null
            ? List.of()
            : publicJoinBindings.stream().map(SeatRenderer.PublicJoinBinding::interaction).toList();
        DisplayInteractionRayRegistry.replacePublicJoinRegion(
            this.session.id(),
            regionKey,
            publicJoinInteractions
        );
        if (publicJoinInteractions == null || publicJoinInteractions.isEmpty()) {
            this.publicJoinRayRegions.remove(regionKey);
        } else {
            this.publicJoinRayRegions.add(regionKey);
        }
        this.trackedPublicJoinProxies.replaceRegion(regionKey, publicJoinBindings, regionEntities);
        this.rayInputProxies.replace(regionKey, interactions);
        if (nextOwners.isEmpty()) {
            this.rayInteractionOwners.remove(regionKey);
        } else {
            this.rayInteractionOwners.put(regionKey, Set.copyOf(nextOwners));
        }
    }

    private static Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> singleViewerRayInteractions(
        UUID viewerId,
        List<DisplayInteractionRayRegistry.RayInteraction> interactions
    ) {
        return viewerId == null || interactions == null || interactions.isEmpty()
            ? Map.of()
            : Map.of(viewerId, List.copyOf(interactions));
    }

    private boolean updateRegion(String regionKey, long fingerprint, ApplyBudget budget, RegionRenderer renderer) {
        Long previousFingerprint = this.regionFingerprints.get(regionKey);
        List<Entity> currentEntities = this.regionDisplays.get(regionKey);
        if (this.hasInvalidDisplayEntity(currentEntities)) {
            this.removeRegionDisplays(regionKey);
            previousFingerprint = null;
            currentEntities = null;
        }
        if (previousFingerprint != null && previousFingerprint == fingerprint && (currentEntities != null || this.regionFingerprints.containsKey(regionKey))) {
            return true;
        }
        if (!budget.tryConsumeRegionUpdate(1)) {
            return false;
        }
        this.removeRegionDisplays(regionKey);
        List<Entity> entities = renderer.render();
        if (!entities.isEmpty()) {
            this.regionDisplays.put(regionKey, entities);
        }
        this.regionFingerprints.put(regionKey, fingerprint);
        return true;
    }

    private boolean updateStaticRegion(String regionKey, long fingerprint, ApplyBudget budget, RegionRenderer renderer) {
        List<Entity> currentEntities = this.regionDisplays.get(regionKey);
        if (!this.hasInvalidDisplayEntity(currentEntities) && currentEntities != null) {
            this.regionFingerprints.put(regionKey, fingerprint);
            return true;
        }
        return this.updateRegion(regionKey, fingerprint, budget, renderer);
    }

    private boolean updateRegionWithSpecs(String regionKey, long fingerprint, ApplyBudget budget, RegionSpecRenderer renderer) {
        Long previousFingerprint = this.regionFingerprints.get(regionKey);
        List<Entity> currentEntities = this.regionDisplays.get(regionKey);
        if (this.hasInvalidDisplayEntity(currentEntities)) {
            this.removeRegionDisplays(regionKey);
            previousFingerprint = null;
            currentEntities = null;
        }
        if (previousFingerprint != null && previousFingerprint == fingerprint && (currentEntities != null || this.regionFingerprints.containsKey(regionKey))) {
            return true;
        }
        if (!budget.canConsumeRegionUpdate(1)) {
            return false;
        }
        List<DisplayEntities.EntitySpec> specs = renderer.render();
        if (currentEntities != null && DisplayEntities.reconcile(this.session, currentEntities, specs)) {
            budget.consumeRegionUpdate(1);
            this.regionFingerprints.put(regionKey, fingerprint);
            return true;
        }
        if (!budget.canConsumeEntitySpawns(specs.size())) {
            return false;
        }
        budget.consumeRegionUpdate(1);
        budget.consumeEntitySpawns(specs.size());
        this.removeRegionDisplays(regionKey);
        List<Entity> entities = DisplayEntities.spawnAll(this.session, specs);
        if (!entities.isEmpty()) {
            this.regionDisplays.put(regionKey, entities);
        }
        this.regionFingerprints.put(regionKey, fingerprint);
        return true;
    }

    private void clearRegion(String regionKey) {
        this.removeRegionDisplays(regionKey);
        this.regionFingerprints.remove(regionKey);
    }

    private void removeAllDisplays() {
        for (String regionKey : List.copyOf(this.regionDisplays.keySet())) {
            this.removeRegionDisplays(regionKey);
        }
        this.regionDisplays.clear();
    }

    private void removeRegionDisplays(String regionKey) {
        this.clearFlatInteractionsForRegion(regionKey);
        List<Entity> entities = this.regionDisplays.remove(regionKey);
        if (entities == null) {
            return;
        }
        for (Entity entity : entities) {
            TableDisplayRegistry.unregister(entity.getEntityId());
            DisplayVisibilityRegistry.unregister(entity.getEntityId());
            if (this.session.plugin().craftEngine() != null) {
                this.session.plugin().craftEngine().unregisterCullableEntity(entity);
            }
            boolean removedByCraftEngine = this.session.plugin().craftEngine() != null
                && this.session.plugin().craftEngine().removeFurniture(entity);
            if (!removedByCraftEngine && !entity.isDead()) {
                this.session.plugin().scheduler().removeEntity(entity);
            }
        }
        this.regionFingerprints.remove(regionKey);
    }

    private void clearFlatInteractionsForRegion(String regionKey) {
        this.rayInputProxies.remove(regionKey);
        this.trackedPublicJoinProxies.removeRegion(regionKey);
        this.publicJoinRayRegions.remove(regionKey);
        DisplayInteractionRayRegistry.clearPublicJoinRegion(this.session.id(), regionKey);
        Set<UUID> ownerIds = this.rayInteractionOwners.remove(regionKey);
        if (ownerIds != null) {
            for (UUID ownerId : ownerIds) {
                DisplayInteractionRayRegistry.clearRegion(ownerId, this.session.id(), regionKey);
            }
            return;
        }
        if (regionKey == null || !regionKey.startsWith(REGION_VIEWER_ACTIONS_PREFIX)) {
            return;
        }
        String viewerId = regionKey.substring(REGION_VIEWER_ACTIONS_PREFIX.length());
        try {
            DisplayInteractionRayRegistry.clearRegion(
                UUID.fromString(viewerId),
                this.session.id(),
                regionKey
            );
        } catch (IllegalArgumentException ignored) {
            // Only UUID-backed viewer action regions can own flat interactions.
        }
    }

    private boolean hasInvalidDisplayEntity(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return false;
        }
        for (Entity entity : entities) {
            if (entity == null || entity.isDead() || !entity.isValid()) {
                return true;
            }
        }
        return false;
    }

    private MetricsCollector metrics() {
        try {
            if (this.session == null || this.session.plugin() == null || this.session.plugin().metrics() == null) {
                return NoopMetricsCollector.instance();
            }
            return this.session.plugin().metrics();
        } catch (RuntimeException ignored) {
            return NoopMetricsCollector.instance();
        }
    }

    private void recordRegionLoadMetrics(MetricsCollector metrics) {
        if (metrics == null) {
            return;
        }
        metrics.recordGauge("table.render.region.active_regions", this.regionFingerprints.size() + this.clientViewerOverlays.regionCount());
        metrics.recordGauge("table.render.region.regions_with_entities", this.regionDisplays.size() + this.clientViewerOverlays.regionCount());
        metrics.recordGauge(
            "table.render.region.managed_entities",
            this.managedEntityCount()
                + this.clientViewerOverlays.entityCount()
                + this.rayInputProxies.entityCount()
                + this.trackedPublicJoinProxies.entityCount()
        );
        metrics.recordGauge(
            "table.render.region.client_input_proxy_entities",
            this.rayInputProxies.entityCount() + this.trackedPublicJoinProxies.entityCount()
        );
        metrics.recordGauge(
            "table.render.region.viewer_overlay_regions",
            this.countRegionsWithPrefix(this.regionFingerprints, REGION_VIEWER_OVERLAY_PREFIX) + this.clientViewerOverlays.regionCount()
        );
        metrics.recordGauge(
            "table.render.region.viewer_overlay_entities",
            this.managedEntityCountWithPrefix(REGION_VIEWER_OVERLAY_PREFIX) + this.clientViewerOverlays.entityCount()
        );
    }

    private long managedEntityCount() {
        long total = 0L;
        for (List<Entity> entities : this.regionDisplays.values()) {
            total += entities.size();
        }
        return total;
    }

    private long managedEntityCountWithPrefix(String prefix) {
        long total = 0L;
        for (Map.Entry<String, List<Entity>> entry : this.regionDisplays.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                total += entry.getValue().size();
            }
        }
        return total;
    }

    private long countRegionsWithPrefix(Map<String, ?> regions, String prefix) {
        long total = 0L;
        for (String regionKey : regions.keySet()) {
            if (regionKey.startsWith(prefix)) {
                total++;
            }
        }
        return total;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private String seatRegionKey(String region, SeatWind wind) {
        if ("visual".equals(region)) {
            return VISUAL_REGION_KEYS[wind.index()];
        }
        if ("labels".equals(region)) {
            return LABEL_REGION_KEYS[wind.index()];
        }
        if ("sticks".equals(region)) {
            return STICK_REGION_KEYS[wind.index()];
        }
        if ("hand-public".equals(region)) {
            return HAND_PUBLIC_REGION_KEYS[wind.index()];
        }
        if ("hand-private".equals(region)) {
            return HAND_PRIVATE_REGION_KEYS[wind.index()];
        }
        if ("discards".equals(region)) {
            return DISCARD_REGION_KEYS[wind.index()];
        }
        if ("melds".equals(region)) {
            return MELD_REGION_KEYS[wind.index()];
        }
        return region + ":" + wind.name();
    }

    private String handPrivateRegionKey(SeatWind wind, int tileIndex) {
        if (tileIndex >= 0 && tileIndex < MAX_HAND_TILE_REGIONS) {
            return HAND_PRIVATE_TILE_REGION_KEYS[wind.index()][tileIndex];
        }
        return this.seatRegionKey("hand-private-" + tileIndex, wind);
    }

    private String handPublicRegionKey(SeatWind wind, int tileIndex) {
        if (tileIndex >= 0 && tileIndex < MAX_HAND_TILE_REGIONS) {
            return HAND_PUBLIC_TILE_REGION_KEYS[wind.index()][tileIndex];
        }
        return this.seatRegionKey("hand-public-" + tileIndex, wind);
    }

    private String discardRegionKey(SeatWind wind, int discardIndex) {
        if (discardIndex >= 0 && discardIndex < MAX_DISCARD_TILE_REGIONS) {
            return DISCARD_TILE_REGION_KEYS[wind.index()][discardIndex];
        }
        return this.seatRegionKey("discards-" + discardIndex, wind);
    }

    private String meldRegionKey(SeatWind wind, int meldIndex) {
        if (meldIndex >= 0 && meldIndex < MAX_MELD_TILE_REGIONS) {
            return MELD_TILE_REGION_KEYS[wind.index()][meldIndex];
        }
        return this.seatRegionKey("melds-" + meldIndex, wind);
    }

    private String wallRegionKey(int wallIndex) {
        if (wallIndex >= 0 && wallIndex < MAX_WALL_TILE_REGIONS) {
            return WALL_TILE_REGION_KEYS[wallIndex];
        }
        return REGION_WALL + "-" + wallIndex;
    }

    private static String[] createSeatRegionKeys(String region) {
        SeatWind[] winds = SeatWind.values();
        String[] keys = new String[winds.length];
        for (SeatWind wind : winds) {
            keys[wind.index()] = region + ":" + wind.name();
        }
        return keys;
    }

    private static String[][] createIndexedSeatRegionKeys(String region, int count) {
        SeatWind[] winds = SeatWind.values();
        String[][] keys = new String[winds.length][count];
        for (SeatWind wind : winds) {
            for (int index = 0; index < count; index++) {
                keys[wind.index()][index] = region + "-" + index + ":" + wind.name();
            }
        }
        return keys;
    }

    private static String[] createIndexedRegionKeys(String region, int count) {
        String[] keys = new String[count];
        for (int index = 0; index < count; index++) {
            keys[index] = region + "-" + index;
        }
        return keys;
    }

    private static long fingerprintOf(Map<String, Long> fingerprints, String regionKey) {
        Long value = fingerprints.get(regionKey);
        return value == null ? 0L : value;
    }

    @FunctionalInterface
    private interface RegionRenderer {
        List<Entity> render();
    }

    @FunctionalInterface
    private interface RegionSpecRenderer {
        List<DisplayEntities.EntitySpec> render();
    }

    @FunctionalInterface
    private interface RayRegionRenderer {
        RayRegionRenderPlan render();
    }

    @FunctionalInterface
    private interface RegionUpdateAction {
        boolean apply();
    }

    private static final class ApplyBudget {
        private int remainingRegionUpdates;
        private int remainingEntitySpawns;

        private ApplyBudget(int remainingRegionUpdates, int remainingEntitySpawns) {
            this.remainingRegionUpdates = remainingRegionUpdates;
            this.remainingEntitySpawns = remainingEntitySpawns;
        }

        static ApplyBudget unlimited() {
            return new ApplyBudget(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        boolean tryConsumeRegionUpdate(int amount) {
            if (this.remainingRegionUpdates < amount) {
                return false;
            }
            this.remainingRegionUpdates -= amount;
            return true;
        }

        boolean tryConsumeEntitySpawns(int amount) {
            if (this.remainingEntitySpawns < amount) {
                return false;
            }
            this.remainingEntitySpawns -= amount;
            return true;
        }

        boolean canConsumeRegionUpdate(int amount) {
            return this.remainingRegionUpdates >= amount;
        }

        boolean canConsumeEntitySpawns(int amount) {
            return this.remainingEntitySpawns >= amount;
        }

        void consumeRegionUpdate(int amount) {
            this.remainingRegionUpdates -= amount;
        }

        void consumeEntitySpawns(int amount) {
            this.remainingEntitySpawns -= amount;
        }
    }

    private static final class RegionUpdateQueue {
        private final List<RegionUpdateAction>[] buckets;
        private int size;

        @SuppressWarnings("unchecked")
        private RegionUpdateQueue() {
            this.buckets = (List<RegionUpdateAction>[]) new List<?>[] {
                new ArrayList<>(1 + SEAT_COUNT),
                new ArrayList<>(2 * MAX_HAND_TILE_REGIONS * SEAT_COUNT),
                new ArrayList<>((1 + MAX_DISCARD_TILE_REGIONS + MAX_MELD_TILE_REGIONS) * SEAT_COUNT),
                new ArrayList<>(2),
                new ArrayList<>(MAX_WALL_TILE_REGIONS + SEAT_COUNT),
            };
        }

        private void add(int bucketIndex, RegionUpdateAction action) {
            this.buckets[bucketIndex].add(action);
            this.size++;
        }

        private List<RegionUpdateAction> bucket(int bucketIndex) {
            return this.buckets[bucketIndex];
        }

        private int bucketCount() {
            return this.buckets.length;
        }

        private int size() {
            return this.size;
        }
    }

    private record QueueExecution(boolean deferred, int processedUpdates) {
    }

    private record RayRegionRenderPlan(
        List<DisplayEntities.EntitySpec> entitySpecs,
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> rayInteractions,
        List<SeatRenderer.PublicJoinBinding> publicJoinBindings
    ) {
        private RayRegionRenderPlan(
            List<DisplayEntities.EntitySpec> entitySpecs,
            Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> rayInteractions
        ) {
            this(entitySpecs, rayInteractions, List.of());
        }

        private RayRegionRenderPlan {
            entitySpecs = List.copyOf(entitySpecs);
            Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> immutable = new LinkedHashMap<>();
            rayInteractions.forEach((viewerId, interactions) -> immutable.put(viewerId, List.copyOf(interactions)));
            rayInteractions = Map.copyOf(immutable);
            publicJoinBindings = List.copyOf(publicJoinBindings);
        }
    }
}
