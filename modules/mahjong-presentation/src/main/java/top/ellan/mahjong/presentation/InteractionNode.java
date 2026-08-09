package top.ellan.mahjong.presentation;

import java.util.Objects;
import top.ellan.mahjong.application.InteractionHandle;

/** Public CraftEngine hit-region furniture. Authorization remains per-player in its bindings. */
public record InteractionNode(
        SceneNodeId id,
        SceneVisibility visibility,
        InteractionHandle handle,
        String assetId,
        SceneTransform transform) implements SceneNode {
    public InteractionNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(handle, "handle");
        assetId = Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(transform, "transform");
        if (!visibility.isPublic()) {
            throw new IllegalArgumentException("World hit regions must be public");
        }
        if (!assetId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid interaction furniture asset");
        }
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
