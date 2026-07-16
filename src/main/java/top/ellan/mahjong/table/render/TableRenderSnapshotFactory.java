package top.ellan.mahjong.table.render;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class TableRenderSnapshotFactory {
    public TableRenderSnapshot create(TableRenderSubject session, long version, long cancellationNonce) {
        Location tableCenter = session.center();
        boolean started = session.isStarted();
        List<SerializedViewerId> serializedOnlineViewerIds = session.viewers().stream()
            .map(Player::getUniqueId)
            .distinct()
            .map(viewerId -> new SerializedViewerId(viewerId, viewerId.toString()))
            .sorted(Comparator.comparing(SerializedViewerId::serializedId))
            .toList();
        List<UUID> onlineViewerIds = serializedOnlineViewerIds.stream()
            .map(SerializedViewerId::id)
            .toList();
        Set<UUID> onlineViewerIdSet = new HashSet<>(onlineViewerIds);
        SeatWind[] seatWinds = SeatWind.values();
        EnumMap<SeatWind, UUID> seatPlayerIds = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : seatWinds) {
            seatPlayerIds.put(wind, session.playerAt(wind));
        }
        Map<UUID, String> viewerMembershipSignatures = new HashMap<>();
        Map<UUID, List<UUID>> viewerIdsExcluding = new HashMap<>();
        for (UUID playerId : seatPlayerIds.values()) {
            if (viewerMembershipSignatures.containsKey(playerId)
                || playerId != null && !onlineViewerIdSet.contains(playerId)) {
                continue;
            }
            viewerMembershipSignatures.put(
                playerId,
                this.viewerMembershipSignature(serializedOnlineViewerIds, playerId)
            );
            viewerIdsExcluding.put(
                playerId,
                playerId == null ? List.copyOf(onlineViewerIds) : this.viewerIdsExcluding(onlineViewerIds, playerId)
            );
        }
        EnumMap<SeatWind, TableSeatRenderSnapshot> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : seatWinds) {
            seats.put(
                wind,
                this.captureSeatSnapshot(
                    session,
                    wind,
                    seatPlayerIds.get(wind),
                    onlineViewerIdSet,
                    viewerMembershipSignatures,
                    viewerIdsExcluding
                )
            );
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
            session.currentVariant(),
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
        UUID playerId,
        Set<UUID> onlineViewerIdSet,
        Map<UUID, String> viewerMembershipSignatures,
        Map<UUID, List<UUID>> viewerIdsExcluding
    ) {
        boolean occupied = playerId != null;
        return new TableSeatRenderSnapshot(
            wind,
            playerId,
            session.displayName(playerId),
            session.publicSeatStatus(wind),
            occupied ? session.points(playerId) : 0,
            occupied && session.isRiichi(playerId),
            occupied && session.isReady(playerId),
            occupied && session.isQueuedToLeave(playerId),
            occupied && onlineViewerIdSet.contains(playerId),
            viewerMembershipSignatures.getOrDefault(playerId, ""),
            occupied ? session.selectedHandTileIndex(playerId) : -1,
            occupied ? session.selectedHandTileIndices(playerId) : List.of(),
            occupied ? session.riichiDiscardIndex(playerId) : -1,
            session.stickLayoutCount(wind),
            viewerIdsExcluding.getOrDefault(playerId, List.of()),
            occupied ? session.hand(playerId) : List.of(),
            occupied ? session.discards(playerId) : List.of(),
            occupied ? session.fuuro(playerId) : List.of(),
            occupied ? session.scoringSticks(playerId) : List.of(),
            session.cornerSticks(wind)
        );
    }

    private String viewerMembershipSignature(List<SerializedViewerId> onlineViewerIds, UUID excludedPlayerId) {
        StringBuilder builder = new StringBuilder(onlineViewerIds.size() * 36);
        for (SerializedViewerId viewer : onlineViewerIds) {
            if (!viewer.id().equals(excludedPlayerId)) {
                builder.append(viewer.serializedId());
            }
        }
        return builder.toString();
    }

    private List<UUID> viewerIdsExcluding(List<UUID> onlineViewerIds, UUID excludedPlayerId) {
        return onlineViewerIds.stream()
            .filter(viewerId -> !viewerId.equals(excludedPlayerId))
            .toList();
    }

    private record SerializedViewerId(UUID id, String serializedId) {}
}
