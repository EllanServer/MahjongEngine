package top.ellan.mahjong.presentation;

import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.domain.TableId;

/** Minimal node mutation set plus replacement interaction bindings. */
public record SceneDiff(
        TableId tableId,
        long fromRevision,
        long toRevision,
        List<SceneNodeId> removals,
        List<SceneNode> upserts,
        List<SceneInteractionBinding> interactionBindings) {
    public SceneDiff {
        Objects.requireNonNull(tableId, "tableId");
        if (fromRevision < -1 || toRevision < 0 || toRevision < fromRevision) {
            throw new IllegalArgumentException("Invalid scene revision range");
        }
        removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
        upserts = List.copyOf(Objects.requireNonNull(upserts, "upserts"));
        interactionBindings =
                List.copyOf(Objects.requireNonNull(interactionBindings, "interactionBindings"));
    }

    public int mutationCount() {
        return removals.size() + upserts.size();
    }
}
