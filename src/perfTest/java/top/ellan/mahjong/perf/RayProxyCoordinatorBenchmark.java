package top.ellan.mahjong.perf;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
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
import top.ellan.mahjong.table.core.TableSessionContext;

/**
 * Contract benchmark for per-viewer client-side ray proxies.
 *
 * <p>The current dev baseline predates the production coordinator. This benchmark is already
 * protected and compiles there, then automatically exercises the real package-private
 * coordinator through a cached reflection adapter once the bugfix baseline supplies it. The
 * A/B runner refuses the ray-proxy profile until all required production classes are present.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RayProxyCoordinatorBenchmark {
    private Adapter adapter;

    @Setup(Level.Trial)
    public void setUp() {
        this.adapter = Adapter.load();
        System.out.println("Ray proxy benchmark adapter: " + this.adapter.name());
    }

    @Benchmark
    public RayProxyResult proxyPlan1Viewer() {
        return this.adapter.run(1);
    }

    @Benchmark
    public RayProxyResult proxyPlan4Viewers() {
        return this.adapter.run(4);
    }

    @Benchmark
    public RayProxyResult proxyPlan32Viewers() {
        return this.adapter.run(32);
    }

    public record RayProxyResult(
        String adapter,
        int viewers,
        int entities,
        int spawnBatches,
        int spawnedEntities,
        int unchangedReplaceSpawnedEntities
    ) {
    }

    private interface Adapter {
        RayProxyResult run(int viewerCount);

        String name();

        static Adapter load() {
            try {
                return new ProductionAdapter();
            } catch (ClassNotFoundException absentOnPreBugfixBaseline) {
                return new ContractAdapter();
            } catch (ReflectiveOperationException reflectionFailure) {
                throw new IllegalStateException("Ray proxy production API exists but no longer matches the protected benchmark", reflectionFailure);
            }
        }
    }

    /** Compile-time fallback only; the A/B profile requires the production classes in both jars. */
    private static final class ContractAdapter implements Adapter {
        @Override
        public RayProxyResult run(int viewerCount) {
            Map<UUID, Geometry> active = new LinkedHashMap<>();
            Geometry geometry = new Geometry(0.24F, 0.90F, 0.075F);
            int spawned = 0;
            for (int index = 0; index < viewerCount; index++) {
                UUID viewerId = viewerId(index);
                if (!geometry.equals(active.put(viewerId, geometry))) {
                    spawned++;
                }
            }
            int unchangedSpawned = 0;
            for (int index = 0; index < viewerCount; index++) {
                UUID viewerId = viewerId(index);
                if (!geometry.equals(active.put(viewerId, geometry))) {
                    unchangedSpawned++;
                }
            }
            return new RayProxyResult(this.name(), viewerCount, active.size(), viewerCount, spawned, unchangedSpawned);
        }

        @Override
        public String name() {
            return "pending-contract";
        }
    }

    private static final class ProductionAdapter implements Adapter {
        private static final String COORDINATOR =
            "top.ellan.mahjong.table.render.SparrowRayInteractionProxyCoordinator";
        private static final String RAY_INTERACTION =
            "top.ellan.mahjong.render.display.DisplayInteractionRayRegistry$RayInteraction";
        private static final String DISPLAY_ACTION = "top.ellan.mahjong.render.display.DisplayClickAction";

        private final Constructor<?> coordinatorConstructor;
        private final Constructor<?> interactionConstructor;
        private final Method playerCommandFactory;
        private final Method replace;
        private final Method entityCount;
        private final Method shutdown;
        private final Class<?> backendType;
        private final Class<?> clientProxyType;

        private ProductionAdapter() throws ReflectiveOperationException {
            Class<?> coordinatorType = Class.forName(COORDINATOR);
            this.backendType = Class.forName(COORDINATOR + "$Backend");
            this.clientProxyType = Class.forName(COORDINATOR + "$ClientProxy");
            Class<?> interactionType = Class.forName(RAY_INTERACTION);
            Class<?> actionType = Class.forName(DISPLAY_ACTION);
            this.coordinatorConstructor = coordinatorType.getDeclaredConstructor(TableSessionContext.class, this.backendType);
            this.coordinatorConstructor.setAccessible(true);
            this.interactionConstructor = interactionType.getDeclaredConstructor(
                UUID.class,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class,
                float.class,
                float.class,
                float.class,
                actionType
            );
            this.interactionConstructor.setAccessible(true);
            this.playerCommandFactory = actionType.getDeclaredMethod("playerCommand", String.class, UUID.class, String.class);
            this.playerCommandFactory.setAccessible(true);
            this.replace = coordinatorType.getDeclaredMethod("replace", String.class, Map.class);
            this.replace.setAccessible(true);
            this.entityCount = coordinatorType.getDeclaredMethod("entityCount");
            this.entityCount.setAccessible(true);
            this.shutdown = coordinatorType.getDeclaredMethod("shutdown");
            this.shutdown.setAccessible(true);
        }

        @Override
        public RayProxyResult run(int viewerCount) {
            Counters counters = new Counters();
            Map<UUID, Player> players = players(viewerCount);
            TableSessionContext session = session(players);
            Object backend = this.backend(counters);
            Object coordinator = construct(this.coordinatorConstructor, session, backend);
            try {
                Map<UUID, List<Object>> interactions = this.interactions(players.keySet());
                invoke(this.replace, coordinator, "viewer-actions", interactions);
                int entities = (int) invoke(this.entityCount, coordinator);
                int firstSpawned = counters.spawnedEntities;
                invoke(this.replace, coordinator, "viewer-actions", interactions);
                int unchangedSpawned = counters.spawnedEntities - firstSpawned;
                RayProxyResult result = new RayProxyResult(
                    this.name(),
                    viewerCount,
                    entities,
                    counters.spawnBatches,
                    firstSpawned,
                    unchangedSpawned
                );
                verify(result);
                return result;
            } finally {
                invoke(this.shutdown, coordinator);
            }
        }

        @Override
        public String name() {
            return "production-coordinator";
        }

        private Map<UUID, List<Object>> interactions(Iterable<UUID> viewerIds) {
            Map<UUID, List<Object>> interactions = new LinkedHashMap<>();
            UUID worldId = new UUID(0x52415950524F5859L, 1L);
            for (UUID viewerId : viewerIds) {
                Object action = invoke(this.playerCommandFactory, null, "perf-ray", viewerId, "noop");
                Object interaction = construct(
                    this.interactionConstructor,
                    worldId,
                    0.0D,
                    65.0D,
                    0.0D,
                    1.0D,
                    0.0D,
                    0.24F,
                    0.90F,
                    0.075F,
                    action
                );
                interactions.put(viewerId, List.of(interaction));
            }
            return interactions;
        }

        private Object backend(Counters counters) {
            InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
                case "available" -> true;
                case "create" -> this.createProxies((List<?>) arguments[1], counters);
                case "spawn" -> {
                    List<?> proxies = (List<?>) arguments[1];
                    counters.spawnBatches++;
                    counters.spawnedEntities += proxies.size();
                    yield null;
                }
                case "destroy", "disable" -> null;
                case "toString" -> "CountingRayProxyBackend";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method);
            };
            return Proxy.newProxyInstance(this.backendType.getClassLoader(), new Class<?>[] {this.backendType}, handler);
        }

        private List<Object> createProxies(List<?> interactions, Counters counters) {
            List<Object> proxies = new ArrayList<>(interactions.size());
            for (int index = 0; index < interactions.size(); index++) {
                int entityId = ++counters.nextEntityId;
                InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
                    case "entityId" -> entityId;
                    case "toString" -> "ClientProxy[" + entityId + "]";
                    case "hashCode" -> entityId;
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method);
                };
                proxies.add(
                    Proxy.newProxyInstance(
                        this.clientProxyType.getClassLoader(),
                        new Class<?>[] {this.clientProxyType},
                        handler
                    )
                );
            }
            return List.copyOf(proxies);
        }
    }

    private static Map<UUID, Player> players(int viewerCount) {
        Map<UUID, Player> players = new LinkedHashMap<>();
        for (int index = 0; index < viewerCount; index++) {
            UUID viewerId = viewerId(index);
            InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> viewerId;
                case "isOnline" -> true;
                case "toString" -> "RayViewer[" + viewerId + "]";
                case "hashCode" -> viewerId.hashCode();
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method);
            };
            Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                handler
            );
            players.put(viewerId, player);
        }
        return players;
    }

    private static TableSessionContext session(Map<UUID, Player> players) {
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "id" -> "perf-ray";
            case "onlinePlayer" -> players.get(arguments[0]);
            case "runForViewer" -> {
                ((Runnable) arguments[1]).run();
                yield null;
            }
            case "toString" -> "RaySessionFixture";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> defaultValue(method);
        };
        return (TableSessionContext) Proxy.newProxyInstance(
            TableSessionContext.class.getClassLoader(),
            new Class<?>[] {TableSessionContext.class},
            handler
        );
    }

    private static UUID viewerId(int index) {
        return new UUID(0x5241595649455700L, index + 1L);
    }

    private static void verify(RayProxyResult result) {
        if (result.entities() != result.viewers()
            || result.spawnBatches() != result.viewers()
            || result.spawnedEntities() != result.viewers()
            || result.unchangedReplaceSpawnedEntities() != 0) {
            throw new IllegalStateException("Ray proxy lifecycle contract changed: " + result);
        }
    }

    private static Object construct(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw propagate(exception);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (ReflectiveOperationException exception) {
            throw propagate(exception);
        }
    }

    private static RuntimeException propagate(ReflectiveOperationException exception) {
        if (exception instanceof InvocationTargetException invocation && invocation.getCause() instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(exception);
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }

    private record Geometry(float width, float height, float depth) {
    }

    private static final class Counters {
        private int nextEntityId = 1_000_000;
        private int spawnBatches;
        private int spawnedEntities;
    }
}
