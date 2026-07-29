package top.ellan.mahjong.table.core.round;

import java.util.List;
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

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SichuanHuEvaluatorBenchmark {
    private List<MahjongTile> waitingHand;
    private List<MahjongTile> fixedMeldHand;

    @Setup(Level.Trial)
    public void setUp() {
        this.waitingHand = List.of(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.M4, MahjongTile.M5_RED, MahjongTile.M6,
            MahjongTile.P2, MahjongTile.P3, MahjongTile.P4,
            MahjongTile.S7, MahjongTile.S8, MahjongTile.S9, MahjongTile.P5
        );
        this.fixedMeldHand = List.of(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.P2, MahjongTile.P3, MahjongTile.P4,
            MahjongTile.S9, MahjongTile.S9, MahjongTile.S5_RED, MahjongTile.S5
        );
        if (!SichuanHuEvaluator.waitingTiles(this.waitingHand, 0)
            .equals(List.of(MahjongTile.P2, MahjongTile.P5))
            || !SichuanHuEvaluator.canWin(this.fixedMeldHand, MahjongTile.S5, 1)) {
            throw new IllegalStateException("Sichuan benchmark fixture changed");
        }
    }

    @Benchmark
    public List<MahjongTile> waitingTilesStandardHand() {
        return SichuanHuEvaluator.waitingTiles(this.waitingHand, 0);
    }

    @Benchmark
    public SichuanHuEvaluator.Result evaluateStandardWin() {
        return SichuanHuEvaluator.evaluate(this.waitingHand, MahjongTile.P5_RED, 0);
    }

    @Benchmark
    public boolean canWinWithFixedMeld() {
        return SichuanHuEvaluator.canWin(this.fixedMeldHand, MahjongTile.S5, 1);
    }
}
