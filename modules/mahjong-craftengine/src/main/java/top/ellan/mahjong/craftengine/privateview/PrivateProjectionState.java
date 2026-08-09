package top.ellan.mahjong.craftengine.privateview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Lock-free indexes for desired and active client-private nodes. */
final class PrivateProjectionState {
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, DesiredNode>> desired =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableNodeKey, PlayerId> viewersByNode =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, ActiveItem>> activeItems =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerId, ConcurrentHashMap<NodeKey, ActiveLabel>> activeLabels =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ViewerTileKey, NodeKey> handTiles =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CameraKey, NodeKey> cameraNodes =
            new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    UpsertedNode upsert(TableId tableId, SceneNode node) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(node, "node");
        PlayerId viewer = node.visibility()
                .privateViewer()
                .orElseThrow(() -> new IllegalArgumentException("Private node has no viewer"));
        requireSupported(node);
        NodeKey key = new NodeKey(tableId, node.id(), viewer);
        viewersByNode.compute(
                new TableNodeKey(tableId, node.id()),
                (ignored, previousViewer) -> {
                    if (previousViewer != null && !previousViewer.equals(viewer)) {
                        throw new IllegalArgumentException(
                                "Private scene node is assigned to more than one viewer");
                    }
                    return viewer;
                });
        long nextGeneration = generation.incrementAndGet();
        DesiredNode previous = desired.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                .put(key, new DesiredNode(nextGeneration, node));
        removeSpecialIndexes(key, previous == null ? null : previous.node());
        addSpecialIndexes(key, node);
        return new UpsertedNode(key, nextGeneration);
    }

    Optional<RemovedNode> remove(TableId tableId, SceneNodeId nodeId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(nodeId, "nodeId");
        PlayerId viewer = viewersByNode.remove(new TableNodeKey(tableId, nodeId));
        if (viewer == null) {
            return Optional.empty();
        }
        ConcurrentHashMap<NodeKey, DesiredNode> nodes = desired.get(viewer);
        if (nodes == null) {
            return Optional.empty();
        }
        NodeKey key = new NodeKey(tableId, nodeId, viewer);
        DesiredNode removed = nodes.remove(key);
        if (removed == null) {
            return Optional.empty();
        }
        if (nodes.isEmpty()) {
            desired.remove(viewer, nodes);
        }
        removeSpecialIndexes(key, removed.node());
        return Optional.of(new RemovedNode(key, removed.node()));
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

    NodeKey handTile(TableId tableId, PlayerId viewer, TileInstanceId tileInstanceId) {
        return handTiles.get(new ViewerTileKey(tableId, viewer, tileInstanceId));
    }

    ActiveItem activeItem(NodeKey key) {
        Map<NodeKey, ActiveItem> items = activeItems.get(key.viewer());
        return items == null ? null : items.get(key);
    }

    ActiveItem putActiveItem(NodeKey key, ActiveItem item) {
        return activeItems
                .computeIfAbsent(key.viewer(), ignored -> new ConcurrentHashMap<>())
                .put(key, item);
    }

    ActiveItem removeActiveItem(NodeKey key) {
        ConcurrentHashMap<NodeKey, ActiveItem> items = activeItems.get(key.viewer());
        if (items == null) {
            return null;
        }
        ActiveItem removed = items.remove(key);
        if (items.isEmpty()) {
            activeItems.remove(key.viewer(), items);
        }
        return removed;
    }

    ActiveLabel putActiveLabel(NodeKey key, ActiveLabel label) {
        return activeLabels
                .computeIfAbsent(key.viewer(), ignored -> new ConcurrentHashMap<>())
                .put(key, label);
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
        activeItems.remove(viewer);
        activeLabels.remove(viewer);
    }

    List<ViewerItems> activeItemSnapshot() {
        List<ViewerItems> snapshot = new ArrayList<>(activeItems.size());
        activeItems.forEach(
                (viewer, items) ->
                        snapshot.add(new ViewerItems(viewer, List.copyOf(items.values()))));
        return snapshot;
    }

    List<ViewerLabels> activeLabelSnapshot() {
        List<ViewerLabels> snapshot = new ArrayList<>(activeLabels.size());
        activeLabels.forEach(
                (viewer, labels) ->
                        snapshot.add(new ViewerLabels(viewer, List.copyOf(labels.values()))));
        return snapshot;
    }

    void clear() {
        activeItems.clear();
        activeLabels.clear();
        handTiles.clear();
        cameraNodes.clear();
        viewersByNode.clear();
        desired.clear();
    }

    private static void requireSupported(SceneNode node) {
        if (!(node instanceof PrivateItemNode)
                && !(node instanceof HudNode)
                && !(node instanceof CameraNode)
                && !(node instanceof ActionLabelNode)) {
            throw new IllegalArgumentException("Unsupported private node: " + node.getClass());
        }
    }

    private void addSpecialIndexes(NodeKey key, SceneNode node) {
        if (node instanceof PrivateItemNode item) {
            handTiles.put(
                    new ViewerTileKey(key.tableId(), key.viewer(), item.tileInstanceId()), key);
        } else if (node instanceof CameraNode) {
            cameraNodes.put(new CameraKey(key.tableId(), key.viewer()), key);
        }
    }

    private void removeSpecialIndexes(NodeKey key, SceneNode node) {
        if (node instanceof PrivateItemNode item) {
            handTiles.remove(
                    new ViewerTileKey(key.tableId(), key.viewer(), item.tileInstanceId()), key);
        }
        if (node instanceof CameraNode) {
            cameraNodes.remove(new CameraKey(key.tableId(), key.viewer()), key);
        }
    }

    record TableNodeKey(TableId tableId, SceneNodeId nodeId) {}

    record NodeKey(TableId tableId, SceneNodeId nodeId, PlayerId viewer) {}

    record ViewerTileKey(TableId tableId, PlayerId viewer, TileInstanceId tileInstanceId) {}

    record CameraKey(TableId tableId, PlayerId viewer) {}

    record DesiredNode(long generation, SceneNode node) {}

    record ActiveItem(long generation, FakeItemDisplay display) {}

    record ActiveLabel(long generation, FakeTextDisplay display) {}

    record UpsertedNode(NodeKey key, long generation) {}

    record RemovedNode(NodeKey key, SceneNode node) {}

    record ViewerItems(PlayerId viewer, List<ActiveItem> items) {}

    record ViewerLabels(PlayerId viewer, List<ActiveLabel> labels) {}
}
