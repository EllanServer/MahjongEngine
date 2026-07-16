package top.ellan.mahjong.render.display;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DisplayVisibilityRegistry {
    private static final Map<Integer, Set<UUID>> PRIVATE_VIEWERS = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<UUID>> EXCLUDED_VIEWERS = new ConcurrentHashMap<>();
    private static final Set<Integer> HIDDEN_ENTITIES = ConcurrentHashMap.newKeySet();

    private DisplayVisibilityRegistry() {
    }

    public static void registerPrivate(int entityId, Collection<UUID> viewers) {
        if (viewers == null || viewers.isEmpty()) {
            unregister(entityId);
            return;
        }
        PRIVATE_VIEWERS.put(entityId, Set.copyOf(viewers));
        EXCLUDED_VIEWERS.remove(entityId);
        HIDDEN_ENTITIES.remove(entityId);
    }

    public static void registerHidden(int entityId) {
        PRIVATE_VIEWERS.remove(entityId);
        EXCLUDED_VIEWERS.remove(entityId);
        HIDDEN_ENTITIES.add(entityId);
    }

    public static void registerExcluded(int entityId, Collection<UUID> viewers) {
        if (viewers == null || viewers.isEmpty()) {
            unregister(entityId);
            return;
        }
        PRIVATE_VIEWERS.remove(entityId);
        EXCLUDED_VIEWERS.put(entityId, Set.copyOf(viewers));
        HIDDEN_ENTITIES.remove(entityId);
    }

    public static boolean canView(int entityId, UUID viewerId) {
        if (HIDDEN_ENTITIES.contains(entityId)) {
            return false;
        }
        Set<UUID> excludedViewers = EXCLUDED_VIEWERS.get(entityId);
        if (excludedViewers != null) {
            return viewerId == null || !excludedViewers.contains(viewerId);
        }
        Set<UUID> viewers = PRIVATE_VIEWERS.get(entityId);
        return viewers == null || viewers.contains(viewerId);
    }

    public static boolean isHidden(int entityId) {
        return HIDDEN_ENTITIES.contains(entityId);
    }

    public static boolean hasCustomVisibility(int entityId) {
        return HIDDEN_ENTITIES.contains(entityId)
            || PRIVATE_VIEWERS.containsKey(entityId)
            || EXCLUDED_VIEWERS.containsKey(entityId);
    }

    public static Set<UUID> privateViewers(int entityId) {
        return PRIVATE_VIEWERS.get(entityId);
    }

    public static Set<UUID> excludedViewers(int entityId) {
        return EXCLUDED_VIEWERS.get(entityId);
    }

    public static boolean matchesPrivate(int entityId, Collection<UUID> viewers) {
        if (EXCLUDED_VIEWERS.containsKey(entityId) || HIDDEN_ENTITIES.contains(entityId)) {
            return false;
        }
        Set<UUID> current = PRIVATE_VIEWERS.get(entityId);
        if (viewers == null) {
            return current == null;
        }
        if (viewers.isEmpty()) {
            return current != null && current.isEmpty();
        }
        return sameViewerSet(current, viewers);
    }

    public static boolean matchesExcluded(int entityId, Collection<UUID> viewers) {
        if (viewers == null || viewers.isEmpty()) {
            return !EXCLUDED_VIEWERS.containsKey(entityId);
        }
        Set<UUID> current = EXCLUDED_VIEWERS.get(entityId);
        return sameViewerSet(current, viewers);
    }

    public static void unregister(int entityId) {
        PRIVATE_VIEWERS.remove(entityId);
        EXCLUDED_VIEWERS.remove(entityId);
        HIDDEN_ENTITIES.remove(entityId);
        DisplayEntities.forgetAppliedBuiltInSpec(entityId);
    }

    public static void clear() {
        PRIVATE_VIEWERS.clear();
        EXCLUDED_VIEWERS.clear();
        HIDDEN_ENTITIES.clear();
        DisplayEntities.clearAppliedBuiltInSpecs();
    }

    private static boolean sameViewerSet(Set<UUID> current, Collection<UUID> viewers) {
        if (current == null || viewers == null) {
            return current == null && viewers == null;
        }
        if (current.size() == viewers.size()) {
            return current.containsAll(viewers);
        }
        return current.equals(Set.copyOf(viewers));
    }
}
