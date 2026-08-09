package top.ellan.mahjong.presentation.node;

import java.util.Objects;

/** Client-only dynamic text or settlement layer. */
public record HudNode(
        SceneNodeId id, SceneVisibility visibility, String contentKey, String contentValue)
        implements SceneNode {
    public HudNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        contentKey = Objects.requireNonNull(contentKey, "contentKey");
        contentValue = Objects.requireNonNull(contentValue, "contentValue");
        if (visibility.isPublic()) {
            throw new IllegalArgumentException("HUD nodes must be projected per viewer");
        }
    }

    @Override
    public boolean worldBacked() {
        return false;
    }
}
