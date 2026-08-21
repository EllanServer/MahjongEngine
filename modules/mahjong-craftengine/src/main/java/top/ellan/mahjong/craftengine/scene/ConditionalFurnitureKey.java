package top.ellan.mahjong.craftengine.scene;

import java.util.Objects;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNodeId;

record ConditionalFurnitureKey(TableId tableId, SceneNodeId nodeId) {
    ConditionalFurnitureKey {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
    }
}
