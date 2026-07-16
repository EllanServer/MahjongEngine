package top.ellan.mahjong.render.display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Server-side hit testing for private display controls and clickable tile models.
 *
 * <p>A vanilla {@code Interaction} entity always has a square horizontal footprint: its width is
 * applied to both X and Z. It therefore cannot exactly match either a flat text display or a
 * {@code width != depth} mahjong tile. This registry uses zero-depth planes for text and oriented
 * boxes with the model's real dimensions for tiles, without spawning a hitbox entity.
 */
public final class DisplayInteractionRayRegistry {
    private static final String DEFAULT_REGION = "viewer-actions";
    private static final double INTERSECTION_EPSILON = 1.0E-7D;
    private static final Map<UUID, ViewerInteractions> VIEWER_INTERACTIONS = new ConcurrentHashMap<>();
    private static final Map<PublicRegionKey, List<RayInteraction>> PUBLIC_JOIN_REGIONS = new LinkedHashMap<>();
    private static final Map<Integer, PublicJoinSource> PUBLIC_JOIN_SOURCES = new ConcurrentHashMap<>();
    private static volatile List<RayInteraction> publicJoinInteractions = List.of();

    private DisplayInteractionRayRegistry() {
    }

    public static void replace(UUID viewerId, String tableId, List<RayInteraction> interactions) {
        replaceRegion(viewerId, tableId, DEFAULT_REGION, interactions);
    }

    public static void replaceRegion(
        UUID viewerId,
        String tableId,
        String regionKey,
        List<RayInteraction> interactions
    ) {
        if (viewerId == null || tableId == null || tableId.isBlank() || regionKey == null || regionKey.isBlank()) {
            return;
        }
        VIEWER_INTERACTIONS.compute(viewerId, (ignored, current) -> {
            if (current != null && !tableId.equals(current.tableId())) {
                return interactions == null || interactions.isEmpty()
                    ? current
                    : ViewerInteractions.single(tableId, regionKey, interactions);
            }
            Map<String, List<RayInteraction>> regions = current == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(current.regions());
            if (interactions == null || interactions.isEmpty()) {
                regions.remove(regionKey);
            } else {
                regions.put(regionKey, List.copyOf(interactions));
            }
            return regions.isEmpty() ? null : ViewerInteractions.from(tableId, regions);
        });
    }

    public static void clearRegion(UUID viewerId, String expectedTableId, String regionKey) {
        if (viewerId == null
            || expectedTableId == null
            || expectedTableId.isBlank()
            || regionKey == null
            || regionKey.isBlank()) {
            return;
        }
        VIEWER_INTERACTIONS.computeIfPresent(viewerId, (ignored, current) -> {
            if (!expectedTableId.equals(current.tableId()) || !current.regions().containsKey(regionKey)) {
                return current;
            }
            Map<String, List<RayInteraction>> regions = new LinkedHashMap<>(current.regions());
            regions.remove(regionKey);
            return regions.isEmpty() ? null : ViewerInteractions.from(expectedTableId, regions);
        });
    }

    public static void clearViewer(UUID viewerId) {
        if (viewerId != null) {
            VIEWER_INTERACTIONS.remove(viewerId);
        }
    }

    public static void clearViewer(UUID viewerId, String expectedTableId) {
        if (viewerId == null || expectedTableId == null || expectedTableId.isBlank()) {
            return;
        }
        VIEWER_INTERACTIONS.computeIfPresent(
            viewerId,
            (ignored, current) -> expectedTableId.equals(current.tableId()) ? null : current
        );
    }

    public static void clearTable(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        VIEWER_INTERACTIONS.forEach((viewerId, current) -> {
            if (tableId.equals(current.tableId())) {
                VIEWER_INTERACTIONS.remove(viewerId, current);
            }
        });
        clearPublicJoinTable(tableId);
    }

    public static void clear() {
        VIEWER_INTERACTIONS.clear();
        clearPublicJoinInteractions();
    }

    /**
     * Replaces public join controls for one rendered table region.
     *
     * <p>These controls are geometry only: no entity or packet is created. The strict JOIN filter
     * prevents private ready, decision, and hand controls from becoming visible to non-members.</p>
     */
    public static synchronized void replacePublicJoinRegion(
        String tableId,
        String regionKey,
        List<RayInteraction> interactions
    ) {
        if (tableId == null || tableId.isBlank() || regionKey == null || regionKey.isBlank()) {
            return;
        }
        PublicRegionKey key = new PublicRegionKey(tableId, regionKey);
        List<RayInteraction> publicJoins = interactions == null
            ? List.of()
            : interactions.stream()
                .filter(interaction -> isPublicJoinForTable(interaction, tableId))
                .toList();
        if (publicJoins.isEmpty()) {
            PUBLIC_JOIN_REGIONS.remove(key);
        } else {
            PUBLIC_JOIN_REGIONS.put(key, publicJoins);
        }
        rebuildPublicJoinSnapshot();
    }

