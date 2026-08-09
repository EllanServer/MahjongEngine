package top.ellan.mahjong.presentation.node;

import java.util.Objects;

/** Public CraftEngine furniture node. */
public record FurnitureNode(
        SceneNodeId id,
        SceneVisibility visibility,
        String assetId,
        SceneTransform transform) implements SceneNode {
    public FurnitureNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        assetId = Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(transform, "transform");
        if (!visibility.isPublic()) {
            throw new IllegalArgumentException("CraftEngine furniture must contain public information only");
        }
        if (!assetId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid CraftEngine asset id: " + assetId);
        }
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
