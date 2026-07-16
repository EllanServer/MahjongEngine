package top.ellan.mahjong.table.render;

import top.ellan.mahjong.metrics.MetricsCollector;
import top.ellan.mahjong.metrics.NoopMetricsCollector;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayVisibilityRegistry;
import top.ellan.mahjong.render.display.TableDisplayRegistry;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.table.core.TableSessionContext;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerPromptSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Entity;

public final class TableRegionDisplayCoordinator {
    private static final String REGION_TABLE = "table";
    private static final String REGION_WALL = "wall";
    private static final String REGION_DORA = "dora";
    private static final String REGION_CENTER = "center";
    private static final String REGION_VIEWER_OVERLAY_PREFIX = "viewer-overlay:";
    private static final int MAX_WALL_TILE_REGIONS = 136;
    private static final int MAX_HAND_TILE_REGIONS = 14;
    private static final int MAX_DISCARD_TILE_REGIONS = 24;
    private static final int MAX_MELD_TILE_REGIONS = 20;
    private static final int DEFAULT_MAX_REGION_UPDATES_PER_APPLY = 64;
    private static final int DEFAULT_MAX_ENTITY_SPAWNS_PER_APPLY = 192;
    private static final int PRIORITY_REACTION_PROMPT = 400;
    private static final int PRIORITY_HAND = 320;
    private static final int PRIORITY_TURN_STATE = 240;
    private static final int PRIORITY_BOARD = 160;
    private static final int PRIORITY_BACKGROUND = 80;
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
    private static final int[] APPLY_PRIORITY_ORDER = {
        PRIORITY_REACTION_PROMPT,
        PRIORITY_HAND,
        PRIORITY_TURN_STATE,
        PRIORITY_BOARD,
        PRIORITY_BACKGROUND
    };

    private final TableSessionContext session;
    private final TableRegionFingerprintService fingerprintService;
    private final int maxRegionUpdatesPerApply;
    private final int maxEntitySpawnsPerApply;
    private final Map<String, List<Entity>> regionDisplays = new LinkedHashMap<>();
    private final Map<String, Long> regionFingerprints = new HashMap<>();

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
        List<QueuedRegionUpdate> queue = new ArrayList<>(512);

