package top.ellan.mahjong.riichi;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
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
import top.ellan.mahjong.riichi.model.MahjongTile;
import top.ellan.mahjong.riichi.model.TileInstance;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RiichiReactionBenchmark {
    @Benchmark
    public ReactionOptions structurallyImpossibleCall(NoCallState state) {
        return state.player.reactionOptionsFor(state.discard, false, false);
    }

    @Benchmark
    public ReactionOptions structurallyImpossibleRonOnly(NoCallState state) {
        return state.player.reactionOptionsFor(state.discard, false, true);
    }

    @Benchmark
    public ReactionOptions firstPossiblePonAnalysis(PossibleCallState state) {
        return state.player.reactionOptionsFor(state.discard, false, false);
    }

    @State(Scope.Thread)
    public static class NoCallState {
        private RiichiPlayerState player;
        private TileInstance discard;
        private Map<?, ?> furoReactions;

        @Setup(Level.Trial)
        public void setUp() throws ReflectiveOperationException {
            this.player = player(List.of(
                MahjongTile.M1, MahjongTile.M4, MahjongTile.M7,
                MahjongTile.P1, MahjongTile.P4, MahjongTile.P7,
                MahjongTile.S1, MahjongTile.S4, MahjongTile.S7,
                MahjongTile.SOUTH, MahjongTile.WEST, MahjongTile.WHITE_DRAGON, MahjongTile.GREEN_DRAGON
            ));
            this.discard = tile(100, MahjongTile.EAST);
            if (this.player.reactionOptionsFor(this.discard, false, false) != null) {
                throw new IllegalStateException("No-call reaction fixture changed");
            }
            Field cacheField = RiichiPlayerAnalysisState.class.getDeclaredField("cachedFuroReactions");
            cacheField.setAccessible(true);
            this.furoReactions = (Map<?, ?>) cacheField.get(this.player);
        }

        @Setup(Level.Invocation)
        public void clearFuroReaction() {
            this.furoReactions.clear();
        }
    }

    @State(Scope.Thread)
    public static class PossibleCallState {
        private RiichiPlayerState player;
        private TileInstance discard;

        @Setup(Level.Invocation)
        public void setUp() {
            this.player = player(List.of(
                MahjongTile.M5_RED, MahjongTile.M5, MahjongTile.M1, MahjongTile.M9,
                MahjongTile.P1, MahjongTile.P9, MahjongTile.S1, MahjongTile.S9,
                MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST,
                MahjongTile.WHITE_DRAGON, MahjongTile.GREEN_DRAGON
            ));
            this.discard = tile(200, MahjongTile.M5);
        }
    }

    private static RiichiPlayerState player(List<MahjongTile> hand) {
        RiichiPlayerState player = new RiichiPlayerState("benchmark", "benchmark", false);
        for (int index = 0; index < hand.size(); index++) {
            player.getHands().add(tile(index, hand.get(index)));
        }
        return player;
    }

    private static TileInstance tile(int id, MahjongTile tile) {
        return new TileInstance(new UUID(0L, id + 1L), tile);
    }
}
