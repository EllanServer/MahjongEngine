package top.ellan.mahjong.application.lobby.port;

import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Platform-neutral ingress used by CraftEngine seat hitboxes. */
public interface SeatInteractionPort {
    SeatInteractionAdmission interact(TableId tableId, SeatId seatId, PlayerId playerId);

    void connected(PlayerId playerId);

    void disconnected(PlayerId playerId);
}
