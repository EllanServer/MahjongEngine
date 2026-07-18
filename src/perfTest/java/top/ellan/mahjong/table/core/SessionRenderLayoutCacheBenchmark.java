package top.ellan.mahjong.table.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.scene.MeldView;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.riichi.model.ScoringStick;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SessionRenderLayoutCacheBenchmark {
    @Benchmark
    public TableRenderLayout.LayoutPlan stableSessionCacheHit(HitState state) {
        state.alternate = !state.alternate;
        return state.precompute(state.alternate ? state.first : state.second);
    }

    @Benchmark
    public TableRenderLayout.LayoutPlan representativeSeatComponentMiss(SeatMissState state) {
        state.alternate = !state.alternate;
        return state.precompute(state.alternate ? state.first : state.second);
    }

    @Benchmark
    public TableRenderLayout.LayoutPlan representativeWallComponentMiss(WallMissState state) {
        state.alternate = !state.alternate;
        return state.precompute(state.alternate ? state.first : state.second);
    }

    private abstract static class CacheState {
        private MethodHandle precompute;

        final void initializePrecompute() throws ReflectiveOperationException {
            try {
                Class<?> cacheType = Class.forName("top.ellan.mahjong.table.core.SessionRenderLayoutCache");
                Constructor<?> constructor = cacheType.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object cache = constructor.newInstance();
                Method method = cacheType.getDeclaredMethod("precompute", TableRenderSnapshot.class);
                method.setAccessible(true);
                this.precompute = MethodHandles.lookup()
                    .unreflect(method)
                    .bindTo(cache)
                    .asType(MethodType.methodType(TableRenderLayout.LayoutPlan.class, TableRenderSnapshot.class));
            } catch (ClassNotFoundException ignored) {
                this.precompute = MethodHandles.lookup().findStatic(
                    TableRenderLayout.class,
                    "precompute",
                    MethodType.methodType(TableRenderLayout.LayoutPlan.class, TableRenderSnapshot.class)
                );
            } catch (NoSuchMethodException | IllegalAccessException exception) {
                throw new ReflectiveOperationException(exception);
            }
        }

        final TableRenderLayout.LayoutPlan precompute(TableRenderSnapshot snapshot) {
            try {
                return (TableRenderLayout.LayoutPlan) this.precompute.invokeExact(snapshot);
            } catch (Throwable throwable) {
                throw new IllegalStateException("Unable to invoke the layout precompute benchmark target", throwable);
            }
        }
    }

    @State(Scope.Thread)
    public static class HitState extends CacheState {
        private TableRenderSnapshot first;
        private TableRenderSnapshot second;
        private boolean alternate;

        @Setup(Level.Trial)
        public void setUp() throws ReflectiveOperationException {
            this.initializePrecompute();
            this.first = snapshot(1L);
            this.second = snapshot(2L);
            this.precompute(this.first);
        }
    }

    @State(Scope.Thread)
    public static class SeatMissState extends CacheState {
        private TableRenderSnapshot first;
        private TableRenderSnapshot second;
        private boolean alternate;

        @Setup(Level.Trial)
        public void setUp() throws ReflectiveOperationException {
            this.initializePrecompute();
            this.first = snapshot(1L, 70, List.of(4));
            this.second = snapshot(2L, 70, List.of(3));
            this.precompute(this.first);
        }
    }

    @State(Scope.Thread)
    public static class WallMissState extends CacheState {
        private TableRenderSnapshot first;
        private TableRenderSnapshot second;
        private boolean alternate;

        @Setup(Level.Trial)
        public void setUp() throws ReflectiveOperationException {
            this.initializePrecompute();
            this.first = snapshot(1L, 70, List.of(4));
            this.second = snapshot(2L, 69, List.of(4));
            this.precompute(this.first);
        }
    }

    private static TableRenderSnapshot snapshot(long version) {
        return snapshot(version, 70, List.of(4));
    }

    private static TableRenderSnapshot snapshot(long version, int remainingWallCount, List<Integer> selectedIndices) {
        List<MahjongTile> hand = List.of(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3, MahjongTile.M4, MahjongTile.M5_RED,
            MahjongTile.P2, MahjongTile.P3, MahjongTile.P4, MahjongTile.S6, MahjongTile.S7,
            MahjongTile.S8, MahjongTile.RED_DRAGON, MahjongTile.RED_DRAGON
        );
        List<MahjongTile> discards = List.of(
            MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST, MahjongTile.NORTH,
            MahjongTile.WHITE_DRAGON, MahjongTile.GREEN_DRAGON, MahjongTile.RED_DRAGON,
            MahjongTile.M1, MahjongTile.M9, MahjongTile.P1, MahjongTile.P9,
            MahjongTile.S1, MahjongTile.S9, MahjongTile.M5_RED, MahjongTile.P5_RED,
            MahjongTile.S5_RED, MahjongTile.M4, MahjongTile.P6
        );
        MeldView meld = new MeldView(
            List.of(MahjongTile.P2, MahjongTile.P2, MahjongTile.P2),
            List.of(false, false, false),
            1,
            90,
            MahjongTile.P2
        );
        EnumMap<SeatWind, TableSeatRenderSnapshot> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            seats.put(wind, new TableSeatRenderSnapshot(
                wind,
                new UUID(0L, wind.index() + 1L),
                wind.name(),
                wind.name(),
                25_000,
                wind == SeatWind.EAST,
                true,
                false,
                true,
                "viewer-" + wind.name(),
                selectedIndices.get(0),
                selectedIndices,
                wind == SeatWind.EAST ? 5 : -1,
                wind.index() + 1,
                List.of(),
                hand,
                discards,
                List.of(meld),
                List.of(ScoringStick.P1000),
                List.of(ScoringStick.P100)
            ));
        }
        return new TableRenderSnapshot(
            version,
            0L,
            "benchmark_world",
            12.75D,
            64.0D,
            -7.25D,
            true,
            false,
            false,
            remainingWallCount,
            1,
            7,
            5,
            1,
            0,
            SeatWind.EAST,
            SeatWind.SOUTH,
            SeatWind.WEST,
            "waiting",
            "rules",
            "center-" + version,
            null,
            null,
            List.of(MahjongTile.M1),
            MahjongVariant.RIICHI,
            seats
        );
    }
}
