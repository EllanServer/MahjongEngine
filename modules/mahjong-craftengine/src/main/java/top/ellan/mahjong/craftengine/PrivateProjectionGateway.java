package top.ellan.mahjong.craftengine;

import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.presentation.SceneNode;
import top.ellan.mahjong.presentation.SceneNodeId;

/** Sparrow/Adventure client-only projection boundary for secret items, HUD and cameras. */
public interface PrivateProjectionGateway {
    void upsert(TableId tableId, SceneNode node);

    void remove(TableId tableId, SceneNodeId nodeId);
}
