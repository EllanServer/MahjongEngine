package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable logical identity across scene revisions.
 *
 * <p>The public constructor validates against the id grammar and must be used for strings that
 * originate outside the presentation layer (for example rule-pack-provided labels). Hot projection
 * paths that compose ids from already-validated components use {@link #trusted(String)} to skip the
 * per-frame regex.</p>
 */
public final class SceneNodeId implements Comparable<SceneNodeId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9._:/-]{0,159}");

    private final String value;

    public SceneNodeId(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid scene node id: " + value);
        }
    }

    private SceneNodeId(String value, boolean trusted) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * Internal fast path for ids composed from already-validated components. Callers must ensure
     * the supplied string satisfies the {@code [a-z0-9][a-z0-9._:/-]{0,159}} grammar.
     */
    public static SceneNodeId trusted(String value) {
        return new SceneNodeId(value, true);
    }

    public String value() {
        return value;
    }

    @Override
    public int compareTo(SceneNodeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SceneNodeId that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
