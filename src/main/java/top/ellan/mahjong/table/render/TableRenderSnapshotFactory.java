package top.ellan.mahjong.table.render;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class TableRenderSnapshotFactory {
    public TableRenderSnapshot create(TableRenderSubject session, long version, long cancellationNonce) {
        Location tableCenter = session.center();
        boolean started = session.isStarted();
        List<UUID> onlineViewerIds = session.viewers().stream()
            .map(Player::getUniqueId)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
        Set<UUID> onlineViewerIdSet = new HashSet<>(onlineViewerIds);
        EnumMap<SeatWind, TableSeatRenderSnapshot> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            seats.put(wind, this.captureSeatSnapshot(session, wind, onlineViewerIds, onlineViewerIdSet));
        }
        return new TableRenderSnapshot(
            version,
            cancellationNonce,
            Objects.toString(tableCenter.getWorld() == null ? null : tableCenter.getWorld().getName(), ""),
            tableCenter.getX(),
            tableCenter.getY(),
            tableCenter.getZ(),
            started,
            session.isRoundFinished(),
            session.isRoundStartInProgress(),
            session.remainingWallCount(),
            session.kanCount(),
            session.dicePoints(),
            session.breakDicePoints(),
            session.roundIndex(),
            session.honbaCount(),
            session.dealerSeat(),
            session.currentSeat(),
            session.openDoorSeat(),
            session.waitingDisplaySummary(),
            session.ruleDisplaySummary(),
            session.publicCenterText(),
            session.lastPublicDiscardPlayerIdValue(),
            session.lastPublicDiscardTile(),
            started ? List.copyOf(session.doraIndicators()) : List.of(),
            seats
        );
    }

    public TableSeatRenderSnapshot createPrivateHandSeat(TableRenderSubject session, SeatWind wind) {
        if (wind == null) {
            throw new IllegalArgumentException("wind is required");
        }
        UUID playerId = session.playerAt(wind);
        boolean occupied = playerId != null;
        boolean online = occupied && session.viewers().stream()
            .anyMatch(viewer -> playerId.equals(viewer.getUniqueId()));
        return new TableSeatRenderSnapshot(
            wind,
            playerId,
            occupied ? session.displayName(playerId) : "",
            "",
            0,
            false,
            false,
            false,
            online,
            "",
            occupied ? session.selectedHandTileIndex(playerId) : -1,
            occupied ? session.selectedHandTileIndices(playerId) : List.of(),
            -1,
            session.stickLayoutCount(wind),
            List.of(),
            occupied ? session.hand(playerId) : List.of(),
            List.of(),
            occupied ? session.fuuro(playerId) : List.of(),
            List.of(),
            List.of()
        );
    }

    private TableSeatRenderSnapshot captureSeatSnapshot(
        TableRenderSubject session,
        SeatWind wind,
        List<UUID> onlineViewerIds,
        Set<UUID> onlineViewerIdSet
    ) {
        UUID playerId = session.playerAt(wind);
        boolean occupied = playerId != null;
        boolean online = occupied && onlineViewerIdSet.contains(playerId);
        String viewerMembershipSignature = online
            ? this.viewerMembershipSignature(onlineViewerIds, playerId)
            : "";
        List<UUID> viewerIdsExcluding = online
            ? this.viewerIdsExcluding(onlineViewerIds, playerId)
            : List.of();
        return new TableSeatRenderSnapshot(
            wind,
            playerId,
            session.displayName(playerId),
            session.publicSeatStatus(wind),
            occupied ? session.points(playerId) : 0,
            occupied && session.isRiichi(playerId),
            occupied && session.isReady(playerId),
            occupied && session.isQueuedToLeave(playerId),
            online,
            viewerMembershipSignature,
            occupied ? session.selectedHandTileIndex(playerId) : -1,
            occupied ? session.selectedHandTileIndices(playerId) : List.of(),
            occupied ? session.riichiDiscardIndex(playerId) : -1,
            session.stickLayoutCount(wind),
            viewerIdsExcluding,
            occupied ? session.hand(playerId) : List.of(),
            occupied ? session.discards(playerId) : List.of(),
            occupied ? session.fuuro(playerId) : List.of(),
            occupied ? session.scoringSticks(playerId) : List.of(),
            session.cornerSticks(wind)
        );
    }

    private String viewerMembershipSignature(List<UUID> onlineViewerIds, UUID excludedPlayerId) {
        StringBuilder builder = new StringBuilder(onlineViewerIds.size() * 36);
        for (UUID viewerId : onlineViewerIds) {
            if (!viewerId.equals(excludedPlayerId)) {
                builder.append(viewerId);
            }
        }
        return builder.toString();
    }

    private List<UUID> viewerIdsExcluding(List<UUID> onlineViewerIds, UUID excludedPlayerId) {
        return onlineViewerIds.stream()
            .filter(viewerId -> !viewerId.equals(excludedPlayerId))
            .toList();
    }
}
