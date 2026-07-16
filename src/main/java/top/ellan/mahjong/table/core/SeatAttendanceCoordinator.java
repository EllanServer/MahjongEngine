package top.ellan.mahjong.table.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;

/** Confirms a player stayed away from their seat before delegating actions. */
final class SeatAttendanceCoordinator {
    private static final long ABSENCE_CONFIRM_DELAY_TICKS = 8L;

    private final MahjongTableManager manager;
    private final Map<UUID, Object> pendingChecks = new ConcurrentHashMap<>();

    SeatAttendanceCoordinator(MahjongTableManager manager) {
        this.manager = manager;
    }

    void cancel(UUID playerId) {
        if (playerId != null) {
            this.pendingChecks.remove(playerId);
        }
    }

    void markPresent(MahjongTableSession session, UUID playerId) {
        this.cancel(playerId);
        if (session != null) {
            session.setPlayerUnattended(playerId, false);
        }
    }

    void monitor(Player player, MahjongTableSession session, SeatWind wind) {
        if (player == null || session == null || wind == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Object token = new Object();
        this.pendingChecks.put(playerId, token);
        this.manager.pluginRef().scheduler().runEntityDelayed(
            player,
            () -> this.confirm(player, playerId, session, wind, token),
            ABSENCE_CONFIRM_DELAY_TICKS
        );
    }

    private void confirm(Player player, UUID playerId, MahjongTableSession session, SeatWind wind, Object token) {
        if (!this.pendingChecks.remove(playerId, token)
            || !player.isOnline()
            || this.manager.tableFor(playerId) != session
            || (!session.isStarted() && !session.isRoundStartInProgress())
            || session.seatOf(playerId) != wind) {
            return;
        }
        session.setPlayerUnattended(playerId, !this.isSeatedAt(player, session, wind));
    }

    private boolean isSeatedAt(Player player, MahjongTableSession session, SeatWind wind) {
        if (!player.isInsideVehicle()) {
            return false;
        }
        Entity vehicle = player.getVehicle();
        DisplayClickAction action = this.manager.seatCoordinatorRef().seatAction(vehicle);
        return action != null && action.seatWind() == wind && session.id().equals(action.tableId());
    }
}
