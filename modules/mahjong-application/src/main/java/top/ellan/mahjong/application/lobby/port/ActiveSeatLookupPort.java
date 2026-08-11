package top.ellan.mahjong.application.lobby.port;

import java.util.Optional;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Read-only bridge that lets the shared CraftEngine chairs admit pinned active players. */
@FunctionalInterface
public interface ActiveSeatLookupPort {
    ActiveSeatLookupPort NONE = (tableId, playerId) -> Optional.empty();

    Optional<SeatId> seatOf(TableId tableId, PlayerId playerId);
}
