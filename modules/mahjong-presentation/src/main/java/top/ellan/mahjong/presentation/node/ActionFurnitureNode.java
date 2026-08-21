package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.regex.Pattern;

/** Viewer-conditional CE text furniture for an action label with static resource text. */
public record ActionFurnitureNode(
        SceneNodeId id,
        SceneVisibility visibility,
        String labelKey,
        SceneTransform transform,
        boolean emphasized)
        implements SceneNode {
    private static final Pattern STATIC_LABEL = Pattern.compile("action\\.[a-z0-9_]{1,89}");

    public ActionFurnitureNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        labelKey = Objects.requireNonNull(labelKey, "labelKey");
        if (!STATIC_LABEL.matcher(labelKey).matches()) {
            throw new IllegalArgumentException("Action furniture requires one static label key");
        }
        Objects.requireNonNull(transform, "transform");
        if (visibility.singleViewer().isEmpty()) {
            throw new IllegalArgumentException(
                    "Action furniture requires exactly one authorized viewer");
        }
    }

    public String assetId() {
        return "mahjongpaper:action_label_" + labelKey.substring("action.".length());
    }

    public String variant() {
        return emphasized ? "emphasized" : "normal";
    }

    @Override
    public boolean worldBacked() {
        return true;
    }
}
