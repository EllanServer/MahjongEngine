package top.ellan.mahjong.craftengine.privateview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;

/** Lock-free indexes for desired and active client-private nodes. */
final class PrivateProjectionState {
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, DesiredNode>> desired =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableNodeKey, Set<PlayerId>> viewersByNode =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, ActiveLabel>> activeLabels =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CameraKey, NodeKey> cameraNodes =
            new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    /**
     * Registers one desired node for every viewer allowed to see it and returns one key per viewer.
     *
     * <p>Shared public HUD nodes carry several viewers, so the per-viewer indexes and the renderer
     * keep working unchanged while the projection only has to emit a single node.</p>
     */
    List<UpsertedNode> upsert(TableId tableId, SceneNode node) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(node, "node");
        Set<PlayerId> viewers = node.visibility().viewers();
        if (viewers.isEmpty()) {
            throw new IllegalArgumentException("Private node has no viewer");
        }
        requireSupported(node);
        Set<PlayerId> audience = Set.copyOf(viewers);
        TableNodeKey tableNode = new TableNodeKey(tableId, node.id());
        Set<PlayerId> previousAudience = viewersByNode.get(tableNode);
        if (previousAudience != null
                && previousAudience.size() == 1
                && audience.size() == 1
                && !previousAudience.equals(audience)) {
            // A single-viewer id encodes its own viewer, so reassigning it means the projection
            // built a colliding id. Stay fail-closed instead of silently moving secret state.
            throw new IllegalArgumentException(
                    "Private scene node is assigned to more than one viewer");
        }
        viewersByNode.put(tableNode, audience);
        if (previousAudience != null) {
            // A shared node that lost viewers must not leave orphaned per-viewer state behind.
            for (PlayerId stale : previousAudience) {
                if (!audience.contains(stale)) {
                    forgetDesired(new NodeKey(tableId, node.id(), stale));
                }
            }
        }
        List<UpsertedNode> upserted = new ArrayList<>(audience.size());
        for (PlayerId viewer : audience) {
            NodeKey key = new NodeKey(tableId, node.id(), viewer);
            long nextGeneration = generation.incrementAndGet();
            DesiredNode previous =
                    desired.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                            .put(key, new DesiredNode(nextGeneration, node));
            removeSpecialIndexes(key, previous == null ? null : previous.node());
            addSpecialIndexes(key, node);
            upserted.add(new UpsertedNode(key, nextGeneration));
        }
        return upserted;
    }

    List<RemovedNode> remove(TableId tableId, SceneNodeId nodeId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
        Set<PlayerId> viewers = viewersByNode.remove(new TableNodeKey(tableId, nodeId));
        if (viewers == null) {
            return List.of();
        }
        List<RemovedNode> removed = new ArrayList<>(viewers.size());
        for (PlayerId viewer : viewers) {
            DesiredNode gone = forgetDesired(new NodeKey(tableId, nodeId, viewer));
            if (gone != null) {
                removed.add(new RemovedNode(new NodeKey(tableId, nodeId, viewer), gone.node()));
            }
        }
        return removed;
    }

    private DesiredNode forgetDesired(NodeKey key) {
        ConcurrentHashMap<NodeKey, DesiredNode> nodes = desired.get(key.viewer());
        if (nodes == null) {
            return null;
        }
        DesiredNode removed = nodes.remove(key);
        if (nodes.isEmpty()) {
            desired.remove(key.viewer(), nodes);
        }
        if (removed != null) {
            removeSpecialIndexes(key, removed.node());
        }
        return removed;
    }

    Map<NodeKey, DesiredNode> desiredNodes(PlayerId viewer) {
        Map<NodeKey, DesiredNode> nodes = desired.get(viewer);
        return nodes == null ? Map.of() : nodes;
    }

    DesiredNode desiredNode(PlayerId viewer, NodeKey key) {
        return desiredNodes(viewer).get(key);
    }

    Optional<CameraNode> cameraNode(TableId tableId, PlayerId viewer) {
        NodeKey key = cameraNodes.get(new CameraKey(tableId, viewer));
        DesiredNode value = key == null ? null : desiredNode(viewer, key);
        return value != null && value.node() instanceof CameraNode camera
                ? Optional.of(camera)
                : Optional.empty();
    }

    ActiveLabel putActiveLabel(NodeKey key, ActiveLabel label) {
        return activeLabels
                .computeIfAbsent(key.viewer(), ignored -> new ConcurrentHashMap<>())
                .put(key, label);
    }

    ActiveLabel activeLabel(NodeKey key) {
        Map<NodeKey, ActiveLabel> labels = activeLabels.get(key.viewer());
        return labels == null ? null : labels.get(key);
    }

    ActiveLabel removeActiveLabel(NodeKey key) {
        ConcurrentHashMap<NodeKey, ActiveLabel> labels = activeLabels.get(key.viewer());
        if (labels == null) {
            return null;
        }
        ActiveLabel removed = labels.remove(key);
        if (labels.isEmpty()) {
            activeLabels.remove(key.viewer(), labels);
        }
        return removed;
    }

    List<ActiveLabel> removeActiveLabels(PlayerId viewer) {
        Map<NodeKey, ActiveLabel> removed = activeLabels.remove(viewer);
        return removed == null ? List.of() : List.copyOf(removed.values());
    }

    void forgetActivity(PlayerId viewer) {
        activeLabels.remove(viewer);
    }

    List<ViewerLabels> activeLabelSnapshot() {
        List<ViewerLabels> snapshot = new ArrayList<>(activeLabels.size());
        activeLabels.forEach(
                (viewer, labels) ->
                        snapshot.add(new ViewerLabels(viewer, List.copyOf(labels.values()))));
        return snapshot;
    }

    void clear() {
        activeLabels.clear();
        cameraNodes.clear();
        viewersByNode.clear();
        desired.clear();
    }

    private static void requireSupported(SceneNode node) {
        if (!(node instanceof HudNode)
                && !(node instanceof CameraNode)
                && !(node instanceof ActionLabelNode)) {
            throw new IllegalArgumentException("Unsupported private node: " + node.getClass());
        }
    }

    private void addSpecialIndexes(NodeKey key, SceneNode node) {
        if (node instanceof CameraNode) {
            cameraNodes.put(new CameraKey(key.tableId(), key.viewer()), key);
        }
    }

    private void removeSpecialIndexes(NodeKey key, SceneNode node) {
        if (node instanceof CameraNode) {
            cameraNodes.remove(new CameraKey(key.tableId(), key.viewer()), key);
        }
    }

    record TableNodeKey(TableId tableId, SceneNodeId nodeId) {}

    record NodeKey(TableId tableId, SceneNodeId nodeId, PlayerId viewer) {}

    record CameraKey(TableId tableId, PlayerId viewer) {}

    record DesiredNode(long generation, SceneNode node) {}

    record ActiveLabel(
            long generation,
            ClientTextDisplay display,
            ActionLabelNode node,
            String content) {}

    record UpsertedNode(NodeKey key, long generation) {}

    record RemovedNode(NodeKey key, SceneNode node) {}

    record ViewerLabels(PlayerId viewer, List<ActiveLabel> labels) {}
}
