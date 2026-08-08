package top.ellan.mahjong.presentation;

import java.util.Objects;
import top.ellan.mahjong.application.InteractionHandle;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** Per-viewer authority behind a public interaction node. */
public record SceneInteractionBinding(
        InteractionHandle handle, PlayerId playerId, ActionToken actionToken) {
    public SceneInteractionBinding {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actionToken, "actionToken");
        if (!playerId.equals(actionToken.actor())) {
            throw new IllegalArgumentException("Interaction binding actor mismatch");
        }
    }
}
