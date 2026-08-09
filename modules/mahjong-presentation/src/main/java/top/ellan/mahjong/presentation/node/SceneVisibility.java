package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.spi.PlayerId;

/** Explicit audience. Private nodes are never eligible for world-backed entities. */
public record SceneVisibility(Optional<PlayerId> privateViewer) {
    public SceneVisibility {
        privateViewer = Objects.requireNonNull(privateViewer, "privateViewer");
    }

    public static SceneVisibility publicToAll() {
        return new SceneVisibility(Optional.empty());
    }

    public static SceneVisibility privateTo(PlayerId playerId) {
        return new SceneVisibility(Optional.of(playerId));
    }

    public boolean isPublic() {
        return privateViewer.isEmpty();
    }
}
