package top.ellan.mahjong.presentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable deterministic scene differ. */
public final class SceneGraphDiffer {
    public SceneDiff diff(SceneGraph previous, SceneGraph next) {
        if (!previous.tableId().equals(next.tableId())) {
            throw new IllegalArgumentException("Cannot diff different tables");
        }
        if (next.revision() < previous.revision()) {
            throw new IllegalArgumentException("Scene revision moved backwards");
        }
        List<SceneNodeId> removals = new ArrayList<>();
        for (SceneNodeId id : previous.nodes().keySet()) {
            if (!next.nodes().containsKey(id)) {
                removals.add(id);
            }
        }
        removals.sort(Comparator.naturalOrder());
        List<SceneNode> upserts = new ArrayList<>();
        for (SceneNode node : next.nodes().values()) {
            if (!node.equals(previous.nodes().get(node.id()))) {
                upserts.add(node);
            }
        }
        upserts.sort(Comparator.comparing(SceneNode::id));
        return new SceneDiff(
                next.tableId(),
                previous.revision(),
                next.revision(),
                removals,
                upserts,
                next.interactionBindings());
    }
}
