package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.regex.Pattern;

/** Public CraftEngine furniture node. */
public record FurnitureNode(
        SceneNodeId id,
        SceneVisibility visibility,
        String assetId,
        String variant,
        SceneTransform transform) implements SceneNode {
    private static final Pattern ASSET_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern VARIANT = Pattern.compile("[a-z0-9_.-]+");
    public FurnitureNode(
            SceneNodeId id,
            SceneVisibility visibility,
            String assetId,
            SceneTransform transform) {
        this(id, visibility, assetId, "ground", transform);
    }

    public FurnitureNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        assetId = Objects.requireNonNull(assetId, "assetId");
        variant = Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(transform, "transform");
        if (!visibility.isPublic()) {
            throw new IllegalArgumentException("CraftEngine furniture must contain public information only");
        }
        if (!ASSET_ID.matcher(assetId).matches()) {
            throw new IllegalArgumentException("Invalid CraftEngine asset id: " + assetId);
        }
        if (!VARIANT.matcher(variant).matches()) {
            throw new IllegalArgumentException("Invalid CraftEngine furniture variant: " + variant);
        }
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
