package top.ellan.mahjong.presentation.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.ActionFurnitureNode;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.PrivateFurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.spi.PlayerId;

/** Complete immutable desired scene for one table revision. */
public final class SceneGraph {
    private final TableId tableId;
    private final long revision;
    private final Map<SceneNodeId, SceneNode> nodes;
    private final List<SceneInteractionBinding> interactionBindings;

    /** Defensive public boundary for caller-owned collections. */
    public SceneGraph(
            TableId tableId,
            long revision,
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> interactionBindings) {
        this(tableId, revision, nodes, interactionBindings, false);
    }

    private SceneGraph(
            TableId tableId,
            long revision,
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> interactionBindings,
            boolean takeOwnership) {
        this.tableId = Objects.requireNonNull(tableId, "tableId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        this.revision = revision;
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(interactionBindings, "interactionBindings");

        HashSet<InteractionHandle> handles = HashSet.newHashSet(interactionBindings.size());
        for (Map.Entry<SceneNodeId, SceneNode> entry : nodes.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalArgumentException("Scene node key differs from its id");
            }
            SceneNode node = entry.getValue();
            if (node.worldBacked()
                    && !node.visibility().isPublic()
                    && !(node instanceof PrivateFurnitureNode)
                    && !(node instanceof ActionFurnitureNode)) {
                throw new IllegalArgumentException(
                        "Only CE conditional furniture may carry private world presentation");
            }
            if (node instanceof InteractionNode interaction) {
                handles.add(interaction.handle());
            }
        }
        HashSet<BindingKey> bindingKeys = HashSet.newHashSet(interactionBindings.size());
        for (SceneInteractionBinding binding : interactionBindings) {
            if (!handles.contains(binding.handle())
                    || binding.revision() != revision
                    || !bindingKeys.add(new BindingKey(binding.handle(), binding.playerId()))) {
                throw new IllegalArgumentException("Invalid or duplicate scene interaction binding");
            }
        }

        this.nodes = takeOwnership
                ? Collections.unmodifiableMap(nodes)
                : Map.copyOf(nodes);
        this.interactionBindings = takeOwnership
                ? Collections.unmodifiableList(interactionBindings)
                : List.copyOf(interactionBindings);
    }

    /**
     * Publishes containers built solely for this graph without duplicating their backing storage.
     * The supplied containers must never be mutated after this call.
     */
    public static SceneGraph takeOwnership(
            TableId tableId,
            long revision,
            HashMap<SceneNodeId, SceneNode> nodes,
            ArrayList<SceneInteractionBinding> interactionBindings) {
        return new SceneGraph(tableId, revision, nodes, interactionBindings, true);
    }

    public static SceneGraph empty(TableId tableId, long revision) {
        return new SceneGraph(tableId, revision, Map.of(), List.of());
    }

    public TableId tableId() {
        return tableId;
    }

    public long revision() {
        return revision;
    }

    public Map<SceneNodeId, SceneNode> nodes() {
        return nodes;
    }

    public List<SceneInteractionBinding> interactionBindings() {
        return interactionBindings;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SceneGraph that
                && tableId.equals(that.tableId)
                && revision == that.revision
                && nodes.equals(that.nodes)
                && interactionBindings.equals(that.interactionBindings);
    }

    @Override
    public int hashCode() {
        int result = tableId.hashCode();
        result = 31 * result + Long.hashCode(revision);
        result = 31 * result + nodes.hashCode();
        result = 31 * result + interactionBindings.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "SceneGraph[tableId=" + tableId
                + ", revision=" + revision
                + ", nodes=" + nodes
                + ", interactionBindings=" + interactionBindings
                + ']';
    }

    private record BindingKey(InteractionHandle handle, PlayerId player) {}
}
