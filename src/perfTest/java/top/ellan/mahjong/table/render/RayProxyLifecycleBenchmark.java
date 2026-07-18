package top.ellan.mahjong.table.render;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Player;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.table.core.TableSessionContext;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RayProxyLifecycleBenchmark {
    @Benchmark
    public int initialCreate32Viewers(InitialCreateState state) {
        state.coordinator.replace("viewer-actions", state.interactions);
        return state.coordinator.entityCount();
    }

    @Benchmark
    public int unchanged32Viewers(LifecycleState state) {
        state.coordinator.replace("viewer-actions", state.geometryA);
        return state.coordinator.entityCount();
    }

    @Benchmark
    public int changedGeometry32Viewers(LifecycleState state) {
        state.geometryBActive = !state.geometryBActive;
        state.coordinator.replace("viewer-actions", state.geometryBActive ? state.geometryB : state.geometryA);
        return state.coordinator.entityCount();
    }

    @Benchmark
    public int oneViewerChurn(LifecycleState state) {
        state.lastViewerActive = !state.lastViewerActive;
        state.coordinator.replace("viewer-actions", state.lastViewerActive ? state.geometryA : state.geometryA31);
        return state.coordinator.entityCount();
    }

    @State(Scope.Thread)
    public static class InitialCreateState {
        private SparrowRayInteractionProxyCoordinator coordinator;
        private Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactions;

        @Setup(Level.Invocation)
        public void setUp() {
            Fixture fixture = fixture();
            this.coordinator = fixture.coordinator;
            this.interactions = fixture.geometryA;
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            this.coordinator.shutdown();
        }
    }

    @State(Scope.Thread)
    public static class LifecycleState {
        private SparrowRayInteractionProxyCoordinator coordinator;
        private Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> geometryA;
        private Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> geometryB;
        private Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> geometryA31;
        private boolean geometryBActive;
        private boolean lastViewerActive = true;

        @Setup(Level.Trial)
        public void setUp() {
            Fixture fixture = fixture();
            this.coordinator = fixture.coordinator;
            this.geometryA = fixture.geometryA;
            this.geometryB = fixture.geometryB;
            this.geometryA31 = withoutLast(this.geometryA);
            this.coordinator.replace("viewer-actions", this.geometryA);
            if (this.coordinator.entityCount() != 32) {
                throw new IllegalStateException("Ray lifecycle fixture changed");
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            this.coordinator.shutdown();
        }
    }

    private static Fixture fixture() {
        Map<UUID, Player> players = players();
        TableSessionContext session = session(players);
        return new Fixture(
            new SparrowRayInteractionProxyCoordinator(session, new CountingBackend()),
            interactions(players.keySet(), 0.0D),
            interactions(players.keySet(), 0.5D)
        );
    }

    private static Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactions(
        Iterable<UUID> viewers,
        double centerX
    ) {
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> result = new LinkedHashMap<>();
        UUID worldId = new UUID(0x52415950524F5859L, 1L);
        for (UUID viewerId : viewers) {
            result.put(viewerId, List.of(new DisplayInteractionRayRegistry.RayInteraction(
                worldId, centerX, 65.0D, 0.0D, 1.0D, 0.0D,
                0.24F, 0.90F, 0.075F,
                DisplayClickAction.playerCommand("perf-ray", viewerId, "noop")
            )));
        }
        return Map.copyOf(result);
    }

    private static Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> withoutLast(
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> source
    ) {
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> result = new LinkedHashMap<>(source);
        UUID last = null;
        for (UUID viewerId : result.keySet()) last = viewerId;
        result.remove(last);
        return Map.copyOf(result);
    }

    private static Map<UUID, Player> players() {
        Map<UUID, Player> players = new LinkedHashMap<>();
        for (int index = 0; index < 32; index++) {
            UUID id = new UUID(0x5241595649455700L, index + 1L);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isOnline" -> true;
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "RayViewer[" + id + "]";
                default -> defaultValue(method);
            };
            players.put(id, (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, handler));
        }
        return players;
    }

    private static TableSessionContext session(Map<UUID, Player> players) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "id" -> "perf-ray-lifecycle";
            case "onlinePlayer" -> players.get(args[0]);
            case "runForViewer" -> { ((Runnable) args[1]).run(); yield null; }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "RayLifecycleSession";
            default -> defaultValue(method);
        };
        return (TableSessionContext) Proxy.newProxyInstance(
            TableSessionContext.class.getClassLoader(), new Class<?>[] {TableSessionContext.class}, handler
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

    private record Fixture(
        SparrowRayInteractionProxyCoordinator coordinator,
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> geometryA,
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> geometryB
    ) {}

    private static final class CountingBackend implements SparrowRayInteractionProxyCoordinator.Backend {
        private int nextEntityId = 1_000_000;

        @Override public boolean available() { return true; }

        @Override
        public List<SparrowRayInteractionProxyCoordinator.ClientProxy> create(
            Player viewer,
            List<DisplayInteractionRayRegistry.RayInteraction> interactions
        ) {
            List<SparrowRayInteractionProxyCoordinator.ClientProxy> proxies = new ArrayList<>(interactions.size());
            for (int index = 0; index < interactions.size(); index++) {
                int entityId = ++this.nextEntityId;
                proxies.add(() -> entityId);
            }
            return List.copyOf(proxies);
        }

        @Override public void spawn(Player viewer, List<SparrowRayInteractionProxyCoordinator.ClientProxy> proxies) {}
        @Override public void destroy(Player viewer, List<SparrowRayInteractionProxyCoordinator.ClientProxy> proxies) {}
    }
}
