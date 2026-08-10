package top.ellan.mahjong.presentation.scene;

import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/**
 * Minimal node mutation set plus replacement interaction bindings.
 *
 * <p>The differ already builds private {@link java.util.ArrayList}s, so the package-internal
 * constructor takes ownership of those lists instead of copying them a second time. Callers that
 * keep their own references (tests, transient overlays) use the defensive public constructor.</p>
 */
public final class SceneDiff {
    private final TableId tableId;
    private final long fromRevision;
    private final long toRevision;
    private final List<SceneNodeId> removals;
    private final List<SceneNode> upserts;
    private final List<SceneInteractionBinding> interactionBindings;

    public SceneDiff(
            TableId tableId,
            long fromRevision,
            long toRevision,
            List<SceneNodeId> removals,
            List<SceneNode> upserts,
            List<SceneInteractionBinding> interactionBindings) {
        this(
                tableId,
                fromRevision,
                toRevision,
                List.copyOf(Objects.requireNonNull(removals, "removals")),
                List.copyOf(Objects.requireNonNull(upserts, "upserts")),
                List.copyOf(Objects.requireNonNull(interactionBindings, "interactionBindings")),
                true);
    }

    /**
     * Ownership-transferring constructor used by {@link SceneGraphDiffer}: the supplied lists are
     * stored as-is and must not be mutated after construction.
     */
    SceneDiff(
            TableId tableId,
            long fromRevision,
            long toRevision,
            List<SceneNodeId> removals,
            List<SceneNode> upserts,
            List<SceneInteractionBinding> interactionBindings,
            boolean trusted) {
        this.tableId = Objects.requireNonNull(tableId, "tableId");
        if (fromRevision < -1 || toRevision < 0 || toRevision < fromRevision) {
            throw new IllegalArgumentException("Invalid scene revision range");
        }
        this.fromRevision = fromRevision;
        this.toRevision = toRevision;
        this.removals = Objects.requireNonNull(removals, "removals");
        this.upserts = Objects.requireNonNull(upserts, "upserts");
        this.interactionBindings = Objects.requireNonNull(interactionBindings, "interactionBindings");
    }

    public TableId tableId() {
        return tableId;
    }

    public long fromRevision() {
        return fromRevision;
    }

    public long toRevision() {
        return toRevision;
    }

    public List<SceneNodeId> removals() {
        return removals;
    }

    public List<SceneNode> upserts() {
        return upserts;
    }

    public List<SceneInteractionBinding> interactionBindings() {
        return interactionBindings;
    }

    public int mutationCount() {
        return removals.size() + upserts.size();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SceneDiff that
                && tableId.equals(that.tableId)
                && fromRevision == that.fromRevision
                && toRevision == that.toRevision
                && removals.equals(that.removals)
                && upserts.equals(that.upserts)
                && interactionBindings.equals(that.interactionBindings);
    }

    @Override
    public int hashCode() {
        int result = tableId.hashCode();
        result = 31 * result + Long.hashCode(fromRevision);
        result = 31 * result + Long.hashCode(toRevision);
        result = 31 * result + removals.hashCode();
        result = 31 * result + upserts.hashCode();
        result = 31 * result + interactionBindings.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "SceneDiff[tableId=" + tableId
                + ", fromRevision=" + fromRevision
                + ", toRevision=" + toRevision
                + ", removals=" + removals
                + ", upserts=" + upserts
                + ", interactionBindings=" + interactionBindings
                + ']';
    }
}
