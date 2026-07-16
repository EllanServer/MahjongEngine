package top.ellan.mahjong.table.core.round;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.gb.jni.GbTingCandidate;
import top.ellan.mahjong.gb.jni.GbTingResponse;
import top.ellan.mahjong.model.MahjongTile;

/** Measures the real GB discard decision path across three tile-duplication patterns. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class GbBotDecisionBenchmark {
    private static final GbTingResponse READY = new GbTingResponse(
        true,
        List.of(new GbTingCandidate("M1", 8, List.of())),
        null
    );
    private static final List<MahjongTile> DUPLICATE_HAND = List.of(
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M1
    );
    private static final List<MahjongTile> MIXED_HAND = List.of(
        MahjongTile.M1,
        MahjongTile.M1,
        MahjongTile.M3,
        MahjongTile.M3,
        MahjongTile.M5,
        MahjongTile.M5,
        MahjongTile.P2,
        MahjongTile.P2,
        MahjongTile.P7,
        MahjongTile.P7,
        MahjongTile.S4,
        MahjongTile.S4,
        MahjongTile.EAST,
        MahjongTile.EAST
    );
    private static final List<MahjongTile> UNIQUE_HAND = List.of(
        MahjongTile.M1,
        MahjongTile.M2,
        MahjongTile.M3,
        MahjongTile.M4,
        MahjongTile.M5,
        MahjongTile.P1,
        MahjongTile.P2,
        MahjongTile.P3,
        MahjongTile.P4,
        MahjongTile.S1,
        MahjongTile.S2,
        MahjongTile.EAST,
        MahjongTile.WHITE_DRAGON,
        MahjongTile.RED_DRAGON
    );

    private final GbBotDecisionService service = new GbBotDecisionService(8);
    private int evaluations;

    @Benchmark
    public BotDecisionResult duplicateHand() {
        return this.decide(DUPLICATE_HAND, 1);
    }

    @Benchmark
    public BotDecisionResult mixedHand() {
        return this.decide(MIXED_HAND, 7);
    }

    @Benchmark
    public BotDecisionResult uniqueHand() {
        return this.decide(UNIQUE_HAND, 14);
    }

    private BotDecisionResult decide(List<MahjongTile> hand, int expectedEvaluations) {
        this.evaluations = 0;
        int discardIndex = this.service.suggestedDiscardIndex(hand, List.of(), (remaining, melds) -> {
            this.evaluations++;
            return READY;
        });
        if (this.evaluations != expectedEvaluations) {
            throw new IllegalStateException(
                "Expected " + expectedEvaluations + " ting evaluations, got " + this.evaluations
            );
        }
        return new BotDecisionResult(discardIndex, this.evaluations);
    }

    public record BotDecisionResult(int discardIndex, int tingEvaluations) {
    }
}
