package top.ellan.mahjong.perf;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayEntityRuntime;

/** Measures the one-entity region shape used by hand, discard, meld and wall tiles. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class DisplayEntitiesSpawnAllBenchmark {
    private static final int REPRESENTATIVE_SINGLETON_REGION_COUNT = 268;

    private Entity entity;
    private DisplayEntityRuntime runtime;
    private List<DisplayEntities.EntitySpec> singletonSpec;

    @Setup(Level.Trial)
    public void setUp() {
        Plugin plugin = proxy(Plugin.class);
        this.entity = proxy(Entity.class);
        this.runtime = new EmptyViewerRuntime(plugin);
        this.singletonSpec = List.of(new ConstantEntitySpec(this.entity));
        this.verifyContract();
    }

    @Benchmark
    public int spawnRepresentativeTableRegions() {
        int spawned = 0;
        for (int region = 0; region < REPRESENTATIVE_SINGLETON_REGION_COUNT; region++) {
            spawned += DisplayEntities.spawnAll(this.runtime, this.singletonSpec).size();
        }
        return spawned;
    }

    private void verifyContract() {
        List<Entity> spawned = DisplayEntities.spawnAll(this.runtime, this.singletonSpec);
        if (spawned.size() != 1 || spawned.get(0) != this.entity) {
            throw new IllegalStateException("Singleton spawn contract changed");
        }
        try {
            spawned.add(this.entity);
            throw new IllegalStateException("spawnAll must return an immutable list");
        } catch (UnsupportedOperationException expected) {
            // Expected contract.
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (instance, method, arguments) -> {
                throw new UnsupportedOperationException(method.toString());
            }
        );
    }

    private record EmptyViewerRuntime(Plugin bukkitPlugin) implements DisplayEntityRuntime {
        @Override
        public Collection<? extends Player> onlinePlayers() {
            return List.of();
        }
    }

    private record ConstantEntitySpec(Entity entity) implements DisplayEntities.EntitySpec {
        @Override
        public Entity spawn(DisplayEntityRuntime runtime) {
            return this.entity;
        }

        @Override
        public boolean canReuse(DisplayEntityRuntime runtime, Entity candidate) {
            return candidate == this.entity;
        }

        @Override
        public void apply(DisplayEntityRuntime runtime, Entity candidate) {
            throw new UnsupportedOperationException("Benchmark does not reconcile entities");
        }
    }
}
