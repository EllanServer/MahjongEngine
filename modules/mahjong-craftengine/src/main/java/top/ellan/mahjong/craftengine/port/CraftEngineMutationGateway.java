package top.ellan.mahjong.craftengine.port;

import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/**
 * Concrete adapter boundary for CraftEngine furniture/culling and private Sparrow packets. Every
 * method is invoked on the owning region thread.
 */
public interface CraftEngineMutationGateway {
    void upsert(TableId tableId, SceneNode node);

    void remove(TableId tableId, SceneNodeId nodeId);
}
