package top.ellan.mahjong.presentation.perf;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.TableGeometry;
import top.ellan.mahjong.presentation.layout.UniversalTableLayout;
import top.ellan.mahjong.presentation.projection.DefaultTableSceneMapper;
import top.ellan.mahjong.presentation.scene.SceneDiff;
import top.ellan.mahjong.presentation.scene.SceneGraph;
import top.ellan.mahjong.presentation.scene.SceneGraphDiffer;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleTilePresentation;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.RuleWallDirection;
import top.ellan.mahjong.spi.RuleWallPresentation;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

/** Small dependency-free benchmark workload intended for paired local/JFR measurements. */
public final class SceneProjectionBenchmark {
    private static final int SAMPLE_COUNT = 7;
    private static final int DEFAULT_WARMUP = 5_000;
    private static final int DEFAULT_ITERATIONS = 10_000;
    private static final PlayerId[] PLAYERS = {
        player("00000000-0000-0000-0000-000000000001"),
        player("00000000-0000-0000-0000-000000000002"),
        player("00000000-0000-0000-0000-000000000003"),
        player("00000000-0000-0000-0000-000000000004")
    };
    private static final TableGeometry GEOMETRY = new TableGeometry(
            0.1125D,
            0.15D,
            0.075D,
            0.0025D,
            0.52D,
            1.225D,
            1.0D,
            1.4375D,
            0.06D,
            0.55D,
            0.24D,
            18,
            48,
            24,
            64,
            32,
            64);
    private static final TableSceneAssets ASSETS = new TableSceneAssets(
            "mahjongpaper:table_visual",
            "mahjongpaper:seat_chair",
            "mahjongpaper:tile_standing_face_down_back",
            "mahjongpaper:tile_flat_face_down_back",
            "mahjongpaper:hand_tile_hitbox",
            "mahjongpaper:action_button_hitbox");
    private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN = allocationBean();

    private static volatile Object sink;

    private SceneProjectionBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int warmup = positiveArgument(args, 0, DEFAULT_WARMUP);
        int iterations = positiveArgument(args, 1, DEFAULT_ITERATIONS);
        DefaultTableSceneMapper mapper = new DefaultTableSceneMapper(
                new UniversalTableLayout(GEOMETRY), ASSETS, 4.5D, true);
        TableProjection projection = representativeProjection();
        SceneGraph stable = mapper.map(projection);
        SceneGraphDiffer differ = new SceneGraphDiffer();

        run("map", warmup, iterations, ignored -> mapper.map(projection));
        run("mapAndDiff", warmup, iterations, ignored -> {
            SceneGraph mapped = mapper.map(projection);
            SceneDiff diff = differ.diff(stable, mapped);
            return diff;
        });
    }

    private static void run(
            String name, int warmup, int iterations, IntFunction<Object> operation) {
        for (int index = 0; index < warmup; index++) {
            sink = operation.apply(index);
        }
        long[] samples = new long[SAMPLE_COUNT];
        long[] allocationSamples = new long[SAMPLE_COUNT];
        for (int sample = 0; sample < samples.length; sample++) {
            long allocatedBefore = allocatedBytes();
            long started = System.nanoTime();
            for (int index = 0; index < iterations; index++) {
                sink = operation.apply(index);
            }
            samples[sample] = System.nanoTime() - started;
            long allocatedAfter = allocatedBytes();
            allocationSamples[sample] = allocatedBefore < 0L
                    ? -1L
                    : allocatedAfter - allocatedBefore;
        }
        java.util.Arrays.sort(samples);
        java.util.Arrays.sort(allocationSamples);
        double nanosPerOperation = (double) samples[samples.length / 2] / iterations;
        double operationsPerSecond = 1_000_000_000.0D / nanosPerOperation;
        double bytesPerOperation = allocationSamples[allocationSamples.length / 2] < 0L
                ? Double.NaN
                : (double) allocationSamples[allocationSamples.length / 2] / iterations;
        System.out.printf(
                "BENCHMARK name=%s nodes=%d warmup=%d iterations=%d samples=%d ns/op=%.1f ops/s=%.1f bytes/op=%.1f%n",
                name,
                ((SceneGraph) sinkGraph()).nodes().size(),
                warmup,
                iterations,
                samples.length,
                nanosPerOperation,
                operationsPerSecond,
                bytesPerOperation);
    }

    private static Object sinkGraph() {
        if (sink instanceof SceneGraph graph) {
            return graph;
        }
        return new DefaultTableSceneMapper(
                        new UniversalTableLayout(GEOMETRY), ASSETS, 4.5D, true)
                .map(representativeProjection());
    }

    private static TableProjection representativeProjection() {
        TableId tableId = new TableId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        RuleTablePresentation presentation = new RuleTablePresentation(
                4,
                new RuleWallPresentation(
                        List.of(17, 17, 17, 17), 0, RuleWallDirection.CLOCKWISE),
                6,
                Optional.of(new SeatId(0)),
                Optional.of(new SeatId(0)),
                Optional.empty());
        List<RuleViewTile> publicTiles = new ArrayList<>(136);
        for (int index = 0; index < 136; index++) {
            publicTiles.add(new RuleViewTile(
                    new TileInstanceId(index + 1L),
                    new TileVisualId("riichi:tile/m" + (index % 9 + 1)),
                    Optional.empty(),
                    RuleViewZone.WALL,
                    index,
                    false,
                    RuleTilePresentation.natural(index)));
        }

        Map<PlayerId, PrivateRuleView> privateViews = new LinkedHashMap<>();
        Map<PlayerId, List<AuthorizedAction>> actions = new LinkedHashMap<>();
        for (int seat = 0; seat < PLAYERS.length; seat++) {
            PlayerId player = PLAYERS[seat];
            SeatId seatId = new SeatId(seat);
            List<RuleViewTile> hand = new ArrayList<>(13);
            for (int index = 0; index < 13; index++) {
                long instance = seat * 13L + index + 1L;
                hand.add(new RuleViewTile(
                        new TileInstanceId(instance),
                        new TileVisualId("riichi:tile/m" + (index % 9 + 1)),
                        Optional.of(seatId),
                        RuleViewZone.HAND,
                        index,
                        true,
                        RuleTilePresentation.natural(index)));
            }
            privateViews.put(
                    player,
                    new PrivateRuleView(
                            1,
                            player,
                            seatId,
                            hand,
                            Map.of("score", Integer.toString(25_000 + seat), "riichi", "false")));
            TileInstanceId target = hand.getFirst().instanceId();
            AuthorizedAction discard = new AuthorizedAction(
                    new ActionToken(
                            UUID.nameUUIDFromBytes(("token-" + seat).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            player,
                            1),
                    new LegalAction(
                            "discard." + seat,
                            new RuleAction("discard", new byte[] {(byte) seat}),
                            ActionPresentation.handTile("action.discard", target)));
            actions.put(player, List.of(discard));
        }

        return new TableProjection(
                tableId,
                1,
                TableLifecycle.ACTIVE,
                new PublicRuleView(
                        1,
                        "playing",
                        publicTiles,
                        Map.of("dealer", "0", "round", "east-1", "wall", "70"),
                        presentation),
                privateViews,
                actions);
    }

    private static PlayerId player(String value) {
        return new PlayerId(UUID.fromString(value));
    }

    private static int positiveArgument(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        int value = Integer.parseInt(args[index]);
        if (value <= 0) {
            throw new IllegalArgumentException("benchmark arguments must be positive");
        }
        return value;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }

    private static long allocatedBytes() {
        return ALLOCATION_BEAN == null
                ? -1L
                : ALLOCATION_BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
