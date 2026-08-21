package top.ellan.mahjong.craftengine.port;

import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** Minimal client-only boundary for dynamic semantic text, HUD and unsupported camera packets. */
public interface PrivateProjectionGateway {
    void upsert(TableId tableId, SceneNode node);

    void remove(TableId tableId, SceneNodeId nodeId);
}
