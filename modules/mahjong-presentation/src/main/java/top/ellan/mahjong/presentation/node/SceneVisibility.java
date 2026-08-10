package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.spi.PlayerId;

/**
 * Explicit audience. An empty viewer set means the node is public; any non-empty set restricts the
 * node to those clients and makes it ineligible for world-backed entities.
 *
 * <p>Multiple viewers exist so that identical public information does not have to be duplicated
 * once per seat. A four-player table used to emit four byte-identical HUD nodes for every public
 * attribute, which consumed most of the per-tick mutation budget on its own.</p>
 */
public record SceneVisibility(Set<PlayerId> viewers) {
    private static final SceneVisibility PUBLIC = new SceneVisibility(Set.of());

    public SceneVisibility {
        viewers = Set.copyOf(Objects.requireNonNull(viewers, "viewers"));
    }

    public static SceneVisibility publicToAll() {
        return PUBLIC;
    }

    public static SceneVisibility privateTo(PlayerId playerId) {
        return new SceneVisibility(Set.of(Objects.requireNonNull(playerId, "playerId")));
    }

    /** Restricts one node to a fixed audience; the set must not be empty. */
    public static SceneVisibility privateTo(Set<PlayerId> playerIds) {
        if (Objects.requireNonNull(playerIds, "playerIds").isEmpty()) {
            throw new IllegalArgumentException("A private audience requires at least one viewer");
        }
        return new SceneVisibility(playerIds);
    }

    public boolean isPublic() {
        return viewers.isEmpty();
    }

    /** Present only when exactly one viewer may see the node. */
    public Optional<PlayerId> singleViewer() {
        return viewers.size() == 1 ? Optional.of(viewers.iterator().next()) : Optional.empty();
    }
}
