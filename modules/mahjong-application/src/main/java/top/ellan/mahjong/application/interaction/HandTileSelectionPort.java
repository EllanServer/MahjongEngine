package top.ellan.mahjong.application.interaction;

import java.util.Optional;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Projects one player's ephemeral hand selection without touching rule state or persistence. */
@FunctionalInterface
public interface HandTileSelectionPort {
    HandTileSelectionPort NOOP = (tableId, playerId, selectedTile) -> {};

    void showSelection(
            TableId tableId, PlayerId playerId, Optional<TileInstanceId> selectedTile);
}
