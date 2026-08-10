package top.ellan.mahjong.presentation.projection.support;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;

/** Stable identifiers shared by independent scene contributors. */
public final class SceneNodeIdentity {
    private static final int HASH_CACHE_CAPACITY = 256;
    private static final int TILE_ID_CACHE_CAPACITY = 384;

    /**
     * Bounded viewer-independent hash cache. The stable id segment depends only on
     * {@code namespace + ':' + key}, so the UUID/MD5 work must not repeat per frame.
     */
    private static final Map<String, String> HASH_CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > HASH_CACHE_CAPACITY;
        }
    };

    /** Bounded tile node id cache indexed by the already-compacted viewer key. */
    private static final Map<TileIdKey, SceneNodeId> TILE_ID_CACHE =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TileIdKey, SceneNodeId> eldest) {
                    return size() > TILE_ID_CACHE_CAPACITY;
                }
            };

    private SceneNodeIdentity() {}

    public static InteractionHandle interaction(String material) {
        return new InteractionHandle(
                UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)));
    }

    public static SceneNodeId publicTile(long instanceId) {
        return tileId(null, instanceId);
    }

    public static SceneNodeId privateTile(PlayerId viewer, long instanceId) {
        return tileId(compact(viewer), instanceId);
    }

    private static synchronized SceneNodeId tileId(String viewerKey, long instanceId) {
        TileIdKey key = new TileIdKey(viewerKey, instanceId);
        SceneNodeId cached = TILE_ID_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        SceneNodeId id = viewerKey == null
                ? SceneNodeId.trusted("tile/public/" + instanceId)
                : SceneNodeId.trusted("tile/private/" + viewerKey + '/' + instanceId);
        TILE_ID_CACHE.put(key, id);
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
        synchronized (HASH_CACHE) {
            String cached = HASH_CACHE.get(material);
            if (cached != null) {
                return cached;
            }
            String stable = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8))
                    .toString()
                    .replace("-", "");
            HASH_CACHE.put(material, stable);
            return stable;
        }
    }

    public static String compact(PlayerId player) {
        return player.toString().replace("-", "");
    }

    private record TileIdKey(String viewerKey, long instanceId) {}
}
