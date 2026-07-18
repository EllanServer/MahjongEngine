package top.ellan.mahjong.table.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
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
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.render.scene.TableRenderer;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.riichi.model.ScoringStick;
import top.ellan.mahjong.table.core.TableSessionContext;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RegionRenderApplyBenchmark {
    private TableRegionDisplayCoordinator planningCoordinator;
    private TableRegionDisplayCoordinator stableCoordinator;
    private TableRenderPrecomputeResult planningResult;
    private TableRenderPrecomputeResult stableResult;
    private MethodHandle completeFingerprintPrecompute;

    @Setup(Level.Trial)
    public void setUp() throws ReflectiveOperationException {
        TableSessionContext session = session();
        TableRenderSnapshot representativeSnapshot = snapshot(true);
        TableRenderLayout.LayoutPlan representativeLayout = TableRenderLayout.precompute(representativeSnapshot);
        this.completeFingerprintPrecompute = completeFingerprintHandle(
            session, representativeSnapshot, representativeLayout
        );
        this.planningResult = new TableRenderPrecomputeResult(
            representativeSnapshot,
            fingerprints(session, representativeSnapshot, representativeLayout),
            representativeLayout
        );
        this.planningCoordinator = new TableRegionDisplayCoordinator(session, new TableRegionFingerprintService(), 0, 0);

        TableRenderSnapshot stableSnapshot = snapshot(false);
        TableRenderLayout.LayoutPlan stableLayout = TableRenderLayout.precompute(stableSnapshot);
        Map<String, Long> fingerprints = fingerprints(session, stableSnapshot, stableLayout);
        this.stableResult = new TableRenderPrecomputeResult(stableSnapshot, fingerprints, stableLayout);
        this.stableCoordinator = new TableRegionDisplayCoordinator(session, new TableRegionFingerprintService());
        seedStableState(this.stableCoordinator, fingerprints);
        int planned = plannedRegionCount(representativeSnapshot, representativeLayout);
        if (planned != 327
            || !this.planningCoordinator.applyRenderPrecompute(this.planningResult)
            || this.stableCoordinator.applyRenderPrecompute(this.stableResult)) {
            throw new IllegalStateException("Region benchmark contract changed: planned=" + planned);
        }
    }

    @Benchmark
    public boolean planAndDeferRepresentativeTableRegions() {
        return this.planningCoordinator.applyRenderPrecompute(this.planningResult);
    }

    @Benchmark
    public boolean applyStableStartedTable() {
        return this.stableCoordinator.applyRenderPrecompute(this.stableResult);
    }

    @Benchmark
    @SuppressWarnings("unchecked")
    public Map<String, Long> precomputeCompleteStartedTableRegions() throws Throwable {
        return (Map<String, Long>) this.completeFingerprintPrecompute.invokeExact();
    }

    private static MethodHandle completeFingerprintHandle(
        TableSessionContext session,
        TableRenderSnapshot snapshot,
        TableRenderLayout.LayoutPlan layout
    ) throws ReflectiveOperationException {
        TableRegionFingerprintService service = new TableRegionFingerprintService();
        try {
            Method method = TableRegionFingerprintService.class.getDeclaredMethod(
                "precomputeRegionFingerprints",
                TableRenderSubject.class,
                TableRenderSnapshot.class,
                TableRenderLayout.LayoutPlan.class
            );
            return MethodHandles.lookup()
                .unreflect(method)
                .bindTo(service)
                .bindTo(session)
                .bindTo(snapshot)
                .bindTo(layout)
                .asType(MethodType.methodType(Map.class));
        } catch (NoSuchMethodException ignored) {
            try {
                return MethodHandles.lookup()
                    .findStatic(
                        RegionRenderApplyBenchmark.class,
                        "fingerprints",
                        MethodType.methodType(
                            Map.class,
                            TableSessionContext.class,
                            TableRenderSnapshot.class,
                            TableRenderLayout.LayoutPlan.class
                        )
                    )
                    .bindTo(session)
                    .bindTo(snapshot)
                    .bindTo(layout);
            } catch (NoSuchMethodException | IllegalAccessException exception) {
                throw new ReflectiveOperationException(exception);
            }
        } catch (IllegalAccessException exception) {
            throw new ReflectiveOperationException(exception);
        }
    }

    private static Map<String, Long> fingerprints(
        TableSessionContext session,
        TableRenderSnapshot snapshot,
        TableRenderLayout.LayoutPlan layout
    ) {
        TableRegionFingerprintService service = new TableRegionFingerprintService();
        Map<String, Long> values = new HashMap<>(service.precomputeRegionFingerprints(session, snapshot));
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan seatPlan = layout.seat(wind);
            for (int index = 0; index < seat.hand().size(); index++) {
                values.put(
                    handPublicRegionKey(wind, index),
                    service.handPublicTileFingerprint(snapshot, seat, seatPlan, index)
                );
                values.put(
                    handPrivateRegionKey(wind, index),
                    service.handPrivateTileFingerprint(seat, seatPlan, index)
                );
            }
            for (int index = 0; index < seatPlan.discardPlacements().size(); index++) {
                values.put(discardRegionKey(wind, index), service.discardTileFingerprint(seat, seatPlan, index));
            }
            for (int index = 0; index < seatPlan.meldPlacements().size(); index++) {
                values.put(meldRegionKey(wind, index), service.meldTileFingerprint(seat, seatPlan, index));
            }
        }
        for (int index = 0; index < layout.wallTiles().size(); index++) {
            values.put(wallRegionKey(index), service.wallTileFingerprint(layout, index));
        }
        return Map.copyOf(values);
    }

    private static int plannedRegionCount(TableRenderSnapshot snapshot, TableRenderLayout.LayoutPlan layout) {
        int count = 3 + layout.wallTiles().size();
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan seatPlan = layout.seat(wind);
            if (seat.playerId() == null) {
                count += 3;
                continue;
            }
            count += 3 + seat.hand().size() * 2 + seatPlan.discardPlacements().size() + seatPlan.meldPlacements().size();
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static void seedStableState(TableRegionDisplayCoordinator coordinator, Map<String, Long> fingerprints)
        throws ReflectiveOperationException {
        Field fingerprintField = TableRegionDisplayCoordinator.class.getDeclaredField("regionFingerprints");
        fingerprintField.setAccessible(true);
        ((Map<String, Long>) fingerprintField.get(coordinator)).putAll(fingerprints);
        Field displayField = TableRegionDisplayCoordinator.class.getDeclaredField("regionDisplays");
        displayField.setAccessible(true);
        Map<String, List<org.bukkit.entity.Entity>> displays =
            (Map<String, List<org.bukkit.entity.Entity>>) displayField.get(coordinator);
        displays.put("table", List.of());
        for (SeatWind wind : SeatWind.values()) {
            displays.put(seatRegionKey("visual", wind), List.of());
        }
    }

    private static String seatRegionKey(String region, SeatWind wind) {
        return region + ":" + wind.name();
    }

    private static String discardRegionKey(SeatWind wind, int index) {
        return seatRegionKey("discards-" + index, wind);
    }

    private static String handPublicRegionKey(SeatWind wind, int index) {
        return seatRegionKey("hand-public-" + index, wind);
    }

    private static String handPrivateRegionKey(SeatWind wind, int index) {
        return seatRegionKey("hand-private-" + index, wind);
    }

    private static String meldRegionKey(SeatWind wind, int index) {
        return seatRegionKey("melds-" + index, wind);
    }

    private static String wallRegionKey(int index) {
        return "wall-" + index;
    }

    private static TableSessionContext session() {
        TableRenderer renderer = new TableRenderer();
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "id" -> "perf-region-apply";
            case "plugin" -> null;
            case "renderer" -> renderer;
            case "center" -> new Location(null, 12.75D, 64.0D, -7.25D);
            case "currentVariant" -> MahjongVariant.RIICHI;
            case "settings" -> PluginSettings.defaults();
            case "toString" -> "RegionApplySession";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> defaultValue(method);
        };
        return (TableSessionContext) Proxy.newProxyInstance(
            TableSessionContext.class.getClassLoader(), new Class<?>[] {TableSessionContext.class}, handler
        );
    }

    private static TableRenderSnapshot snapshot(boolean withHands) {
        List<MahjongTile> hand = withHands
            ? List.of(
                MahjongTile.M1, MahjongTile.M2, MahjongTile.M3, MahjongTile.M4, MahjongTile.M5_RED,
                MahjongTile.P2, MahjongTile.P3, MahjongTile.P4, MahjongTile.S6, MahjongTile.S7, MahjongTile.RED_DRAGON
            )
            : List.of();
        List<MahjongTile> discards = List.of(
            MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST, MahjongTile.NORTH,
            MahjongTile.WHITE_DRAGON, MahjongTile.GREEN_DRAGON, MahjongTile.RED_DRAGON,
            MahjongTile.M1, MahjongTile.M9, MahjongTile.P1, MahjongTile.P9,
            MahjongTile.S1, MahjongTile.S9, MahjongTile.M5_RED, MahjongTile.P5_RED,
            MahjongTile.S5_RED, MahjongTile.M4, MahjongTile.P6
        );
        MeldView meld = new MeldView(
            List.of(MahjongTile.P2, MahjongTile.P2, MahjongTile.P2),
            List.of(false, false, false), 1, 90, MahjongTile.P2
        );
        EnumMap<SeatWind, TableSeatRenderSnapshot> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            seats.put(wind, new TableSeatRenderSnapshot(
                wind, new UUID(0L, wind.index() + 1L), wind.name(), wind.name(), 25_000,
                false, true, false, true, "viewer-" + wind.name(), -1, List.of(), -1, 0,
                List.of(), hand, discards, List.of(meld), List.of(), List.of(ScoringStick.P100)
            ));
        }
        return new TableRenderSnapshot(
            1L, 0L, "benchmark_world", 12.75D, 64.0D, -7.25D,
            true, false, false, 70, 1, 7, 5, 1, 0,
            SeatWind.EAST, SeatWind.SOUTH, SeatWind.WEST,
            "waiting", "rules", "center", null, MahjongTile.RED_DRAGON,
            List.of(MahjongTile.M1), MahjongVariant.RIICHI, seats
        );
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        return null;
    }
}