        this.enqueue(queue, PRIORITY_BOARD, () -> this.updateStaticRegion(
            REGION_TABLE,
            fingerprintOf(fingerprints, REGION_TABLE),
            budget,
            () -> this.session.renderer().renderTableStructure(this.session, plan)
        ));
        this.enqueueWallRegionUpdates(plan, budget, queue);
        this.enqueue(queue, PRIORITY_BOARD, () -> this.updateRegionWithSpecs(
            REGION_DORA,
            fingerprintOf(fingerprints, REGION_DORA),
            budget,
            () -> this.session.renderer().renderDoraSpecs(this.session, plan)
        ));
        this.enqueue(queue, PRIORITY_REACTION_PROMPT, () -> this.updateRegionWithSpecs(
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
            this.enqueue(queue, PRIORITY_BACKGROUND, () -> this.updateStaticRegion(
                visualRegionKey,
                fingerprintOf(fingerprints, visualRegionKey),
                budget,
                () -> this.session.renderer().renderSeatVisual(this.session, wind)
            ));
            this.enqueue(queue, PRIORITY_REACTION_PROMPT, () -> this.updateRegionWithSpecs(
                labelsRegionKey,
                fingerprintOf(fingerprints, labelsRegionKey),
                budget,
                () -> this.session.renderer().renderSeatLabelSpecs(this.session, seat, seatPlan)
            ));
            this.enqueue(queue, PRIORITY_TURN_STATE, () -> this.updateRegion(
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
        this.updateRegionWithSpecs(
            snapshot.regionKey(),
            this.fingerprintService.opaqueFingerprint(snapshot.fingerprint()),
            ApplyBudget.unlimited(),
            () -> this.session.renderer().renderViewerOverlaySpecs(this.session, snapshot)
        );
        this.updateViewerPromptRegion(snapshot.prompt());
        this.updateViewerActionOverlayRegion(snapshot.actions());
        this.recordRegionLoadMetrics(metrics);
        metrics.recordTimerNanos("table.render.viewer_overlay.apply.nanos", System.nanoTime() - startedAt);
    }

    public void updateViewerActionRegions(TableViewerOverlaySnapshot snapshot) {
        long startedAt = System.nanoTime();
        MetricsCollector metrics = this.metrics();
        metrics.incrementCounter("table.render.viewer_actions.apply.calls");
        this.updateViewerPromptRegion(snapshot.prompt());
        this.updateViewerActionOverlayRegion(snapshot.actions());
        this.recordRegionLoadMetrics(metrics);
        metrics.recordTimerNanos("table.render.viewer_actions.apply.nanos", System.nanoTime() - startedAt);
    }

    private void updateViewerPromptRegion(TableViewerPromptSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.updateRegionWithSpecs(
            snapshot.regionKey(),
            this.fingerprintService.opaqueFingerprint(snapshot.fingerprint()),
            ApplyBudget.unlimited(),
            () -> this.session.renderer().renderViewerPromptSpecs(this.session, snapshot)
        );
    }

    private void updateViewerActionOverlayRegion(TableViewerActionOverlaySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.updateRegionWithSpecs(
            snapshot.regionKey(),
            this.fingerprintService.opaqueFingerprint(snapshot.fingerprint()),
            ApplyBudget.unlimited(),
            () -> this.session.renderer().renderViewerActionOverlaySpecs(this.session, snapshot)
        );
    }

    public List<String> regionKeys() {
        return List.copyOf(this.regionDisplays.keySet());
    }

    public List<String> regionKeysWithPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return this.regionKeys();
        }
        List<String> keys = new ArrayList<>();
        for (String regionKey : this.regionDisplays.keySet()) {
            if (regionKey.startsWith(prefix)) {
                keys.add(regionKey);
            }
        }
        return List.copyOf(keys);
    }

    public void removeManagedRegionDisplays(String regionKey) {
        this.removeRegionDisplays(regionKey);
        this.recordRegionLoadMetrics(this.metrics());
    }

    public void clearRenderDisplays() {
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
        this.regionFingerprints.clear();
        this.recordRegionLoadMetrics(this.metrics());
    }

    public boolean hasRegionDisplays() {
        return !this.regionDisplays.isEmpty();
    }

    public boolean hasStaleDisplayRegions() {
        for (List<Entity> entities : this.regionDisplays.values()) {
            if (this.hasInvalidDisplayEntity(entities)) {
                return true;
            }
        }
        return false;
    }

    private QueueExecution applyQueue(List<QueuedRegionUpdate> updates) {
        if (!this.hasProductionQueueOrder(updates)) {
            return this.applySortedQueue(updates);
        }
        int processed = 0;
        for (int priority : APPLY_PRIORITY_ORDER) {
            for (QueuedRegionUpdate update : updates) {
                if (update.priority() != priority) {
                    continue;
                }
                if (!update.action().apply()) {
                    return new QueueExecution(true, processed);
                }
                processed++;
            }
        }
        return new QueueExecution(false, processed);
    }

    private boolean hasProductionQueueOrder(List<QueuedRegionUpdate> updates) {
        long previousSequence = Long.MIN_VALUE;
        for (QueuedRegionUpdate update : updates) {
            if (update.sequence() < previousSequence || !this.isProductionPriority(update.priority())) {
                return false;
            }
            previousSequence = update.sequence();
        }
        return true;
    }

    private boolean isProductionPriority(int priority) {
        return switch (priority) {
            case PRIORITY_REACTION_PROMPT, PRIORITY_HAND, PRIORITY_TURN_STATE, PRIORITY_BOARD, PRIORITY_BACKGROUND -> true;
            default -> false;
        };
    }

    private QueueExecution applySortedQueue(List<QueuedRegionUpdate> updates) {
        updates.sort(
            Comparator.comparingInt(QueuedRegionUpdate::priority).reversed()
                .thenComparingLong(QueuedRegionUpdate::sequence)
        );
        int processed = 0;
        for (QueuedRegionUpdate update : updates) {
            if (!update.action().apply()) {
                return new QueueExecution(true, processed);
            }
            processed++;
        }
        return new QueueExecution(false, processed);
    }

    private void enqueue(List<QueuedRegionUpdate> queue, int priority, RegionUpdateAction action) {
        queue.add(new QueuedRegionUpdate(priority, queue.size(), action));
    }

    private void enqueuePrivateHandRegionUpdates(
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        ApplyBudget budget,
        List<QueuedRegionUpdate> queue
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
            this.enqueue(queue, PRIORITY_HAND, () -> this.updateRegionWithSpecs(
                regionKey,
                this.fingerprintService.handPrivateTileFingerprint(seat, plan, index),
                budget,
                () -> this.session.renderer().renderHandPrivateTileSpecs(this.session, seat, plan, index)
            ));
        }
    }

    private void enqueuePublicHandRegionUpdates(
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        ApplyBudget budget,
        List<QueuedRegionUpdate> queue
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
            this.enqueue(queue, PRIORITY_HAND, () -> this.updateRegionWithSpecs(
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
        List<QueuedRegionUpdate> queue
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
            this.enqueue(queue, PRIORITY_TURN_STATE, () -> this.updateRegionWithSpecs(
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
        List<QueuedRegionUpdate> queue
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
            this.enqueue(queue, PRIORITY_TURN_STATE, () -> this.updateRegionWithSpecs(
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
        List<QueuedRegionUpdate> queue
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
            this.enqueue(queue, PRIORITY_BACKGROUND, () -> this.updateRegionWithSpecs(
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
            if (!this.updateRegionWithSpecs(
                regionKey,
                this.fingerprintService.handPrivateTileFingerprint(seat, plan, tileIndex),
                budget,
                () -> this.session.renderer().renderHandPrivateTileSpecs(this.session, seat, plan, index)
            )) {
                return false;
            }
        }
        return true;
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
        if (!budget.canConsumeEntitySpawns(specs.size())) {
            return false;
        }
        if (currentEntities != null && DisplayEntities.reconcile(this.session, currentEntities, specs)) {
            budget.consumeRegionUpdate(1);
            this.regionFingerprints.put(regionKey, fingerprint);
            return true;
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
        metrics.recordGauge("table.render.region.active_regions", this.regionFingerprints.size());
        metrics.recordGauge("table.render.region.regions_with_entities", this.regionDisplays.size());
        metrics.recordGauge("table.render.region.managed_entities", this.managedEntityCount());
        metrics.recordGauge("table.render.region.viewer_overlay_regions", this.countRegionsWithPrefix(this.regionFingerprints, REGION_VIEWER_OVERLAY_PREFIX));
        metrics.recordGauge("table.render.region.viewer_overlay_entities", this.managedEntityCountWithPrefix(REGION_VIEWER_OVERLAY_PREFIX));
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

    private record QueuedRegionUpdate(int priority, long sequence, RegionUpdateAction action) {
    }

    private record QueueExecution(boolean deferred, int processedUpdates) {
    }
}
