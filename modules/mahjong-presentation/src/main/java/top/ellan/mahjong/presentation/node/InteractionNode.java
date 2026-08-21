package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import top.ellan.mahjong.application.interaction.InteractionHandle;

/** Public CE wake-up proxy plus exact server-side ray target. Authorization stays per binding. */
public record InteractionNode(
        SceneNodeId id,
        SceneVisibility visibility,
        InteractionHandle handle,
        String assetId,
        InteractionBounds bounds,
        SceneTransform transform) implements SceneNode {
    public InteractionNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(handle, "handle");
        assetId = Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(transform, "transform");
        if (transform.pitchDegrees() != 0.0D || transform.rollDegrees() != 0.0D) {
            throw new IllegalArgumentException("Interaction ray targets support yaw rotation only");
        }
        if (!visibility.isPublic()) {
            throw new IllegalArgumentException("World hit regions must be public");
        }
        if (!SceneAssetGrammar.validAsset(assetId)) {
            throw new IllegalArgumentException("Invalid interaction furniture asset");
        }
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
