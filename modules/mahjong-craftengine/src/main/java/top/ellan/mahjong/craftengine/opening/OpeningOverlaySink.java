package top.ellan.mahjong.craftengine.opening;

import java.util.List;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** Small testable boundary over the CE scene backend's non-durable overlay layer. */
@FunctionalInterface
interface OpeningOverlaySink {
    void replace(
            TableId tableId,
            long generation,
            List<SceneNodeId> managedIds,
            List<FurnitureNode> desiredNodes);
}
