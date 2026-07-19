package top.ellan.mahjong.render.display;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ownership index for per-viewer client-only entities used only to wake exact ray hit testing. */
public final class ClientInteractionProxyRegistry {
    private static final Map<Integer, ProxyOwner> OWNERS = new ConcurrentHashMap<>();

    private ClientInteractionProxyRegistry() {
    }

    public static void register(int entityId, UUID viewerId, String tableId) {
        if (entityId < 0 || viewerId == null || tableId == null || tableId.isBlank()) {
            return;
        }
        OWNERS.put(entityId, new ProxyOwner(viewerId, tableId));
    }

    public static String tableIdFor(int entityId, UUID viewerId) {
        if (viewerId == null) {
            return null;
        }
        ProxyOwner owner = OWNERS.get(entityId);
        return owner != null && viewerId.equals(owner.viewerId()) ? owner.tableId() : null;
    }

    public static boolean isOwnedBy(int entityId, UUID viewerId, String tableId) {
        if (viewerId == null || tableId == null) {
            return false;
        }
        ProxyOwner owner = OWNERS.get(entityId);
        return owner != null && owner.matches(viewerId, tableId);
    }

    public static void unregister(int entityId, UUID viewerId, String tableId) {
        if (viewerId == null || tableId == null) {
            return;
        }
        OWNERS.computeIfPresent(
            entityId,
            (ignored, owner) -> viewerId.equals(owner.viewerId()) && tableId.equals(owner.tableId())
                ? null
                : owner
        );
    }

    public static void clearViewer(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        OWNERS.forEach((entityId, owner) -> {
            if (viewerId.equals(owner.viewerId())) {
                OWNERS.remove(entityId, owner);
            }
        });
    }

    public static void clearTable(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        OWNERS.forEach((entityId, owner) -> {
            if (tableId.equals(owner.tableId())) {
                OWNERS.remove(entityId, owner);
            }
        });
    }

    public static void clear() {
        OWNERS.clear();
    }

    private record ProxyOwner(UUID viewerId, String tableId) {
        private boolean matches(UUID expectedViewerId, String expectedTableId) {
            return expectedViewerId.equals(this.viewerId) && expectedTableId.equals(this.tableId);
        }
    }
}
