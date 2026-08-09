package top.ellan.mahjong.craftengine.port;

import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.presentation.SceneNode;
import top.ellan.mahjong.presentation.SceneNodeId;

/**
 * Concrete adapter boundary for CraftEngine furniture/culling and private Sparrow packets. Every
 * method is invoked on the owning region thread.
 */
public interface CraftEngineMutationGateway {
    void upsert(TableId tableId, SceneNode node);

    void remove(TableId tableId, SceneNodeId nodeId);
}
