package top.ellan.mahjong.presentation;

import java.util.Objects;
import top.ellan.mahjong.application.InteractionHandle;

/** Public CraftEngine hit-region furniture. Authorization remains per-player in its bindings. */
public record InteractionNode(
        SceneNodeId id,
        SceneVisibility visibility,
        InteractionHandle handle,
        SceneTransform transform) implements SceneNode {
    public InteractionNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(transform, "transform");
        if (!visibility.isPublic()) {
            throw new IllegalArgumentException("World hit regions must be public");
        }
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
