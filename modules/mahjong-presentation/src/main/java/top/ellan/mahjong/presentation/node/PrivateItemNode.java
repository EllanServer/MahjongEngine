package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

/** Client-only CraftEngine item projection for an authorized tile face. */
public record PrivateItemNode(
        SceneNodeId id,
        SceneVisibility visibility,
        TileInstanceId tileInstanceId,
        TileVisualId visualId,
        SceneTransform transform) implements SceneNode {
    public PrivateItemNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(tileInstanceId, "tileInstanceId");
        Objects.requireNonNull(visualId, "visualId");
        Objects.requireNonNull(transform, "transform");
        if (visibility.isPublic()) {
            throw new IllegalArgumentException("Private item projection requires one viewer");
        }
    }

    @Override
    public boolean worldBacked() {
        return false;
    }
}
