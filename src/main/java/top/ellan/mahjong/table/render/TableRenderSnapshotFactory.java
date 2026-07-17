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
    private static final SeatWind[] SEAT_WINDS = SeatWind.values();

    private ViewerMembershipPlan cachedViewerMembershipPlan;

    public TableRenderSnapshot create(TableRenderSubject session, long version, long cancellationNonce) {
        Location tableCenter = session.center();
        boolean started = session.isStarted();
        ViewerMembershipPlan viewerMembershipPlan = this.resolveViewerMembershipPlan(session);
        EnumMap<SeatWind, TableSeatRenderSnapshot> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SEAT_WINDS) {
            seats.put(
                wind,
                this.captureSeatSnapshot(session, wind, viewerMembershipPlan.seat(wind))
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

    private ViewerMembershipPlan resolveViewerMembershipPlan(TableRenderSubject session) {
        List<Player> viewers = session.viewers();
        ViewerMembershipPlan cached = this.cachedViewerMembershipPlan;
        if (cached != null && cached.matches(session, viewers)) {
            return cached;
        }
        ViewerMembershipPlan captured = this.captureViewerMembershipPlan(session, viewers);
        this.cachedViewerMembershipPlan = captured;
        return captured;
    }

    private ViewerMembershipPlan captureViewerMembershipPlan(TableRenderSubject session, List<Player> viewers) {
        List<UUID> sourceViewerIds = viewers.stream()
            .map(Player::getUniqueId)
            .toList();
        List<SerializedViewerId> serializedOnlineViewerIds = sourceViewerIds.stream()
            .distinct()
            .map(viewerId -> new SerializedViewerId(viewerId, viewerId.toString()))
            .sorted(Comparator.comparing(SerializedViewerId::serializedId))
            .toList();
        List<UUID> onlineViewerIds = serializedOnlineViewerIds.stream()
            .map(SerializedViewerId::id)
            .toList();
        Set<UUID> onlineViewerIdSet = new HashSet<>(onlineViewerIds);
        Map<UUID, SeatViewerMembership> membershipsByPlayerId = new HashMap<>();
        EnumMap<SeatWind, SeatViewerMembership> seatMemberships = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SEAT_WINDS) {
            UUID playerId = session.playerAt(wind);
            SeatViewerMembership membership = membershipsByPlayerId.get(playerId);
            if (membership == null && !membershipsByPlayerId.containsKey(playerId)) {
                membership = this.captureSeatViewerMembership(
                    playerId,
                    serializedOnlineViewerIds,
                    onlineViewerIds,
                    onlineViewerIdSet
                );
                membershipsByPlayerId.put(playerId, membership);
            }
            seatMemberships.put(wind, membership);
        }
        return new ViewerMembershipPlan(session, sourceViewerIds, seatMemberships);
    }

    private SeatViewerMembership captureSeatViewerMembership(
        UUID playerId,
        List<SerializedViewerId> serializedOnlineViewerIds,
        List<UUID> onlineViewerIds,
        Set<UUID> onlineViewerIdSet
    ) {
        if (playerId != null && !onlineViewerIdSet.contains(playerId)) {
            return new SeatViewerMembership(playerId, false, "", List.of());
        }
        return new SeatViewerMembership(
            playerId,
            playerId != null,
            this.viewerMembershipSignature(serializedOnlineViewerIds, playerId),
            playerId == null ? List.copyOf(onlineViewerIds) : this.viewerIdsExcluding(onlineViewerIds, playerId)
        );
    }

    private TableSeatRenderSnapshot captureSeatSnapshot(
        TableRenderSubject session,
        SeatWind wind,
        SeatViewerMembership viewerMembership
    ) {
        UUID playerId = viewerMembership.playerId();
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
            viewerMembership.online(),
            viewerMembership.signature(),
            occupied ? session.selectedHandTileIndex(playerId) : -1,
            occupied ? session.selectedHandTileIndices(playerId) : List.of(),
            occupied ? session.riichiDiscardIndex(playerId) : -1,
            session.stickLayoutCount(wind),
            viewerMembership.viewerIdsExcluding(),
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

    private record ViewerMembershipPlan(
        TableRenderSubject subject,
        List<UUID> sourceViewerIds,
        EnumMap<SeatWind, SeatViewerMembership> seatMemberships
    ) {
        private boolean matches(TableRenderSubject session, List<Player> viewers) {
            if (this.subject != session || this.sourceViewerIds.size() != viewers.size()) {
                return false;
            }
            for (SeatWind wind : SEAT_WINDS) {
                if (!Objects.equals(this.seat(wind).playerId(), session.playerAt(wind))) {
                    return false;
                }
            }
            for (int index = 0; index < this.sourceViewerIds.size(); index++) {
                if (!this.sourceViewerIds.get(index).equals(viewers.get(index).getUniqueId())) {
                    return false;
                }
            }
            return true;
        }

        private SeatViewerMembership seat(SeatWind wind) {
            return this.seatMemberships.get(wind);
        }
    }

    private record SeatViewerMembership(
        UUID playerId,
        boolean online,
        String signature,
        List<UUID> viewerIdsExcluding
    ) {}

    private record SerializedViewerId(UUID id, String serializedId) {}
}
