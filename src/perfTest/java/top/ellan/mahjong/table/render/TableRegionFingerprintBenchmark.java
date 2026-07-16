package top.ellan.mahjong.table.render;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.riichi.model.ScoringStick;

/** Measures the complete region map and the per-tile fingerprint workload used by one table. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TableRegionFingerprintBenchmark {
    private static final long CHECKSUM_SEED = 0x6A09E667F3BCC909L;
    private static final List<MahjongTile> HAND = List.of(
        MahjongTile.M1,
        MahjongTile.M2,
        MahjongTile.M3,
        MahjongTile.M4,
        MahjongTile.M5_RED,
        MahjongTile.P2,
        MahjongTile.P3,
        MahjongTile.P4,
        MahjongTile.S6,
        MahjongTile.S7,
        MahjongTile.RED_DRAGON
    );
    private static final List<MahjongTile> DISCARDS = List.of(
        MahjongTile.EAST,
        MahjongTile.SOUTH,
        MahjongTile.WEST,
        MahjongTile.NORTH,
        MahjongTile.WHITE_DRAGON,
        MahjongTile.GREEN_DRAGON,
        MahjongTile.RED_DRAGON,
        MahjongTile.M1,
        MahjongTile.M9,
        MahjongTile.P1,
        MahjongTile.P9,
        MahjongTile.S1,
        MahjongTile.S9,
        MahjongTile.M5_RED,
        MahjongTile.P5_RED,
        MahjongTile.S5_RED,
        MahjongTile.M4,
        MahjongTile.P6
    );
    private static final List<MeldView> MELDS = List.of(new MeldView(
        List.of(MahjongTile.P2, MahjongTile.P2, MahjongTile.P2),
        List.of(false, false, false),
        1,
        90,
        MahjongTile.P2
    ));

    private final TableRegionFingerprintService service = new TableRegionFingerprintService();
    private TableRenderSubject subject;
    private TableRenderSnapshot snapshot;
    private TableRenderLayout.LayoutPlan layout;
    private Map<String, Long> expectedRegions;
    private long expectedTileBatchChecksum;

    @Setup(Level.Trial)
    public void setUpFixture() {
        this.subject = fixture();
        this.snapshot = new TableRenderSnapshotFactory().create(this.subject, 17L, 3L);
        this.layout = TableRenderLayout.precompute(this.snapshot);
        this.expectedRegions = referenceRegionFingerprints(this.subject, this.snapshot);
        this.expectedTileBatchChecksum = this.referenceTileBatchChecksum();
        this.verifyFixtureCoverage();
    }

    /**
     * Re-checks the exact legacy contract outside the timed section before every JMH
     * iteration. The independent oracle deliberately retains Objects.toString field encoding,
     * colon separation and per-character FNV mixing.
     */
    @Setup(Level.Iteration)
    public void verifyFingerprintContract() {
        Map<String, Long> actualRegions = this.service.precomputeRegionFingerprints(this.subject, this.snapshot);
        if (!this.expectedRegions.equals(actualRegions)) {
            throw new IllegalStateException(
                "Region fingerprint contract changed: expected=" + this.expectedRegions + ", actual=" + actualRegions
            );
        }
        long actualTileChecksum = this.tileBatchChecksum();
        if (actualTileChecksum != this.expectedTileBatchChecksum) {
            throw new IllegalStateException(
                "Tile fingerprint contract changed: expected=" + this.expectedTileBatchChecksum
                    + ", actual=" + actualTileChecksum
            );
        }
    }

    @Benchmark
    public Map<String, Long> precomputeStartedTableRegions() {
        return this.service.precomputeRegionFingerprints(this.subject, this.snapshot);
    }

    @Benchmark
    public long fingerprintRenderedTileBatch() {
        return this.tileBatchChecksum();
    }

    private void verifyFixtureCoverage() {
        if (!this.snapshot.started() || this.expectedRegions.size() != 20 || this.layout.wallTiles().size() != 136) {
            throw new IllegalStateException("Region fingerprint fixture no longer covers one complete started table");
        }
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = this.snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan plan = this.layout.seat(wind);
            if (seat.hand().size() != 11
                || plan.privateHandPoints().size() != 11
                || plan.publicHandPoints().size() != 11
                || plan.discardPlacements().size() != 18
                || plan.meldPlacements().size() != 4) {
                throw new IllegalStateException("Incomplete per-tile fixture coverage for " + wind);
            }
        }
    }

    private long tileBatchChecksum() {
        long checksum = CHECKSUM_SEED;
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = this.snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan seatPlan = this.layout.seat(wind);
            for (int tileIndex = 0; tileIndex < seat.hand().size(); tileIndex++) {
                checksum = combine(checksum, this.service.handPrivateTileFingerprint(seat, seatPlan, tileIndex));
                checksum = combine(checksum, this.service.handPublicTileFingerprint(this.snapshot, seat, seatPlan, tileIndex));
            }
            for (int discardIndex = 0; discardIndex < seatPlan.discardPlacements().size(); discardIndex++) {
                checksum = combine(checksum, this.service.discardTileFingerprint(seat, seatPlan, discardIndex));
            }
            for (int meldIndex = 0; meldIndex < seatPlan.meldPlacements().size(); meldIndex++) {
                checksum = combine(checksum, this.service.meldTileFingerprint(seat, seatPlan, meldIndex));
            }
        }
        for (int wallIndex = 0; wallIndex < this.layout.wallTiles().size(); wallIndex++) {
            checksum = combine(checksum, this.service.wallTileFingerprint(this.layout, wallIndex));
        }
        return checksum;
    }

    private long referenceTileBatchChecksum() {
        long checksum = CHECKSUM_SEED;
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = this.snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan seatPlan = this.layout.seat(wind);
            for (int tileIndex = 0; tileIndex < seat.hand().size(); tileIndex++) {
                TableRenderLayout.Point point = seatPlan.privateHandPoints().get(tileIndex);
                checksum = combine(checksum, referenceFingerprint(
                    "hand-private-tile",
                    seat.wind().name(),
                    Objects.toString(seat.playerId(), "empty"),
                    tileIndex,
                    seat.online(),
                    seat.hand().size(),
                    seat.selectedHandTileIndices().contains(tileIndex),
                    Double.doubleToLongBits(point.x()),
                    Double.doubleToLongBits(point.y()),
                    Double.doubleToLongBits(point.z()),
                    seat.hand().get(tileIndex).name()
                ));
                point = seatPlan.publicHandPoints().get(tileIndex);
                checksum = combine(checksum, referenceFingerprint(
                    "hand-public-tile",
                    seat.wind().name(),
                    Objects.toString(seat.playerId(), "empty"),
                    tileIndex,
                    this.snapshot.started(),
                    seat.online(),
                    seat.viewerMembershipSignature(),
                    seat.stickLayoutCount(),
                    Double.doubleToLongBits(point.x()),
                    Double.doubleToLongBits(point.y()),
                    Double.doubleToLongBits(point.z()),
                    this.snapshot.started() ? "unknown" : seat.hand().get(tileIndex).name()
                ));
            }
            for (int discardIndex = 0; discardIndex < seatPlan.discardPlacements().size(); discardIndex++) {
                TableRenderLayout.TilePlacement placement = seatPlan.discardPlacements().get(discardIndex);
                checksum = combine(checksum, referenceFingerprint(
                    "discard-tile",
                    seat.wind().name(),
                    Objects.toString(seat.playerId(), "empty"),
                    discardIndex,
                    seat.riichiDiscardIndex(),
                    Float.floatToIntBits(placement.yaw()),
                    Double.doubleToLongBits(placement.point().x()),
                    Double.doubleToLongBits(placement.point().y()),
                    Double.doubleToLongBits(placement.point().z()),
                    placement.tile().name(),
                    placement.pose().name()
                ));
            }
            for (int meldIndex = 0; meldIndex < seatPlan.meldPlacements().size(); meldIndex++) {
                TableRenderLayout.TilePlacement placement = seatPlan.meldPlacements().get(meldIndex);
                checksum = combine(checksum, referenceFingerprint(
                    "meld-tile",
                    seat.wind().name(),
                    Objects.toString(seat.playerId(), "empty"),
                    meldIndex,
                    Float.floatToIntBits(placement.yaw()),
                    Double.doubleToLongBits(placement.point().x()),
                    Double.doubleToLongBits(placement.point().y()),
                    Double.doubleToLongBits(placement.point().z()),
                    placement.tile().name(),
                    placement.pose().name()
                ));
            }
        }
        for (int wallIndex = 0; wallIndex < this.layout.wallTiles().size(); wallIndex++) {
            TableRenderLayout.TilePlacement placement = this.layout.wallTiles().get(wallIndex);
            long fingerprint = placement == null
                ? referenceFingerprint("wall-empty", wallIndex)
                : referenceFingerprint(
                    "wall-tile",
                    wallIndex,
                    Float.floatToIntBits(placement.yaw()),
                    Double.doubleToLongBits(placement.point().x()),
                    Double.doubleToLongBits(placement.point().y()),
                    Double.doubleToLongBits(placement.point().z()),
                    placement.tile().name(),
                    placement.pose().name()
                );
            checksum = combine(checksum, fingerprint);
        }
        return checksum;
    }

    private static Map<String, Long> referenceRegionFingerprints(
        TableRenderSubject subject,
        TableRenderSnapshot snapshot
    ) {
        Map<String, Long> expected = new HashMap<>();
        expected.put("table", referenceFingerprint(
            "table",
            snapshot.worldName(),
            (int) Math.floor(snapshot.centerX()),
            (int) Math.floor(snapshot.centerY()),
            (int) Math.floor(snapshot.centerZ()),
            Objects.toString(subject.settings().craftEngineTableFurnitureId(), "")
        ));
        expected.put("wall", snapshot.started()
            ? referenceFingerprint("wall", snapshot.started(), snapshot.gameFinished(), snapshot.remainingWallCount())
            : referenceFingerprint("wall", "waiting"));

        List<Object> doraFields = new ArrayList<>();
        doraFields.add("dora");
        if (!snapshot.started()) {
            doraFields.add("waiting");
        } else {
            doraFields.add(snapshot.started());
            doraFields.add(snapshot.doraIndicators().size());
            snapshot.doraIndicators().forEach(tile -> doraFields.add(tile.name()));
        }
        expected.put("dora", referenceFingerprint(doraFields.toArray()));
        expected.put("center", referenceFingerprint(
            "center",
            snapshot.started() ? "started" : "waiting",
            snapshot.publicCenterText(),
            snapshot.lastPublicDiscardPlayerId(),
            snapshot.lastPublicDiscardTile()
        ));

        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            expected.put(regionKey("visual", wind), referenceFingerprint(
                "visual",
                "chair-stable-v2",
                wind.name(),
                Objects.toString(subject.settings().craftEngineSeatFurnitureId(), "")
            ));
            expected.put(regionKey("labels", wind), referenceFingerprint(
                "labels",
                "depth-v2",
                seat.wind().name(),
                snapshot.currentSeat().name(),
                snapshot.started(),
                snapshot.roundStartInProgress(),
                Objects.toString(seat.playerId(), "empty"),
                seat.displayName(),
                seat.publicSeatStatus(),
                seat.riichi(),
                seat.ready(),
                seat.queuedToLeave()
            ));

            List<Object> stickFields = new ArrayList<>();
            stickFields.add("sticks");
            stickFields.add(seat.wind().name());
            stickFields.add(Objects.toString(seat.playerId(), "empty"));
            stickFields.add(snapshot.honbaCount());
            stickFields.add(snapshot.dealerSeat().name());
            if (seat.playerId() != null) {
                stickFields.add(seat.riichi());
                seat.scoringSticks().forEach(stick -> stickFields.add(stick.name()));
                seat.cornerSticks().forEach(stick -> stickFields.add(stick.name()));
            }
            expected.put(regionKey("sticks", wind), referenceFingerprint(stickFields.toArray()));

            List<Object> handFields = new ArrayList<>();
            handFields.add("hand-public");
            handFields.add(seat.wind().name());
            handFields.add(Objects.toString(seat.playerId(), "empty"));
            if (seat.playerId() != null) {
                handFields.add(snapshot.started());
                handFields.add(seat.online());
                handFields.add(seat.viewerMembershipSignature());
                handFields.add(seat.stickLayoutCount());
                seat.hand().forEach(tile -> handFields.add(tile.name()));
            }
            expected.put(regionKey("hand-public", wind), referenceFingerprint(handFields.toArray()));
        }
        return Map.copyOf(expected);
    }

    private static TableRenderSubject fixture() {
        PluginSettings settings = PluginSettings.from(new YamlConfiguration());
        List<UUID> playerIds = List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            UUID.fromString("00000000-0000-0000-0000-000000000004")
        );
        List<Player> viewers = playerIds.stream().map(TableRegionFingerprintBenchmark::player).toList();
        EnumMap<SeatWind, UUID> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            seats.put(wind, playerIds.get(wind.index()));
        }

        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "settings" -> settings;
            case "center" -> new Location(null, 12.75D, 64.0D, -7.25D);
            case "viewers" -> viewers;
            case "playerAt" -> seats.get((SeatWind) arguments[0]);
            case "isStarted" -> true;
            case "isRoundFinished", "isRoundStartInProgress", "isQueuedToLeave" -> false;
            case "remainingWallCount" -> 70;
            case "kanCount", "roundIndex" -> 1;
            case "dicePoints" -> 7;
            case "breakDicePoints" -> 5;
            case "honbaCount" -> 2;
            case "dealerSeat" -> SeatWind.EAST;
            case "currentSeat" -> SeatWind.SOUTH;
            case "openDoorSeat" -> SeatWind.WEST;
            case "waitingDisplaySummary" -> "waiting";
            case "ruleDisplaySummary" -> "rules";
            case "publicCenterText" -> "East 2 | 2 honba";
            case "lastPublicDiscardPlayerIdValue" -> playerIds.get(2);
            case "lastPublicDiscardTile" -> MahjongTile.RED_DRAGON;
            case "doraIndicators" -> List.of(MahjongTile.M1, MahjongTile.P1, MahjongTile.S1);
            case "displayName" -> "player-" + playerIds.indexOf(arguments[0]);
            case "publicSeatStatus" -> ((SeatWind) arguments[0]).name() + " 25000";
            case "points" -> 25_000;
            case "isRiichi" -> playerIds.get(0).equals(arguments[0]);
            case "isReady" -> true;
            case "selectedHandTileIndex" -> 10;
            case "selectedHandTileIndices" -> List.of(4, 10);
            case "riichiDiscardIndex" -> playerIds.get(0).equals(arguments[0]) ? 5 : -1;
            case "stickLayoutCount" -> ((SeatWind) arguments[0]).index() + 1;
            case "hand" -> HAND;
            case "discards" -> DISCARDS;
            case "fuuro" -> MELDS;
            case "scoringSticks" -> List.of(ScoringStick.P1000, ScoringStick.P100);
            case "cornerSticks" -> List.of(ScoringStick.P100, ScoringStick.P5000);
            case "toString" -> "RegionFingerprintFixture";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> defaultValue(method);
        };
        return (TableRenderSubject) Proxy.newProxyInstance(
            TableRenderSubject.class.getClassLoader(),
            new Class<?>[] {TableRenderSubject.class},
            handler
        );
    }

    private static Player player(UUID playerId) {
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "isOnline" -> true;
            case "getName" -> "player-" + playerId.getLeastSignificantBits();
            case "toString" -> "PlayerFixture[" + playerId + "]";
            case "hashCode" -> playerId.hashCode();
            case "equals" -> proxy == arguments[0];
            default -> defaultValue(method);
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }

    private static long referenceFingerprint(Object... fields) {
        long hash = 0xcbf29ce484222325L;
        boolean needsSeparator = false;
        for (Object field : fields) {
            if (needsSeparator) {
                hash = mix(hash, ':');
            }
            String text = Objects.toString(field, "");
            for (int index = 0; index < text.length(); index++) {
                hash = mix(hash, text.charAt(index));
            }
            needsSeparator = true;
        }
        return hash;
    }

    private static long mix(long hash, char value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static long combine(long checksum, long fingerprint) {
        return Long.rotateLeft(checksum, 9) ^ fingerprint;
    }

    private static String regionKey(String region, SeatWind wind) {
        return region + ":" + wind.name();
    }
}
