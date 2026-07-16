package top.ellan.mahjong.perf;

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

/** Measures complete started-table layouts for every supported ruleset. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TableRenderLayoutBenchmark {
    private static final int DEAD_WALL_SIZE = 14;
    private static final long DIGEST_SEED = 0xBB67AE8584CAA73BL;
    private static final long NULL_PLACEMENT_MARKER = 0xD1B54A32D192ED03L;
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
        MahjongTile.P6,
        MahjongTile.S2,
        MahjongTile.M7,
        MahjongTile.P8,
        MahjongTile.S4,
        MahjongTile.M6,
        MahjongTile.P3
    );
    private static final List<ScoringStick> SCORING_STICKS = List.of(
        ScoringStick.P1000,
        ScoringStick.P100,
        ScoringStick.P5000
    );
    private static final List<ScoringStick> CORNER_STICKS = List.of(
        ScoringStick.P100,
        ScoringStick.P1000,
        ScoringStick.P5000,
        ScoringStick.P10000,
        ScoringStick.P100,
        ScoringStick.P1000
    );
    private static final List<MahjongTile> DORA_INDICATORS = List.of(
        MahjongTile.M1,
        MahjongTile.P1,
        MahjongTile.S1
    );

    private TableRenderSnapshot riichiSnapshot;
    private TableRenderSnapshot gbSnapshot;
    private TableRenderSnapshot sichuanSnapshot;
    private long expectedRiichiDigest;
    private long expectedGbDigest;
    private long expectedSichuanDigest;

    @Setup(Level.Trial)
    public void setUpFixture() {
        this.riichiSnapshot = fixture(MahjongVariant.RIICHI, 80);
        this.gbSnapshot = fixture(MahjongVariant.GB, 96);
        this.sichuanSnapshot = fixture(MahjongVariant.SICHUAN, 72);
        this.expectedRiichiDigest = initializeFixture(this.riichiSnapshot);
        this.expectedGbDigest = initializeFixture(this.gbSnapshot);
        this.expectedSichuanDigest = initializeFixture(this.sichuanSnapshot);
        this.verifyLayoutContract();
    }

    /** Rechecks the complete shape and deterministic geometry outside the timed section. */
    @Setup(Level.Iteration)
    public void verifyLayoutContract() {
        verifyLayoutContract(this.riichiSnapshot, this.expectedRiichiDigest);
        verifyLayoutContract(this.gbSnapshot, this.expectedGbDigest);
        verifyLayoutContract(this.sichuanSnapshot, this.expectedSichuanDigest);
    }

    @Benchmark
    public TableRenderLayout.LayoutPlan precomputeCompleteRiichiTable() {
        return TableRenderLayout.precompute(this.riichiSnapshot);
    }

    @Benchmark
    public TableRenderLayout.LayoutPlan precomputeCompleteGbTable() {
        return TableRenderLayout.precompute(this.gbSnapshot);
    }

    @Benchmark
    public TableRenderLayout.LayoutPlan precomputeCompleteSichuanTable() {
        return TableRenderLayout.precompute(this.sichuanSnapshot);
    }

    private static long initializeFixture(TableRenderSnapshot snapshot) {
        TableRenderLayout.LayoutPlan baseline = TableRenderLayout.precompute(snapshot);
        verifyFixtureCoverage(snapshot, baseline);
        return digest(baseline);
    }

    private static void verifyLayoutContract(TableRenderSnapshot snapshot, long expectedDigest) {
        TableRenderLayout.LayoutPlan actual = TableRenderLayout.precompute(snapshot);
        verifyFixtureCoverage(snapshot, actual);
        long actualDigest = digest(actual);
        if (actualDigest != expectedDigest) {
            throw new IllegalStateException(
                snapshot.variant() + " layout digest changed: expected=" + expectedDigest + ", actual=" + actualDigest
            );
        }
    }

    private static void verifyFixtureCoverage(TableRenderSnapshot snapshot, TableRenderLayout.LayoutPlan layout) {
        if (!snapshot.started() || layout.wallTiles().size() != snapshot.wallCapacity()) {
            throw new IllegalStateException(
                snapshot.variant() + " fixture no longer covers all " + snapshot.wallCapacity() + " physical wall slots"
            );
        }
        try {
            layout.wallTiles().set(0, layout.wallTiles().get(0));
            throw new IllegalStateException(snapshot.variant() + " wall layout must remain externally immutable");
        } catch (UnsupportedOperationException expected) {
            // The production optimization measured by this profile may reuse the backing buffer,
            // but callers must never be able to mutate it after publication.
        }
        int expectedDoraTiles = snapshot.usesDeadWall() ? snapshot.doraIndicators().size() : 0;
        int expectedWallTiles = snapshot.remainingWallCount()
            + (snapshot.usesDeadWall() ? DEAD_WALL_SIZE - expectedDoraTiles : 0);
        long actualWallTiles = layout.wallTiles().stream().filter(placement -> placement != null).count();
        if (actualWallTiles != expectedWallTiles || layout.doraTiles().size() != expectedDoraTiles) {
            throw new IllegalStateException(
                "Incomplete " + snapshot.variant() + " wall fixture: expected=" + expectedWallTiles
                    + ", actual=" + actualWallTiles
                    + ", dora=" + layout.doraTiles().size()
            );
        }
        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = snapshot.seat(wind);
            TableRenderLayout.SeatLayoutPlan seatLayout = layout.seat(wind);
            int expectedMeldTiles = seat.melds().stream()
                .mapToInt(meld -> meld.tiles().size() + (meld.hasAddedKanTile() ? 1 : 0))
                .sum();
            int expectedSticks = seat.cornerSticks().size() + (seat.riichi() ? 1 : 0);
            if (seat.playerId() == null
                || seatLayout.publicHandPoints().size() != HAND.size()
                || seatLayout.privateHandPoints().size() != HAND.size()
                || seatLayout.discardPlacements().size() != DISCARDS.size()
                || seatLayout.meldPlacements().size() != expectedMeldTiles
                || seatLayout.stickPlacements().size() != expectedSticks) {
                throw new IllegalStateException("Incomplete seat layout fixture for " + wind);
            }
        }
    }

    private static TableRenderSnapshot fixture(MahjongVariant variant, int remainingWallCount) {
        EnumMap<SeatWind, TableSeatRenderSnapshot> seats = new EnumMap<>(SeatWind.class);
        for (SeatWind wind : SeatWind.values()) {
            seats.put(wind, seatFixture(wind));
        }
        return new TableRenderSnapshot(
            17L,
            3L,
            "benchmark_world",
            12.75D,
            64.0D,
            -7.25D,
            true,
            false,
            false,
            remainingWallCount,
            2,
            7,
            5,
            1,
            2,
            SeatWind.EAST,
            SeatWind.SOUTH,
            SeatWind.WEST,
            "waiting",
            "rules",
            "East 2 | 2 honba",
            playerId(SeatWind.WEST),
            MahjongTile.RED_DRAGON,
            variant == MahjongVariant.RIICHI ? DORA_INDICATORS : List.of(),
            variant,
            seats
        );
    }

    private static TableSeatRenderSnapshot seatFixture(SeatWind wind) {
        return new TableSeatRenderSnapshot(
            wind,
            playerId(wind),
            "player-" + wind.name().toLowerCase(),
            wind.name() + " 25000",
            25_000,
            true,
            true,
            false,
            true,
            "viewer:" + wind.name(),
            10,
            List.of(4, 10),
            5 + wind.index(),
            CORNER_STICKS.size(),
            List.of(),
            HAND,
            DISCARDS,
            List.of(meldFixture(wind)),
            SCORING_STICKS,
            CORNER_STICKS
        );
    }

    private static MeldView meldFixture(SeatWind wind) {
        return switch (wind) {
            case EAST -> new MeldView(
                List.of(MahjongTile.M2, MahjongTile.M3, MahjongTile.M4),
                List.of(false, false, false),
                0,
                90,
                null
            );
            case SOUTH -> new MeldView(
                List.of(MahjongTile.P7, MahjongTile.P7, MahjongTile.P7),
                List.of(false, false, false),
                1,
                -90,
                null
            );
            case WEST -> new MeldView(
                List.of(MahjongTile.S9, MahjongTile.S9, MahjongTile.S9, MahjongTile.S9),
                List.of(true, false, false, true),
                -1,
                0,
                null
            );
            case NORTH -> new MeldView(
                List.of(MahjongTile.GREEN_DRAGON, MahjongTile.GREEN_DRAGON, MahjongTile.GREEN_DRAGON),
                List.of(false, false, false),
                2,
                90,
                MahjongTile.GREEN_DRAGON
            );
        };
    }

    private static UUID playerId(SeatWind wind) {
        return new UUID(0L, wind.index() + 1L);
    }

    private static long digest(TableRenderLayout.LayoutPlan layout) {
        long digest = DIGEST_SEED;
        digest = mix(digest, layout.displayCenter());
        digest = mix(digest, layout.tableCenter());
        digest = mix(digest, layout.tableVisualAnchor());
        digest = mix(digest, Double.doubleToLongBits(layout.borderSpanX()));
        digest = mix(digest, Double.doubleToLongBits(layout.borderSpanZ()));
        for (SeatWind wind : SeatWind.values()) {
            digest = mix(digest, wind.ordinal());
            digest = mix(digest, layout.seat(wind));
        }
        for (TableRenderLayout.TilePlacement placement : layout.wallTiles()) {
            digest = mix(digest, placement);
        }
        for (TableRenderLayout.TilePlacement placement : layout.doraTiles()) {
            digest = mix(digest, placement);
        }
        return digest;
    }

    private static long mix(long digest, TableRenderLayout.SeatLayoutPlan seat) {
        digest = mix(digest, seat.handBase());
        digest = mix(digest, seat.statusLabelLocation());
        digest = mix(digest, seat.playerNameLocation());
        digest = mix(digest, seat.interactionLocation());
        digest = mix(digest, Float.floatToIntBits(seat.yaw()));
        for (TableRenderLayout.Point point : seat.publicHandPoints()) {
            digest = mix(digest, point);
        }
        for (TableRenderLayout.Point point : seat.privateHandPoints()) {
            digest = mix(digest, point);
        }
        for (TableRenderLayout.TilePlacement placement : seat.discardPlacements()) {
            digest = mix(digest, placement);
        }
        for (TableRenderLayout.TilePlacement placement : seat.meldPlacements()) {
            digest = mix(digest, placement);
        }
        for (TableRenderLayout.StickPlacement placement : seat.stickPlacements()) {
            digest = mix(digest, placement);
        }
        return digest;
    }

    private static long mix(long digest, TableRenderLayout.TilePlacement placement) {
        if (placement == null) {
            return mix(digest, NULL_PLACEMENT_MARKER);
        }
        digest = mix(digest, placement.point());
        digest = mix(digest, Float.floatToIntBits(placement.yaw()));
        digest = mix(digest, placement.tile().ordinal());
        return mix(digest, placement.pose().ordinal());
    }

    private static long mix(long digest, TableRenderLayout.StickPlacement placement) {
        digest = mix(digest, placement.center());
        digest = mix(digest, placement.longOnX() ? 1L : 0L);
        return mix(digest, placement.stick().ordinal());
    }

    private static long mix(long digest, TableRenderLayout.Point point) {
        digest = mix(digest, Double.doubleToLongBits(point.x()));
        digest = mix(digest, Double.doubleToLongBits(point.y()));
        return mix(digest, Double.doubleToLongBits(point.z()));
    }

    private static long mix(long digest, long value) {
        return Long.rotateLeft(digest, 11) ^ value * 0x9E3779B97F4A7C15L;
    }
}
