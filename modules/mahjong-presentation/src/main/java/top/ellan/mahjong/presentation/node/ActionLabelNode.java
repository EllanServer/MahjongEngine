package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.regex.Pattern;

/** Client-only label paired with a CraftEngine interaction hitbox. */
public record ActionLabelNode(
        SceneNodeId id,
        SceneVisibility visibility,
        String labelKey,
        SceneTransform transform,
        boolean emphasized)
        implements SceneNode {
    private static final Pattern VALID_LABEL = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,95}");

    public ActionLabelNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        labelKey = Objects.requireNonNull(labelKey, "labelKey");
        if (!VALID_LABEL.matcher(labelKey).matches()) {
            throw new IllegalArgumentException("Invalid action label key: " + labelKey);
        }
        Objects.requireNonNull(transform, "transform");
        if (visibility.isPublic()) {
            throw new IllegalArgumentException("Action labels must be projected per viewer");
        }
    }

    @Override
    public boolean worldBacked() {
        return false;
    }
}