    public static synchronized void clearPublicJoinRegion(String tableId, String regionKey) {
        if (tableId == null || tableId.isBlank() || regionKey == null || regionKey.isBlank()) {
            return;
        }
        if (PUBLIC_JOIN_REGIONS.remove(new PublicRegionKey(tableId, regionKey)) != null) {
            rebuildPublicJoinSnapshot();
        }
        PUBLIC_JOIN_SOURCES.entrySet().removeIf(entry -> entry.getValue().matches(tableId, regionKey));
    }

    public static void registerPublicJoinSource(
        String tableId,
        String regionKey,
        int entityId,
        UUID entityUuid
    ) {
        if (tableId == null
            || tableId.isBlank()
            || regionKey == null
            || regionKey.isBlank()
            || entityUuid == null) {
            return;
        }
        PUBLIC_JOIN_SOURCES.put(entityId, new PublicJoinSource(tableId, regionKey, entityUuid));
    }

    public static void clearPublicJoinSource(String tableId, String regionKey) {
        if (tableId == null || regionKey == null) {
            return;
        }
        PUBLIC_JOIN_SOURCES.entrySet().removeIf(entry -> entry.getValue().matches(tableId, regionKey));
    }

    public static PublicJoinSource publicJoinSource(int entityId) {
        return PUBLIC_JOIN_SOURCES.get(entityId);
    }

    public static boolean isPublicJoinSourceCurrent(String tableId, String regionKey) {
        return PUBLIC_JOIN_SOURCES.values().stream().anyMatch(source -> source.matches(tableId, regionKey));
    }

    public static synchronized boolean isPublicJoinRegionCurrent(String tableId, String regionKey) {
        return tableId != null
            && !tableId.isBlank()
            && regionKey != null
            && !regionKey.isBlank()
            && PUBLIC_JOIN_REGIONS.containsKey(new PublicRegionKey(tableId, regionKey));
    }

    private static synchronized void clearPublicJoinTable(String tableId) {
        boolean removed = PUBLIC_JOIN_REGIONS.keySet().removeIf(key -> tableId.equals(key.tableId()));
        if (removed) {
            rebuildPublicJoinSnapshot();
        }
        PUBLIC_JOIN_SOURCES.entrySet().removeIf(entry -> tableId.equals(entry.getValue().tableId()));
    }

    private static synchronized void clearPublicJoinInteractions() {
        PUBLIC_JOIN_REGIONS.clear();
        PUBLIC_JOIN_SOURCES.clear();
        publicJoinInteractions = List.of();
    }

    private static void rebuildPublicJoinSnapshot() {
        List<RayInteraction> flattened = new ArrayList<>();
        PUBLIC_JOIN_REGIONS.values().forEach(flattened::addAll);
        publicJoinInteractions = List.copyOf(flattened);
    }

    private static boolean isPublicJoinForTable(RayInteraction interaction, String tableId) {
        return interaction != null
            && interaction.action().actionType() == DisplayClickAction.ActionType.JOIN_SEAT
            && tableId.equals(interaction.action().tableId());
    }

    public static boolean isCurrent(UUID viewerId, String tableId) {
        if (viewerId == null || tableId == null || tableId.isBlank()) {
            return false;
        }
        ViewerInteractions current = VIEWER_INTERACTIONS.get(viewerId);
        return current != null && tableId.equals(current.tableId());
    }

    public static boolean isRegionCurrent(UUID viewerId, String tableId, String regionKey) {
        if (viewerId == null
            || tableId == null
            || tableId.isBlank()
            || regionKey == null
            || regionKey.isBlank()) {
            return false;
        }
        ViewerInteractions current = VIEWER_INTERACTIONS.get(viewerId);
        return current != null
            && tableId.equals(current.tableId())
            && current.regions().containsKey(regionKey);
    }

    /** Returns an immutable diagnostic snapshot used by registry tests. */
    static List<RayInteraction> snapshot(UUID viewerId) {
        ViewerInteractions interactions = VIEWER_INTERACTIONS.get(viewerId);
        return interactions == null ? List.of() : interactions.interactions();
    }

    /** Returns an immutable diagnostic snapshot used by registry tests. */
    static List<RayInteraction> publicJoinSnapshot() {
        return publicJoinInteractions;
    }

