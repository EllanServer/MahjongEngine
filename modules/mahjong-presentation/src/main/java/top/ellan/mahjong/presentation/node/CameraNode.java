package top.ellan.mahjong.presentation.node;

import java.util.Objects;

/** Client-only camera instruction. */
public record CameraNode(
        SceneNodeId id, SceneVisibility visibility, SceneTransform transform, boolean active)
        implements SceneNode {
    public CameraNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(transform, "transform");
        if (visibility.isPublic()) {
            throw new IllegalArgumentException("Camera nodes must be projected per viewer");
        }
    }

    @Override
    public boolean worldBacked() {
        return false;
    }
}
