package top.ellan.mahjong.application.lobby.projection;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import top.ellan.mahjong.application.TableProjection;
import top.ellan.mahjong.application.lobby.command.LobbyCommand;

/** Projection plus the exact revision-bound command catalog owned by the actor. */
public record LobbyProjectionFrame(
        TableProjection projection, Map<UUID, LobbyCommand> actionCatalog) {
    public LobbyProjectionFrame {
        Objects.requireNonNull(projection, "projection");
        actionCatalog = Map.copyOf(Objects.requireNonNull(actionCatalog, "actionCatalog"));
    }
}
