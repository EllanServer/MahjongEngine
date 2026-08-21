package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

/** CE furniture whose public back and conditional face are selected per viewer. */
public record PrivateFurnitureNode(
        SceneNodeId id,
        SceneVisibility visibility,
        TileInstanceId tileInstanceId,
        TileVisualId visualId,
        SceneTransform transform) implements SceneNode {
    public PrivateFurnitureNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(tileInstanceId, "tileInstanceId");
        Objects.requireNonNull(visualId, "visualId");
        Objects.requireNonNull(transform, "transform");
        if (visibility.singleViewer().isEmpty()) {
            throw new IllegalArgumentException(
                    "A private tile furniture requires exactly one authorized viewer");
        }
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
