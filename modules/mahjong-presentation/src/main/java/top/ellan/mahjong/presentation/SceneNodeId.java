package top.ellan.mahjong.presentation;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable logical identity across scene revisions. */
public record SceneNodeId(String value) implements Comparable<SceneNodeId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9._:/-]{0,159}");

    public SceneNodeId {
        value = Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid scene node id: " + value);
        }
    }

    @Override
    public int compareTo(SceneNodeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
