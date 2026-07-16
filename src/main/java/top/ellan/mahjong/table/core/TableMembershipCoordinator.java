package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.SeatWind;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class TableMembershipCoordinator {
    private final MahjongTableManager manager;

    TableMembershipCoordinator(MahjongTableManager manager) {
        this.manager = manager;
    }

    MahjongTableSession join(Player player, String tableId) {
        MahjongTableSession session = this.manager.directoryRef().resolveTable(tableId);
        if (session == null) {
            return null;
        }
        if (this.manager.isViewingAnyTableInternal(player.getUniqueId())) {
            return this.manager.sessionForViewer(player.getUniqueId());
        }
        if (!session.addPlayer(player)) {
            return null;
        }
        this.manager.refreshCoordinatorRef().cleanupLoadedTableArtifactsIfNeeded(session);
        this.manager.directoryRef().assignPlayer(player.getUniqueId(), session.id());
        this.manager.pluginRef().debug().log("table", player.getName() + " joined table " + session.id());
        session.render();
        this.syncCraftEngineTrackedEntities(player);
        this.sendReadyPrompt(player);
        return session;
    }

    MahjongTableSession join(Player player, String tableId, SeatWind wind) {
        MahjongTableSession session = this.manager.directoryRef().resolveTable(tableId);
        if (session == null || wind == null) {
            return null;
        }

        UUID playerId = player.getUniqueId();
        MahjongTableSession currentSeat = this.manager.tableFor(playerId);
        if (currentSeat != null) {
            return currentSeat == session && currentSeat.seatOf(playerId) == wind ? currentSeat : null;
        }

        MahjongTableSession currentView = this.manager.sessionForViewer(playerId);
        if (currentView != null && currentView != session) {
            return null;
        }

        boolean replacedBotSeat = false;
        if (!session.addPlayer(player, wind)) {
            if (!session.replaceBotWithPlayer(player, wind)) {
                return null;
            }
            replacedBotSeat = true;
        }
        this.manager.refreshCoordinatorRef().cleanupLoadedTableArtifactsIfNeeded(session);
        this.manager.directoryRef().removeSpectator(playerId);
        session.removeSpectator(playerId);
        this.manager.directoryRef().assignPlayer(playerId, session.id());
        if (replacedBotSeat) {
            this.manager.pluginRef().debug().log("table", player.getName() + " replaced bot at " + wind.name() + " on table " + session.id());
        } else {
            this.manager.pluginRef().debug().log("table", player.getName() + " joined table " + session.id() + " at " + wind.name());
        }
        session.render();
        this.syncCraftEngineTrackedEntities(player);
        this.sendReadyPrompt(player);
        return session;
    }

    MahjongTableSession spectate(Player player, String tableId) {
        MahjongTableSession session = this.manager.directoryRef().resolveTable(tableId);
        if (session == null) {
            return null;
        }
        if (this.manager.isViewingAnyTableInternal(player.getUniqueId())) {
            return this.manager.sessionForViewer(player.getUniqueId());
        }
        if (!session.addSpectator(player)) {
            return null;
        }
        this.manager.refreshCoordinatorRef().cleanupLoadedTableArtifactsIfNeeded(session);
        this.manager.directoryRef().assignSpectator(player.getUniqueId(), session.id());
        this.manager.pluginRef().debug().log("table", player.getName() + " is spectating table " + session.id());
        session.render();
        this.syncCraftEngineTrackedEntities(player);
        return session;
    }

    MahjongTableManager.LeaveResult leave(UUID playerId) {
        this.closeOverheadView(playerId);
        MahjongTableSession session = this.manager.tableFor(playerId);
        if (session == null) {
            MahjongTableSession spectatorSession = this.unspectate(playerId);
            return spectatorSession == null
                ? new MahjongTableManager.LeaveResult(MahjongTableManager.LeaveStatus.NOT_IN_TABLE, null)
                : new MahjongTableManager.LeaveResult(MahjongTableManager.LeaveStatus.UNSPECTATED, spectatorSession);
        }
        if (session.isStarted()) {
            if (session.queueLeaveAfterRound(playerId)) {
                session.setPlayerUnattended(playerId, true);
                return new MahjongTableManager.LeaveResult(MahjongTableManager.LeaveStatus.DEFERRED, session);
            }
            return new MahjongTableManager.LeaveResult(MahjongTableManager.LeaveStatus.BLOCKED, session);
        }
        SeatWind seatWind = session.seatOf(playerId);
        if (!session.removePlayer(playerId)) {
            return new MahjongTableManager.LeaveResult(MahjongTableManager.LeaveStatus.BLOCKED, session);
        }
        this.manager.directoryRef().removePlayer(playerId);
        this.manager.seatCoordinatorRef().movePlayerToSeatExit(playerId, session, seatWind);
        this.manager.pluginRef().debug().log("table", "Player " + playerId + " left table " + session.id());
        this.handleSeatMembershipChanged(session, true);
        return new MahjongTableManager.LeaveResult(MahjongTableManager.LeaveStatus.LEFT, session);
    }

    MahjongTableSession unspectate(UUID playerId) {
        this.closeOverheadView(playerId);
        String tableId = this.manager.directoryRef().removeSpectator(playerId);
        MahjongTableSession session = tableId == null ? null : this.manager.directoryRef().resolveTable(tableId);
        if (session == null) {
            return null;
        }
        session.removeSpectator(playerId);
        this.manager.pluginRef().debug().log("table", "Spectator " + playerId + " left table " + session.id());
        session.render();
        return session;
    }

    void removePlayerWithoutMove(UUID playerId, MahjongTableSession session) {
        if (playerId == null || session == null) {
            return;
        }
        this.closeOverheadView(playerId);
        SeatWind seatWind = session.seatOf(playerId);
        if (seatWind != null) {
            this.manager.seatCoordinatorRef().ejectSeatOccupant(playerId);
            if (!session.removePlayer(playerId)) {
                return;
            }
            this.manager.directoryRef().removePlayer(playerId);
        } else if (session.isSpectator(playerId)) {
            session.removeSpectator(playerId);
            this.manager.directoryRef().removeSpectator(playerId);
        } else {
            return;
        }
        this.manager.pluginRef().debug().log("table", "Player " + playerId + " removed from table " + session.id() + " without movement");
        this.handleSeatMembershipChanged(session, true);
    }

    void removePlayerFromTableWithoutMove(UUID playerId) {
        if (playerId == null) {
            return;
        }
        MahjongTableSession session = this.manager.tableFor(playerId);
        if (session != null) {
            this.removePlayerWithoutMove(playerId, session);
            return;
        }
        MahjongTableSession spectatorSession = this.manager.sessionForViewer(playerId);
        if (spectatorSession != null) {
            this.unspectate(playerId);
        }
    }

    void finalizeDeferredLeaves(MahjongTableSession session, Map<UUID, SeatWind> playerSeats) {
        if (session == null || playerSeats == null || playerSeats.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, SeatWind> entry : playerSeats.entrySet()) {
            UUID playerId = entry.getKey();
            SeatWind wind = entry.getValue();
            this.closeOverheadView(playerId);
            this.manager.directoryRef().removePlayer(playerId);
            this.manager.seatCoordinatorRef().movePlayerToSeatExit(playerId, session, wind);
            this.manager.pluginRef().debug().log("table", "Player " + playerId + " left table " + session.id() + " after round end");
        }
        this.handleSeatMembershipChanged(session, true);
    }

    private void handleSeatMembershipChanged(MahjongTableSession session, boolean renderAfter) {
        if (session.isEmpty()) {
            if (session.isPersistentRoom()) {
                if (renderAfter) {
                    session.render();
                }
                this.manager.pluginRef().debug().log("table", "Kept empty persistent table " + session.id());
            } else {
                Location center = session.center();
                this.manager.directoryRef().removeSpectators(session.spectators());
                session.shutdown();
                this.manager.cleanupTableArtifactsAt(center);
                this.manager.directoryRef().removeTable(session);
                this.manager.pluginRef().debug().log("table", "Removed empty table " + session.id());
            }
        } else if (renderAfter) {
            session.render();
        }
        this.manager.persistTables();
    }

    private void syncCraftEngineTrackedEntities(Player player) {
        if (player == null || this.manager.pluginRef().craftEngine() == null) {
            return;
        }
        this.manager.pluginRef().scheduler().runEntity(player, () -> this.manager.pluginRef().craftEngine().syncTrackedEntitiesFor(player));
    }

    private void sendReadyPrompt(Player player) {
        this.manager.pluginRef().messages().send(player, "command.ready_prompt");
    }

    private void closeOverheadView(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            this.manager.overheadViewCoordinatorRef().discard(playerId);
            return;
        }
        this.manager.overheadViewCoordinatorRef().exit(player, false);
    }
}
