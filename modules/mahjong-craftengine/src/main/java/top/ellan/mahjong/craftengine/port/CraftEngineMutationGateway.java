package top.ellan.mahjong.craftengine.port;

import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/**
 * Concrete adapter boundary for CraftEngine furniture/culling and private Sparrow packets. Every
 * method is invoked on the owning region thread. Implementations must return immediately when the
 * platform exposes an asynchronous mutation and complete the returned stage only after that
 * mutation has committed.
 */
public interface CraftEngineMutationGateway {
    CompletionStage<Void> upsert(TableId tableId, SceneNode node);

    CompletionStage<Void> remove(TableId tableId, SceneNodeId nodeId);
}
