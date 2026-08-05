package top.ellan.mahjong.table.render;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TableRegionFingerprintService {
    private static final String REGION_TABLE = "table";
    private static final String REGION_WALL = "wall";
    private static final String REGION_DORA = "dora";
    private static final String REGION_CENTER = "center";

    public Map<String, Long> precomputeRegionFingerprints(TableRenderSubject session, TableRenderSnapshot snapshot) {
        Map<String, Long> fingerprints = new HashMap<>();
        fingerprints.put(REGION_TABLE, this.tableFingerprint(session, snapshot));
        fingerprints.put(REGION_WALL, this.wallFingerprint(snapshot));
        fingerprints.put(REGION_DORA, this.doraFingerprint(snapshot));
        fingerprints.put(REGION_CENTER, this.centerFingerprint(snapshot));
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            fingerprints.put(this.seatRegionKey("visual", wind), this.seatVisualFingerprint(session, wind));
            fingerprints.put(this.seatRegionKey("labels", wind), this.seatLabelFingerprint(session, snapshot, seat));
            fingerprints.put(this.seatRegionKey("sticks", wind), this.stickFingerprint(snapshot, seat));
            fingerprints.put(this.seatRegionKey("hand-public", wind), this.handPublicFingerprint(snapshot, seat));
        }
        return Map.copyOf(fingerprints);
    }

    public long handPrivateTileFingerprint(TableSeatRenderSnapshot seat, TableRenderLayout.SeatLayoutPlan plan, int tileIndex) {
        TableRenderLayout.Point point = plan.privateHandPoints().get(tileIndex);
        return fingerprintBuilder(160)
            .field("hand-private-tile")
            .field(seat.wind().name())
            .field(seat.playerId())
            .field(tileIndex)
            .field(seat.online())
            .field(seat.hand().size())
            .field(seat.selectedHandTileIndices().contains(tileIndex))
            .field(seat.hand().get(tileIndex).name())
            .value();
    }

    public long handPublicTileFingerprint(
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        TableRenderLayout.Point point = plan.publicHandPoints().get(tileIndex);
        return fingerprintBuilder(160)
            .field("hand-public-tile")
            .field(seat.wind().name())
            .field(seat.playerId())
            // tileIndex and hand size are packed into a single injective field: hand size
            // participates in the layout fingerprint, so it must also be part of the content
            // fingerprint, otherwise a hand that grows/shrinks re-arranges every tile while
            // the per-tile content fingerprints stay identical and the short-circuit would
            // wrongly skip the layout update. Packing keeps the field count (and therefore
            // the every-tick fixed cost) identical to the pre-change fingerprint.
            // Injective because tileIndex < 64 and hand size < 64 in every legal game state.
            .field(tileIndex * 64 + seat.hand().size())
            .field(snapshot.started())
            .field(seat.online())
            .field(seat.viewerMembershipSignature())
            .field(seat.stickLayoutCount())
            // Public hand entities are an information boundary: their identity must never depend
            // on a concealed tile, including during the deal/start transition.
            .field("unknown")
            .value();
    }

    public long discardTileFingerprint(TableSeatRenderSnapshot seat, TableRenderLayout.SeatLayoutPlan plan, int discardIndex) {
        TableRenderLayout.TilePlacement placement = plan.discardPlacements().get(discardIndex);
        return fingerprintBuilder(160)
            .field("discard-tile")
            .field(seat.wind().name())
            .field(seat.playerId())
            .field(discardIndex)
            .field(seat.riichiDiscardIndex())
            .field(Float.floatToIntBits(placement.yaw()))
            .field(Double.doubleToLongBits(placement.point().x()))
            .field(Double.doubleToLongBits(placement.point().y()))
            .field(Double.doubleToLongBits(placement.point().z()))
            .field(placement.tile().name())
            .field(placement.pose().name())
            .value();
    }

    long wallTileFingerprint(TableRenderLayout.LayoutPlan plan, int wallIndex) {
        TableRenderLayout.TilePlacement placement = plan.wallTiles().get(wallIndex);
        if (placement == null) {
            return fingerprintBuilder(32)
                .field("wall-empty")
                .field(wallIndex)
                .value();
        }
        return fingerprintBuilder(160)
            .field("wall-tile")
            .field(wallIndex)
            .field(Float.floatToIntBits(placement.yaw()))
            .field(Double.doubleToLongBits(placement.point().x()))
            .field(Double.doubleToLongBits(placement.point().y()))
            .field(Double.doubleToLongBits(placement.point().z()))
            .field(placement.tile().name())
            .field(placement.pose().name())
            .value();
    }

    public long opaqueFingerprint(String value) {
        return fingerprintBuilder(64)
            .field("opaque")
            .field(value)
            .value();
    }

    private long wallFingerprint(TableRenderSnapshot snapshot) {
        if (!snapshot.started()) {
            return fingerprintBuilder(24)
                .field("wall")
                .field("waiting")
                .value();
        }
        return fingerprintBuilder(32)
            .field("wall")
            .field(snapshot.started())
            .field(snapshot.gameFinished())
            .field(snapshot.remainingWallCount())
            .value();
    }

    private long tableFingerprint(TableRenderSubject session, TableRenderSnapshot snapshot) {
        return fingerprintBuilder(48)
            .field("table")
            .field(snapshot.worldName())
            .field((int) Math.floor(snapshot.centerX()))
            .field((int) Math.floor(snapshot.centerY()))
            .field((int) Math.floor(snapshot.centerZ()))
            .field(Objects.toString(session.settings().craftEngineTableFurnitureId(), ""))
            .value();
    }

    private long doraFingerprint(TableRenderSnapshot snapshot) {
        if (!snapshot.started()) {
            return fingerprintBuilder(24)
                .field("dora")
                .field("waiting")
                .value();
        }
        FingerprintBuilder builder = fingerprintBuilder(64)
            .field("dora")
            .field(snapshot.started())
            .field(snapshot.doraIndicators().size());
        snapshot.doraIndicators().forEach(tile -> builder.field(tile.name()));
        return builder.value();
    }

    private long centerFingerprint(TableRenderSnapshot snapshot) {
        return fingerprintBuilder(192)
            .field("center")
            .field(snapshot.started() ? "started" : "waiting")
            .field(snapshot.publicCenterText())
            .field(snapshot.lastPublicDiscardPlayerId())
            .field(snapshot.lastPublicDiscardTile())
            .value();
    }

    private long seatLabelFingerprint(TableRenderSubject session, TableRenderSnapshot snapshot, TableSeatRenderSnapshot seat) {
        return fingerprintBuilder(128)
            .field("labels")
            .field("ray-actions-v3")
            .field(seat.wind().name())
            .field(snapshot.currentSeat().name())
            .field(snapshot.started())
            // Read roundStartInProgress from the snapshot rather than the live session.
            // precomputeRegionFingerprints runs on the async render-precompute thread;
            // although the session field is now volatile (so the live read would be safe),
            // using the snapshot guarantees the fingerprint matches the rest of the
            // snapshot state captured on the region thread, avoiding a torn read where
            // the seat label fingerprint reflects a newer roundStartInProgress value than
            // the seat occupancy snapshots around it.
            .field(snapshot.roundStartInProgress())
            .field(seat.playerId())
            .field(seat.displayName())
            .field(seat.publicSeatStatus())
            .field(seat.riichi())
            .field(seat.ready())
            .field(seat.queuedToLeave())
            .field(seat.viewerMembershipSignature())
            .value();
    }

    private long seatVisualFingerprint(TableRenderSubject session, SeatWind wind) {
        return fingerprintBuilder(96)
            .field("visual")
            .field("chair-stable-v2")
            .field(wind.name())
            .field(Objects.toString(session.settings().craftEngineSeatFurnitureId(), ""))
            .value();
    }

    private long handPublicFingerprint(TableRenderSnapshot snapshot, TableSeatRenderSnapshot seat) {
        FingerprintBuilder builder = fingerprintBuilder(256)
            .field("hand-public")
            .field(seat.wind().name())
            .field(seat.playerId());
        if (seat.playerId() == null) {
            return builder.value();
        }
        builder.field(snapshot.started())
            .field(seat.online())
            .field(seat.viewerMembershipSignature())
            .field(seat.stickLayoutCount());
        seat.hand().forEach(tile -> builder.field(tile.name()));
        return builder.value();
    }

    public long meldTileFingerprint(TableSeatRenderSnapshot seat, TableRenderLayout.SeatLayoutPlan plan, int meldIndex) {
        TableRenderLayout.TilePlacement placement = plan.meldPlacements().get(meldIndex);
        return fingerprintBuilder(160)
            .field("meld-tile")
            .field(seat.wind().name())
            .field(seat.playerId())
            .field(meldIndex)
            .field(Float.floatToIntBits(placement.yaw()))
            .field(Double.doubleToLongBits(placement.point().x()))
            .field(Double.doubleToLongBits(placement.point().y()))
            .field(Double.doubleToLongBits(placement.point().z()))
            .field(placement.tile().name())
            .field(placement.pose().name())
            .value();
    }

    private long stickFingerprint(TableRenderSnapshot snapshot, TableSeatRenderSnapshot seat) {
        FingerprintBuilder builder = fingerprintBuilder(128)
            .field("sticks")
            .field(seat.wind().name())
            .field(seat.playerId())
            .field(snapshot.honbaCount())
            .field(snapshot.dealerSeat().name());
        if (seat.playerId() == null) {
            return builder.value();
        }
        builder.field(seat.riichi());
        seat.scoringSticks().forEach(stick -> builder.field(stick.name()));
        seat.cornerSticks().forEach(stick -> builder.field(stick.name()));
        return builder.value();
    }

    private String seatRegionKey(String region, SeatWind wind) {
        return region + ":" + wind.name();
    }

    /**
     * Layout fingerprint for a seat's private hand tiles.
     *
     * <p>The per-tile content fingerprint intentionally excludes tile positions so a pure layout
     * change (hand size growth/shrink re-arranging every tile) does not poison every tile
     * fingerprint. Tile coordinates are fully determined by (seat, hand size, tile index,
     * selected indices), all of which are already part of the per-tile content fingerprints,
     * so the layout fingerprint only needs to carry the hand structure (size): when content
     * changes but the structure does not, the region can be reconciled in place (teleport)
     * instead of being respawned.
     *
     * <p>This is a deliberately cheap fingerprint (a few FNV fields) because it is computed
     * once per seat per apply; it never needs to enumerate tile coordinates.
     */
    public long privateHandLayoutFingerprint(TableSeatRenderSnapshot seat, TableRenderLayout.SeatLayoutPlan plan) {
        return fingerprintBuilder(48)
            .field("hand-private-layout")
            .field(seat.wind().name())
            .field(seat.playerId())
            .field(seat.hand().size())
            .value();
    }

    /**
     * Layout fingerprint for a seat's public hand tiles.
     *
     * <p>The per-tile content fingerprint intentionally excludes tile positions, so the layout
     * fingerprint carries the remaining structural signal (hand size plus the seat identity,
     * which is already covered by the content fingerprints); the coordinator consults it on
     * the update path to decide reconcile-vs-respawn. It is cheap to compute (a few FNV
     * fields, once per seat per apply).
     */
    public long publicHandLayoutFingerprint(TableRenderSnapshot snapshot, TableSeatRenderSnapshot seat, TableRenderLayout.SeatLayoutPlan plan) {
        return fingerprintBuilder(48)
            .field("hand-public-layout")
            .field(seat.wind().name())
            .field(seat.playerId())
            .field(snapshot.started())
            .field(seat.hand().size())
            .value();
    }

    private static FingerprintBuilder fingerprintBuilder(int capacity) {
        return new FingerprintBuilder();
    }

    private static final class FingerprintBuilder {
        private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
        private static final long FNV_PRIME = 0x100000001b3L;

        private long hash = FNV_OFFSET_BASIS;
        private boolean needsSeparator;

        private FingerprintBuilder field(Object value) {
            this.startField('o');
            String text = Objects.toString(value, "");
            for (int index = 0; index < text.length(); index++) {
                this.mix(text.charAt(index));
            }
            return this;
        }

        private FingerprintBuilder field(java.util.UUID value) {
            if (value == null) {
                return this.field((Object) null);
            }
            this.startField('u');
            this.mixLong(value.getMostSignificantBits());
            this.mixLong(value.getLeastSignificantBits());
            return this;
        }

        private FingerprintBuilder field(boolean value) {
            this.startField('b');
            this.mix(value ? 1 : 0);
            return this;
        }

        private FingerprintBuilder field(int value) {
            this.startField('i');
            this.mix(value);
            this.mix(value >>> 8);
            this.mix(value >>> 16);
            this.mix(value >>> 24);
            return this;
        }

        private FingerprintBuilder field(long value) {
            this.startField('l');
            this.mixLong(value);
            return this;
        }

        private long value() {
            return this.hash;
        }

        private void startField(char type) {
            if (this.needsSeparator) {
                this.mix(':');
            }
            this.mix(type);
            this.needsSeparator = true;
        }

        private void mixLong(long value) {
            this.mix((int) value);
            this.mix((int) (value >>> 8));
            this.mix((int) (value >>> 16));
            this.mix((int) (value >>> 24));
            this.mix((int) (value >>> 32));
            this.mix((int) (value >>> 40));
            this.mix((int) (value >>> 48));
            this.mix((int) (value >>> 56));
        }

        private void mix(int value) {
            this.hash ^= value & 0xffL;
            this.hash *= FNV_PRIME;
        }
    }
}
