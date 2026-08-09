package top.ellan.mahjong.application;

import java.util.Objects;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Per-player binding behind a shared public hit region. */
public record InteractionRouteBinding(
        InteractionHandle handle,
        PlayerId playerId,
        long revision,
        InteractionPurpose purpose,
        ActionToken actionToken,
        TileInstanceId targetTile) {
    public InteractionRouteBinding(
            InteractionHandle handle, PlayerId playerId, ActionToken actionToken) {
        this(
                handle,
                playerId,
                Objects.requireNonNull(actionToken, "actionToken").revision(),
                InteractionPurpose.RULE_ACTION,
                actionToken,
                null);
    }

    public InteractionRouteBinding {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(purpose, "purpose");
        if (revision < 0) {
            throw new IllegalArgumentException("Interaction revision must be non-negative");
        }
        if (purpose == InteractionPurpose.OVERHEAD_VIEW) {
            if (actionToken != null || targetTile != null) {
                throw new IllegalArgumentException("Overhead view routes cannot carry rule data");
            }
        } else if (actionToken == null
                || actionToken.revision() != revision
                || !playerId.equals(actionToken.actor())) {
            throw new IllegalArgumentException("Interaction route actor mismatch");
        }
        if ((purpose == InteractionPurpose.HAND_TILE_ACTION) != (targetTile != null)) {
            throw new IllegalArgumentException("Only hand-tile routes require a target tile");
        }
    }

    public static InteractionRouteBinding handTile(
            InteractionHandle handle,
            PlayerId playerId,
            ActionToken token,
            TileInstanceId targetTile) {
        return new InteractionRouteBinding(
                handle,
                playerId,
                token.revision(),
                InteractionPurpose.HAND_TILE_ACTION,
                token,
                Objects.requireNonNull(targetTile, "targetTile"));
    }

    public static InteractionRouteBinding overhead(
            InteractionHandle handle, PlayerId playerId, long revision) {
        return new InteractionRouteBinding(
                handle,
                playerId,
                revision,
                InteractionPurpose.OVERHEAD_VIEW,
                null,
                null);
    }
}
