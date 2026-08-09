package top.ellan.mahjong.application.interaction;

import java.util.Objects;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Immutable route value published atomically for one player and scene revision. */
record InteractionRoute(
        TableId tableId,
        PlayerId actor,
        long revision,
        InteractionPurpose purpose,
        ActionToken actionToken,
        TileInstanceId targetTile) {
    InteractionRoute {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(purpose, "purpose");
        if (revision < 0) {
            throw new IllegalArgumentException("route revision must be non-negative");
        }
    }
}
