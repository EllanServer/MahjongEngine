package top.ellan.mahjong.presentation.projection.support;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;

/** Stable identifiers shared by independent scene contributors. */
public final class SceneNodeIdentity {
    private static final int HASH_CACHE_CAPACITY = 256;
    private static final int INTERACTION_CACHE_CAPACITY = 512;
    private static final int TILE_ID_CACHE_CAPACITY = 1_024;
    private static final int PLAYER_KEY_CACHE_CAPACITY = 512;

    /**
     * Bounded viewer-independent hash cache. The stable id segment depends only on
     * {@code namespace + ':' + key}, so the UUID/MD5 work must not repeat per frame.
     */
    private static final AtomicReferenceArray<HashEntry> HASH_CACHE =
            new AtomicReferenceArray<>(HASH_CACHE_CAPACITY);
    private static final AtomicReferenceArray<InteractionEntry> INTERACTION_CACHE =
            new AtomicReferenceArray<>(INTERACTION_CACHE_CAPACITY);

    /** Bounded lock-free caches keep independent table projection workers from serializing. */
    private static final AtomicReferenceArray<TileIdEntry> TILE_ID_CACHE =
            new AtomicReferenceArray<>(TILE_ID_CACHE_CAPACITY);
    private static final AtomicReferenceArray<PlayerKeyEntry> PLAYER_KEY_CACHE =
            new AtomicReferenceArray<>(PLAYER_KEY_CACHE_CAPACITY);

    private SceneNodeIdentity() {}

    public static InteractionHandle interaction(String material) {
        Objects.requireNonNull(material, "material");
        int slot = slot(material.hashCode(), INTERACTION_CACHE_CAPACITY);
        InteractionEntry cached = INTERACTION_CACHE.get(slot);
        if (cached != null && cached.material().equals(material)) {
            return cached.handle();
        }
        InteractionHandle handle = new InteractionHandle(
                UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)));
        INTERACTION_CACHE.lazySet(slot, new InteractionEntry(material, handle));
        return handle;
    }

    public static SceneNodeId publicTile(long instanceId) {
        return tileId(null, instanceId);
    }

    public static SceneNodeId privateTile(PlayerId viewer, long instanceId) {
        return tileId(compact(viewer), instanceId);
    }

    /** Fast path for a viewer key already compacted once by the enclosing projector. */
    public static SceneNodeId privateTile(String viewerKey, long instanceId) {
        return tileId(Objects.requireNonNull(viewerKey, "viewerKey"), instanceId);
    }

    private static SceneNodeId tileId(String viewerKey, long instanceId) {
        int slot = slot(31 * Long.hashCode(instanceId) + Objects.hashCode(viewerKey),
                TILE_ID_CACHE_CAPACITY);
        TileIdEntry cached = TILE_ID_CACHE.get(slot);
        if (cached != null
                && cached.instanceId() == instanceId
                && Objects.equals(cached.viewerKey(), viewerKey)) {
            return cached.id();
        }
        SceneNodeId id = viewerKey == null
                ? SceneNodeId.trusted("tile/public/" + instanceId)
                : SceneNodeId.trusted("tile/private/" + viewerKey + '/' + instanceId);
        TILE_ID_CACHE.lazySet(slot, new TileIdEntry(viewerKey, instanceId, id));
        return id;
    }

    public static SceneNodeId hud(PlayerId viewer, String namespace, String key) {
        return hud(compact(viewer), namespace, key);
    }

    /**
     * Builds a HUD id from an already-compacted audience key. Shared public HUD nodes pass a fixed
     * key so one node serves every seat instead of one node per viewer.
     */
    public static SceneNodeId hud(String viewerKey, String namespace, String key) {
        String stable = hashSegment(namespace + ':' + key);
        return SceneNodeId.trusted("hud/" + viewerKey + '/' + namespace + '/' + stable);
    }

    private static String hashSegment(String material) {
        int slot = slot(material.hashCode(), HASH_CACHE_CAPACITY);
        HashEntry cached = HASH_CACHE.get(slot);
        if (cached != null && cached.material().equals(material)) {
            return cached.stable();
        }
        String stable = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        HASH_CACHE.lazySet(slot, new HashEntry(material, stable));
        return stable;
    }

    public static String compact(PlayerId player) {
        Objects.requireNonNull(player, "player");
        int slot = slot(player.hashCode(), PLAYER_KEY_CACHE_CAPACITY);
        PlayerKeyEntry cached = PLAYER_KEY_CACHE.get(slot);
        if (cached != null && cached.player().equals(player)) {
            return cached.compact();
        }
        String compact = player.toString().replace("-", "");
        PLAYER_KEY_CACHE.lazySet(slot, new PlayerKeyEntry(player, compact));
        return compact;
    }

    private static int slot(int hash, int capacity) {
        hash ^= hash >>> 16;
        return hash & (capacity - 1);
    }

    private record HashEntry(String material, String stable) {}

    private record InteractionEntry(String material, InteractionHandle handle) {}

    private record TileIdEntry(String viewerKey, long instanceId, SceneNodeId id) {}

    private record PlayerKeyEntry(PlayerId player, String compact) {}
}
