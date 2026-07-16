package top.ellan.mahjong.table.render;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.scene.SeatRenderer;
import top.ellan.mahjong.table.core.TableSessionContext;

/** Sends JOIN wake-up proxies only to players tracking that exact JOIN label. */
final class TrackedPublicJoinProxyCoordinator {
    private static final String PROXY_REGION_PREFIX = "tracked-public-join:";

    private final TableSessionContext session;
    private final SparrowRayInteractionProxyCoordinator proxies;
    private final Map<String, RegionState> regions = new HashMap<>();
    private final Map<TrackingKey, TrackingState> tracked = new HashMap<>();
    private long generationClock;

    TrackedPublicJoinProxyCoordinator(TableSessionContext session) {
        this(session, new SparrowRayInteractionProxyCoordinator(session));
    }

    TrackedPublicJoinProxyCoordinator(
        TableSessionContext session,
        SparrowRayInteractionProxyCoordinator proxies
    ) {
        this.session = session;
        this.proxies = proxies;
    }

    synchronized void replaceRegion(
        String regionKey,
        List<SeatRenderer.PublicJoinBinding> bindings,
        List<Entity> regionEntities
    ) {
        if (regionKey == null || bindings == null || bindings.isEmpty()) {
            this.removeRegion(regionKey);
            return;
        }
        Set<SourceToken> sources = new LinkedHashSet<>();
        for (SeatRenderer.PublicJoinBinding binding : bindings) {
            int index = binding.specIndex();
            if (regionEntities == null || index >= regionEntities.size()) {
                continue;
            }
            Entity entity = regionEntities.get(index);
            if (entity instanceof TextDisplay) {
                sources.add(new SourceToken(entity.getEntityId(), entity.getUniqueId()));
            }
        }
        if (sources.isEmpty()) {
            this.removeRegion(regionKey);
            return;
        }

        long generation = ++this.generationClock;
        List<DisplayInteractionRayRegistry.RayInteraction> interactions = bindings.stream()
            .map(SeatRenderer.PublicJoinBinding::interaction)
            .toList();
        RegionState region = new RegionState(generation, interactions, Set.copyOf(sources));
        this.regions.put(regionKey, region);
        DisplayInteractionRayRegistry.clearPublicJoinSource(this.session.id(), regionKey);
        for (SourceToken source : sources) {
            DisplayInteractionRayRegistry.registerPublicJoinSource(
                this.session.id(),
                regionKey,
                source.entityId(),
                source.entityUuid()
            );
        }

        for (TrackingKey key : List.copyOf(this.tracked.keySet())) {
            if (!regionKey.equals(key.regionKey())) {
                continue;
            }
            TrackingState state = this.tracked.get(key);
            state.sources().retainAll(sources);
            if (state.sources().isEmpty()) {
                this.removeTracking(key);
            } else {
                this.scheduleProxyReplace(key, state, region);
            }
        }
        for (SourceToken source : sources) {
            Entity entity = regionEntities.get(bindingIndexForSource(bindings, regionEntities, source));
            for (Player player : entity.getTrackedPlayers()) {
                this.track(player, regionKey, source.entityId(), source.entityUuid());
            }
        }
    }

    void track(Player player, String regionKey, int sourceEntityId, UUID sourceEntityUuid) {
        if (player == null || regionKey == null || sourceEntityUuid == null) {
            return;
        }
        RegionState region;
        synchronized (this) {
            region = this.regions.get(regionKey);
            if (region == null || !region.sources().contains(new SourceToken(sourceEntityId, sourceEntityUuid))) {
                return;
            }
        }
        long expectedGeneration = region.generation();
        this.session.runForViewer(
            player,
            () -> this.trackOnViewerThread(
                player,
                regionKey,
                sourceEntityId,
                sourceEntityUuid,
                expectedGeneration
            )
        );
    }

    void untrack(Player player, String regionKey, int sourceEntityId, UUID sourceEntityUuid) {
        if (player == null || regionKey == null || sourceEntityUuid == null) {
            return;
        }
        this.session.runForViewer(
            player,
            () -> this.untrackOnViewerThread(
                player.getUniqueId(),
                regionKey,
                new SourceToken(sourceEntityId, sourceEntityUuid)
            )
        );
    }

    synchronized void discardViewer(UUID playerId) {
        for (TrackingKey key : List.copyOf(this.tracked.keySet())) {
            if (key.viewerId().equals(playerId)) {
                this.removeTracking(key);
            }
        }
    }

