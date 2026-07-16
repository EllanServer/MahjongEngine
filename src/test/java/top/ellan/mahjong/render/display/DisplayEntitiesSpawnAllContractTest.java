package top.ellan.mahjong.render.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class DisplayEntitiesSpawnAllContractTest {
    @Test
    void singletonSpawnReturnsTheSameEntityInAnImmutableList() {
        Entity entity = proxy(Entity.class);
        CountingRuntime runtime = new CountingRuntime(proxy(Plugin.class));

        List<Entity> spawned = DisplayEntities.spawnAll(runtime, List.of(new ConstantEntitySpec(entity)));

        assertEquals(1, spawned.size());
        assertSame(entity, spawned.get(0));
        assertEquals(1, runtime.onlinePlayerReads());
        assertThrows(UnsupportedOperationException.class, () -> spawned.add(entity));
    }

    @Test
    void singletonNullSpawnReturnsAnImmutableEmptyList() {
        CountingRuntime runtime = new CountingRuntime(proxy(Plugin.class));

        List<Entity> spawned = DisplayEntities.spawnAll(runtime, List.of(new ConstantEntitySpec(null)));

        assertEquals(0, spawned.size());
        assertEquals(1, runtime.onlinePlayerReads());
        assertThrows(UnsupportedOperationException.class, () -> spawned.add(proxy(Entity.class)));
    }

    @Test
    void multipleSpawnsPreserveOrderAndSkipNulls() {
        Entity first = proxy(Entity.class);
        Entity second = proxy(Entity.class);
        CountingRuntime runtime = new CountingRuntime(proxy(Plugin.class));

        List<Entity> spawned = DisplayEntities.spawnAll(
            runtime,
            List.of(new ConstantEntitySpec(first), new ConstantEntitySpec(null), new ConstantEntitySpec(second))
        );

        assertEquals(2, spawned.size());
        assertSame(first, spawned.get(0));
        assertSame(second, spawned.get(1));
        assertEquals(1, runtime.onlinePlayerReads());
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

    private static final class CountingRuntime implements DisplayEntityRuntime {
        private final Plugin plugin;
        private int onlinePlayerReads;

        private CountingRuntime(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public Plugin bukkitPlugin() {
            return this.plugin;
        }

        @Override
        public Collection<? extends Player> onlinePlayers() {
            this.onlinePlayerReads++;
            return List.of();
        }

        private int onlinePlayerReads() {
            return this.onlinePlayerReads;
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
            throw new UnsupportedOperationException("Contract test does not reconcile entities");
        }
    }
}
