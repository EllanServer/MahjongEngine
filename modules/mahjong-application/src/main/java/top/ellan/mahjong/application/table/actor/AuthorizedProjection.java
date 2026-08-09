package top.ellan.mahjong.application.table.actor;

import java.util.Map;
import java.util.UUID;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.spi.AuthorizedAction;

/** Projection plus the actor-private token catalog that authorizes it. */
record AuthorizedProjection(
        TableProjection projection,
        Map<UUID, AuthorizedAction> actionCatalog) {
    AuthorizedProjection {
        actionCatalog = Map.copyOf(actionCatalog);
    }
}
