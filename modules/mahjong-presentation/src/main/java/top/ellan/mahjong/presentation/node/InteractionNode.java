package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.regex.Pattern;
import top.ellan.mahjong.application.interaction.InteractionHandle;

/** Public CraftEngine hit-region furniture. Authorization remains per-player in its bindings. */
public record InteractionNode(
        SceneNodeId id,
        SceneVisibility visibility,
        InteractionHandle handle,
        String assetId,
        SceneTransform transform) implements SceneNode {
    private static final Pattern ASSET_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public InteractionNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(handle, "handle");
        assetId = Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(transform, "transform");
        if (!visibility.isPublic()) {
            throw new IllegalArgumentException("World hit regions must be public");
        }
        if (!ASSET_ID.matcher(assetId).matches()) {
            throw new IllegalArgumentException("Invalid interaction furniture asset");
        }
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
