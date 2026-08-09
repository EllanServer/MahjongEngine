package top.ellan.mahjong.presentation;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.application.InteractionHandle;
import top.ellan.mahjong.spi.PlayerId;

/** Complete immutable desired scene for one table revision. */
public record SceneGraph(
        TableId tableId,
        long revision,
        Map<SceneNodeId, SceneNode> nodes,
        List<SceneInteractionBinding> interactionBindings) {
    public SceneGraph {
        Objects.requireNonNull(tableId, "tableId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        Objects.requireNonNull(nodes, "nodes");
        Map<SceneNodeId, SceneNode> copied = new LinkedHashMap<>();
        for (Map.Entry<SceneNodeId, SceneNode> entry : nodes.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalArgumentException("Scene node key differs from its id");
            }
            SceneNode node = entry.getValue();
            if (node.worldBacked() && !node.visibility().isPublic()) {
                throw new IllegalArgumentException("Private information cannot be world-backed");
            }
            copied.put(entry.getKey(), node);
        }
        nodes = Map.copyOf(copied);
        interactionBindings =
                List.copyOf(Objects.requireNonNull(interactionBindings, "interactionBindings"));
        java.util.Set<InteractionHandle> handles = new HashSet<>();
        for (SceneNode node : nodes.values()) {
            if (node instanceof InteractionNode interaction) {
                handles.add(interaction.handle());
            }
        }
        java.util.Set<BindingKey> bindingKeys = new HashSet<>();
        for (SceneInteractionBinding binding : interactionBindings) {
            if (!handles.contains(binding.handle())
                    || binding.revision() != revision
                    || !bindingKeys.add(new BindingKey(binding.handle(), binding.playerId()))) {
                throw new IllegalArgumentException("Invalid or duplicate scene interaction binding");
            }
        }
    }

    private record BindingKey(InteractionHandle handle, PlayerId player) {}

    public static SceneGraph empty(TableId tableId, long revision) {
        return new SceneGraph(tableId, revision, Map.of(), List.of());
    }
}
