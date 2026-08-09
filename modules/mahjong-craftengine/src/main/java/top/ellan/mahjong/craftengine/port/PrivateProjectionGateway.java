package top.ellan.mahjong.craftengine.port;

import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** Sparrow/Adventure client-only projection boundary for secret items, HUD and cameras. */
public interface PrivateProjectionGateway {
    void upsert(TableId tableId, SceneNode node);

    void remove(TableId tableId, SceneNodeId nodeId);
}
