package top.ellan.mahjong.application;

import java.util.Objects;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** Per-player binding behind a shared public hit region. */
public record InteractionRouteBinding(
        InteractionHandle handle, PlayerId playerId, ActionToken actionToken) {
    public InteractionRouteBinding {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actionToken, "actionToken");
        if (!playerId.equals(actionToken.actor())) {
            throw new IllegalArgumentException("Interaction route actor mismatch");
        }
    }
}