    public static DisplayClickAction resolve(Player player, double maxDistance) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        ViewerInteractions interactions = VIEWER_INTERACTIONS.get(player.getUniqueId());
        List<RayInteraction> viewerInteractions = interactions == null
            ? List.of()
            : interactions.interactions();
        List<RayInteraction> publicInteractions = publicJoinInteractions;
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return null;
        }
        Vector direction = eye.getDirection();
        return resolveRay(
            viewerInteractions,
            publicInteractions,
            world.getUID(),
            eye.getX(),
            eye.getY(),
            eye.getZ(),
            direction.getX(),
            direction.getY(),
            direction.getZ(),
            maxDistance
        );
    }

    static DisplayClickAction resolveRay(
        List<RayInteraction> interactions,
        UUID worldId,
        double originX,
        double originY,
        double originZ,
        double directionX,
        double directionY,
        double directionZ,
        double maxDistance
    ) {
        return resolveRay(
            interactions,
            List.of(),
            worldId,
            originX,
            originY,
            originZ,
            directionX,
            directionY,
            directionZ,
            maxDistance
        );
    }

    private static DisplayClickAction resolveRay(
        List<RayInteraction> primaryInteractions,
        List<RayInteraction> secondaryInteractions,
        UUID worldId,
        double originX,
        double originY,
        double originZ,
        double directionX,
        double directionY,
        double directionZ,
        double maxDistance
    ) {
        boolean primaryEmpty = primaryInteractions == null || primaryInteractions.isEmpty();
        boolean secondaryEmpty = secondaryInteractions == null || secondaryInteractions.isEmpty();
        if ((primaryEmpty && secondaryEmpty) || !Double.isFinite(maxDistance) || maxDistance <= 0.0D) {
            return null;
        }
        double directionLength = Math.sqrt(
            directionX * directionX + directionY * directionY + directionZ * directionZ
        );
        if (!Double.isFinite(directionLength) || directionLength <= INTERSECTION_EPSILON) {
            return null;
        }
        double rayX = directionX / directionLength;
        double rayY = directionY / directionLength;
        double rayZ = directionZ / directionLength;
        double closestDistance = Double.POSITIVE_INFINITY;
        double closestCenterScore = Double.POSITIVE_INFINITY;
        DisplayClickAction closestAction = null;
        for (int sourceIndex = 0; sourceIndex < 2; sourceIndex++) {
            List<RayInteraction> source = sourceIndex == 0 ? primaryInteractions : secondaryInteractions;
            if (source == null) {
                continue;
            }
            for (RayInteraction interaction : source) {
                if (!Objects.equals(worldId, interaction.worldId())) {
                    continue;
                }
                RayHit hit = intersect(
                    interaction,
                    originX,
                    originY,
                    originZ,
                    rayX,
                    rayY,
                    rayZ,
                    maxDistance
                );
                if (hit == null || hit.distance() > closestDistance + INTERSECTION_EPSILON) {
                    continue;
                }
                if (hit.distance() < closestDistance - INTERSECTION_EPSILON
                    || hit.centerScore() < closestCenterScore) {
                    closestDistance = hit.distance();
                    closestCenterScore = hit.centerScore();
                    closestAction = interaction.action();
                }
            }
        }
        return closestAction;
    }

    private static RayHit intersect(
        RayInteraction interaction,
        double originX,
        double originY,
        double originZ,
        double rayX,
        double rayY,
        double rayZ,
        double maxDistance
    ) {
        double normalX = -interaction.acrossZ();
        double normalZ = interaction.acrossX();
        double relativeX = originX - interaction.centerX();
        double relativeY = originY - interaction.centerY();
        double relativeZ = originZ - interaction.centerZ();
        double localOriginAcross = relativeX * interaction.acrossX() + relativeZ * interaction.acrossZ();
        double localOriginDepth = relativeX * normalX + relativeZ * normalZ;
        double localRayAcross = rayX * interaction.acrossX() + rayZ * interaction.acrossZ();
        double localRayDepth = rayX * normalX + rayZ * normalZ;
        if (interaction.depth() <= INTERSECTION_EPSILON) {
            if (Math.abs(localRayDepth) <= INTERSECTION_EPSILON) {
                return null;
            }
            double distance = -localOriginDepth / localRayDepth;
            if (distance <= 0.0D || distance > maxDistance) {
                return null;
            }
            double hitAcross = localOriginAcross + localRayAcross * distance;
            double hitHeight = relativeY + rayY * distance;
            if (Math.abs(hitAcross) > interaction.width() / 2.0D
                || Math.abs(hitHeight) > interaction.height() / 2.0D) {
                return null;
            }
            return new RayHit(
                distance,
                normalizedCenterScore(hitAcross, hitHeight, interaction.width(), interaction.height())
            );
        }

        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        RayRange acrossRange = clipAxis(
            localOriginAcross,
            localRayAcross,
            interaction.width() / 2.0D,
            near,
            far
        );
        if (acrossRange == null) {
            return null;
        }
        near = acrossRange.near();
        far = acrossRange.far();
        RayRange heightRange = clipAxis(
            relativeY,
            rayY,
            interaction.height() / 2.0D,
            near,
            far
        );
        if (heightRange == null) {
            return null;
        }
        near = heightRange.near();
        far = heightRange.far();
        RayRange depthRange = clipAxis(
            localOriginDepth,
            localRayDepth,
            interaction.depth() / 2.0D,
            near,
            far
        );
        if (depthRange == null) {
            return null;
        }
        near = depthRange.near();
        far = depthRange.far();
        double distance = near > INTERSECTION_EPSILON ? near : far;
        if (distance <= 0.0D || distance > maxDistance) {
            return null;
        }
        double hitAcross = localOriginAcross + localRayAcross * distance;
        double hitHeight = relativeY + rayY * distance;
        return new RayHit(
            distance,
            normalizedCenterScore(hitAcross, hitHeight, interaction.width(), interaction.height())
        );
    }

    private static RayRange clipAxis(
        double origin,
        double direction,
        double halfExtent,
        double currentNear,
        double currentFar
    ) {
        if (Math.abs(direction) <= INTERSECTION_EPSILON) {
            return Math.abs(origin) <= halfExtent
                ? new RayRange(currentNear, currentFar)
                : null;
        }
        double first = (-halfExtent - origin) / direction;
        double second = (halfExtent - origin) / direction;
        double axisNear = Math.min(first, second);
        double axisFar = Math.max(first, second);
        double near = Math.max(currentNear, axisNear);
        double far = Math.min(currentFar, axisFar);
        return near <= far ? new RayRange(near, far) : null;
    }

    private static double normalizedCenterScore(
        double across,
        double height,
        double width,
        double totalHeight
    ) {
        double normalizedAcross = across / (width / 2.0D);
        double normalizedHeight = height / (totalHeight / 2.0D);
        return normalizedAcross * normalizedAcross + normalizedHeight * normalizedHeight;
    }

    public record RayInteraction(
        UUID worldId,
        double centerX,
        double centerY,
        double centerZ,
        double acrossX,
        double acrossZ,
        float width,
        float height,
        float depth,
        DisplayClickAction action
    ) {
        public RayInteraction {
            double acrossLength = Math.hypot(acrossX, acrossZ);
            if (!Double.isFinite(acrossLength) || acrossLength <= INTERSECTION_EPSILON) {
                throw new IllegalArgumentException("Ray interaction across axis must be finite and non-zero");
            }
            if (!Double.isFinite(centerX)
                || !Double.isFinite(centerY)
                || !Double.isFinite(centerZ)
                || !Float.isFinite(width)
                || !Float.isFinite(height)
                || !Float.isFinite(depth)
                || width <= 0.0F
                || height <= 0.0F
                || depth < 0.0F) {
                throw new IllegalArgumentException("Ray interaction bounds must be finite and non-negative");
            }
            acrossX /= acrossLength;
            acrossZ /= acrossLength;
            action = Objects.requireNonNull(action, "action");
        }
    }

    private record ViewerInteractions(
        String tableId,
        Map<String, List<RayInteraction>> regions,
        List<RayInteraction> interactions
    ) {
        private static ViewerInteractions single(
            String tableId,
            String regionKey,
            List<RayInteraction> interactions
        ) {
            return from(tableId, Map.of(regionKey, List.copyOf(interactions)));
        }

        private static ViewerInteractions from(
            String tableId,
            Map<String, List<RayInteraction>> sourceRegions
        ) {
            Map<String, List<RayInteraction>> regions = new LinkedHashMap<>();
            List<RayInteraction> flattened = new ArrayList<>();
            sourceRegions.forEach((regionKey, regionInteractions) -> {
                List<RayInteraction> immutableInteractions = List.copyOf(regionInteractions);
                regions.put(regionKey, immutableInteractions);
                flattened.addAll(immutableInteractions);
            });
            return new ViewerInteractions(
                tableId,
                Collections.unmodifiableMap(regions),
                List.copyOf(flattened)
            );
        }
    }

    private record PublicRegionKey(String tableId, String regionKey) {
    }

    public record PublicJoinSource(String tableId, String regionKey, UUID entityUuid) {
        public PublicJoinSource {
            Objects.requireNonNull(entityUuid, "entityUuid");
        }

        private boolean matches(String expectedTableId, String expectedRegionKey) {
            return this.tableId.equals(expectedTableId) && this.regionKey.equals(expectedRegionKey);
        }
    }

    private record RayHit(double distance, double centerScore) {
    }

    private record RayRange(double near, double far) {
    }
}