    synchronized void removeRegion(String regionKey) {
        if (regionKey == null) {
            return;
        }
        this.regions.remove(regionKey);
        DisplayInteractionRayRegistry.clearPublicJoinSource(this.session.id(), regionKey);
        for (TrackingKey key : List.copyOf(this.tracked.keySet())) {
            if (regionKey.equals(key.regionKey())) {
                this.removeTracking(key);
            }
        }
    }

    synchronized void clear() {
        for (TrackingKey key : List.copyOf(this.tracked.keySet())) {
            this.removeTracking(key);
        }
        for (String regionKey : List.copyOf(this.regions.keySet())) {
            DisplayInteractionRayRegistry.clearPublicJoinSource(this.session.id(), regionKey);
        }
        this.regions.clear();
    }

    synchronized void shutdown() {
        this.tracked.clear();
        this.regions.clear();
        this.proxies.shutdown();
    }

    synchronized int entityCount() {
        return this.proxies.entityCount();
    }

    private synchronized void trackOnViewerThread(
        Player player,
        String regionKey,
        int sourceEntityId,
        UUID sourceEntityUuid,
        long expectedGeneration
    ) {
        RegionState region = this.regions.get(regionKey);
        SourceToken sourceToken = new SourceToken(sourceEntityId, sourceEntityUuid);
        DisplayInteractionRayRegistry.PublicJoinSource source =
            DisplayInteractionRayRegistry.publicJoinSource(sourceEntityId);
        if (!player.isOnline()
            || region == null
            || region.generation() != expectedGeneration
            || !region.sources().contains(sourceToken)
            || source == null
            || !sourceEntityUuid.equals(source.entityUuid())
            || !this.session.id().equals(source.tableId())
            || !regionKey.equals(source.regionKey())
            || this.session.seatOf(player.getUniqueId()) != null
            || this.session.isSpectator(player.getUniqueId())) {
            return;
        }
        TrackingKey key = new TrackingKey(regionKey, player.getUniqueId());
        TrackingState state = this.tracked.get(key);
        if (state == null || state.player() != player) {
            if (state != null) {
                this.removeTracking(key);
            }
            state = new TrackingState(player, new LinkedHashSet<>());
            this.tracked.put(key, state);
        }
        boolean firstSource = state.sources().isEmpty();
        state.sources().add(sourceToken);
        if (firstSource) {
            this.proxies.replace(
                proxyRegionKey(key),
                Map.of(player.getUniqueId(), region.interactions())
            );
        }
    }

    private synchronized void untrackOnViewerThread(
        UUID playerId,
        String regionKey,
        SourceToken source
    ) {
        TrackingKey key = new TrackingKey(regionKey, playerId);
        TrackingState state = this.tracked.get(key);
        if (state == null || !state.sources().remove(source)) {
            return;
        }
        if (state.sources().isEmpty()) {
            this.removeTracking(key);
        }
    }

    private void scheduleProxyReplace(TrackingKey key, TrackingState state, RegionState region) {
        this.session.runForViewer(state.player(), () -> {
            synchronized (this) {
                if (this.tracked.get(key) != state || this.regions.get(key.regionKey()) != region) {
                    return;
                }
                UUID viewerId = key.viewerId();
                if (!state.player().isOnline()
                    || this.session.seatOf(viewerId) != null
                    || this.session.isSpectator(viewerId)) {
                    this.removeTracking(key);
                    return;
                }
                this.proxies.replace(
                    proxyRegionKey(key),
                    Map.of(viewerId, region.interactions())
                );
            }
        });
    }

    private void removeTracking(TrackingKey key) {
        this.tracked.remove(key);
        this.proxies.remove(proxyRegionKey(key));
    }

    private static int bindingIndexForSource(
        List<SeatRenderer.PublicJoinBinding> bindings,
        List<Entity> entities,
        SourceToken source
    ) {
        for (SeatRenderer.PublicJoinBinding binding : bindings) {
            int index = binding.specIndex();
            if (index < entities.size()) {
                Entity entity = entities.get(index);
                if (entity.getEntityId() == source.entityId()
                    && source.entityUuid().equals(entity.getUniqueId())) {
                    return index;
                }
            }
        }
        throw new IllegalStateException("Public JOIN source entity is no longer bound");
    }

    private static String proxyRegionKey(TrackingKey key) {
        return PROXY_REGION_PREFIX + key.regionKey() + ':' + key.viewerId();
    }

    private record RegionState(
        long generation,
        List<DisplayInteractionRayRegistry.RayInteraction> interactions,
        Set<SourceToken> sources
    ) {
    }

    private record SourceToken(int entityId, UUID entityUuid) {
    }

    private record TrackingKey(String regionKey, UUID viewerId) {
    }

    private record TrackingState(Player player, Set<SourceToken> sources) {
    }
}
