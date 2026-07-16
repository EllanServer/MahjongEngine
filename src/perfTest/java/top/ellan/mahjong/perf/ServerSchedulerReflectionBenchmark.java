package top.ellan.mahjong.perf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import top.ellan.mahjong.runtime.PluginTask;
import top.ellan.mahjong.runtime.ServerScheduler;

/** Measures the reflective scheduler-capability dispatch used across Paper and Folia. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ServerSchedulerReflectionBenchmark {
    private static final int GLOBAL_TASKS = 4;
    private static final int REGION_TASKS = 16;
    private static final int ENTITY_TASKS = 64;
    private static final int REPRESENTATIVE_TASKS = GLOBAL_TASKS + REGION_TASKS + ENTITY_TASKS;
    private static final Runnable NO_OP = () -> {
    };

    private Harness folia;
    private Harness paper;
    private Location location;

    @Setup(Level.Trial)
    public void setUp() {
        World world = proxy(World.class, ServerSchedulerReflectionBenchmark::unsupported);
        this.location = new Location(world, 8.0, 64.0, -8.0);
        this.folia = createFoliaHarness();
        this.paper = createPaperHarness();
        this.verifyContract();
    }

    @Benchmark
    @OperationsPerInvocation(REPRESENTATIVE_TASKS)
    public void scheduleFoliaBurst(Blackhole blackhole) {
        this.scheduleBurst(this.folia, blackhole);
    }

    @Benchmark
    @OperationsPerInvocation(REPRESENTATIVE_TASKS)
    public void schedulePaperBurst(Blackhole blackhole) {
        this.scheduleBurst(this.paper, blackhole);
    }

    private void scheduleBurst(Harness harness, Blackhole blackhole) {
        for (int index = 0; index < GLOBAL_TASKS; index++) {
            blackhole.consume(harness.scheduler().runGlobal(NO_OP));
        }
        for (int index = 0; index < REGION_TASKS; index++) {
            blackhole.consume(harness.scheduler().runRegion(this.location, NO_OP));
        }
        for (int index = 0; index < ENTITY_TASKS; index++) {
            blackhole.consume(harness.scheduler().runEntity(harness.entity(), NO_OP));
        }
    }

    private void verifyContract() {
        this.verifyHarness(this.folia, true);
        this.verifyHarness(this.paper, false);
    }

    private void verifyHarness(Harness harness, boolean expectFolia) {
        int foliaBefore = harness.foliaInvocations().value;
        int paperBefore = harness.paperInvocations().value;
        assertActive(harness.scheduler().runGlobal(NO_OP));
        assertActive(harness.scheduler().runRegion(this.location, NO_OP));
        assertActive(harness.scheduler().runEntity(harness.entity(), NO_OP));
        int foliaDelta = harness.foliaInvocations().value - foliaBefore;
        int paperDelta = harness.paperInvocations().value - paperBefore;
        if (expectFolia ? foliaDelta != 3 || paperDelta != 0 : foliaDelta != 0 || paperDelta != 3) {
            throw new IllegalStateException(
                "Scheduler route changed: folia=" + foliaDelta + ", paper=" + paperDelta
            );
        }
    }

    private static void assertActive(PluginTask task) {
        if (task == null || task.isCancelled()) {
            throw new IllegalStateException("Scheduler must return an active task handle");
        }
    }

    private static Harness createFoliaHarness() {
        Counter foliaInvocations = new Counter();
        Counter paperInvocations = new Counter();
        BukkitScheduler bukkitScheduler = bukkitScheduler(paperInvocations);
        Object globalScheduler = schedulerProxy(
            capabilityReturnType(Server.class, "getGlobalRegionScheduler"),
            new Class<?>[] {Plugin.class, Consumer.class},
            foliaInvocations
        );
        Object regionScheduler = schedulerProxy(
            capabilityReturnType(Server.class, "getRegionScheduler"),
            new Class<?>[] {Plugin.class, Location.class, Consumer.class},
            foliaInvocations
        );
        Object entityScheduler = schedulerProxy(
            capabilityReturnType(Entity.class, "getScheduler"),
            new Class<?>[] {Plugin.class, Consumer.class, Runnable.class},
            foliaInvocations
        );
        Server server = proxy(
            Server.class,
            (instance, method, arguments) -> switch (method.getName()) {
                case "getGlobalRegionScheduler" -> globalScheduler;
                case "getRegionScheduler" -> regionScheduler;
                case "getScheduler" -> bukkitScheduler;
                default -> unsupported(instance, method, arguments);
            }
        );
        Plugin plugin = plugin(server);
        Entity entity = proxy(
            Entity.class,
            (instance, method, arguments) -> {
                if (method.getName().equals("getScheduler") && method.getParameterCount() == 0) {
                    return entityScheduler;
                }
                return unsupported(instance, method, arguments);
            }
        );
        return new Harness(new ServerScheduler(plugin), entity, foliaInvocations, paperInvocations);
    }

    private static Harness createPaperHarness() {
        Counter foliaInvocations = new Counter();
        Counter paperInvocations = new Counter();
        BukkitScheduler bukkitScheduler = bukkitScheduler(paperInvocations);
        Server server = proxy(
            Server.class,
            (instance, method, arguments) -> {
                if (
                    (method.getName().equals("getGlobalRegionScheduler") || method.getName().equals("getRegionScheduler"))
                        && method.getParameterCount() == 0
                ) {
                    return null;
                }
                if (method.getName().equals("getScheduler") && method.getParameterCount() == 0) {
                    return bukkitScheduler;
                }
                return unsupported(instance, method, arguments);
            }
        );
        Plugin plugin = plugin(server);
        Entity entity = proxy(
            Entity.class,
            (instance, method, arguments) -> {
                if (method.getName().equals("getScheduler") && method.getParameterCount() == 0) {
                    return null;
                }
                return unsupported(instance, method, arguments);
            }
        );
        return new Harness(new ServerScheduler(plugin), entity, foliaInvocations, paperInvocations);
    }

    private static Object schedulerProxy(Class<?> schedulerType, Class<?>[] runParameters, Counter invocations) {
        Class<?> taskType;
        try {
            taskType = schedulerType.getMethod("run", runParameters).getReturnType();
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Scheduler contract changed: " + schedulerType.getName(), exception);
        }
        Object task = interfaceProxy(
            taskType,
            (instance, method, arguments) -> {
                if (method.getName().equals("isCancelled") || method.getName().equals("cancel")) {
                    return defaultValue(method.getReturnType());
                }
                return unsupported(instance, method, arguments);
            }
        );
        return interfaceProxy(
            schedulerType,
            (instance, method, arguments) -> {
                if (method.getName().startsWith("run")) {
                    invocations.value++;
                    return task;
                }
                return unsupported(instance, method, arguments);
            }
        );
    }

    private static Class<?> capabilityReturnType(Class<?> owner, String methodName) {
        try {
            return owner.getMethod(methodName).getReturnType();
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Paper scheduler capability is unavailable: " + methodName, exception);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
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
        throw new IllegalStateException("Unknown primitive return type: " + type.getName());
    }

    private static Plugin plugin(Server server) {
        return proxy(
            Plugin.class,
            (instance, method, arguments) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getServer" -> server;
                default -> unsupported(instance, method, arguments);
            }
        );
    }

    private static BukkitScheduler bukkitScheduler(Counter invocations) {
        BukkitTask task = proxy(
            BukkitTask.class,
            (instance, method, arguments) -> {
                if (method.getName().equals("isCancelled") && method.getParameterCount() == 0) {
                    return false;
                }
                if (method.getName().equals("cancel") && method.getParameterCount() == 0) {
                    return null;
                }
                return unsupported(instance, method, arguments);
            }
        );
        return proxy(
            BukkitScheduler.class,
            (instance, method, arguments) -> {
                if (method.getName().startsWith("runTask")) {
                    invocations.value++;
                    return task;
                }
                return unsupported(instance, method, arguments);
            }
        );
    }

    private static Object unsupported(Object instance, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
                case "toString" -> instance.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
        throw new UnsupportedOperationException(method.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> primary, InvocationHandler handler, Class<?>... additionalInterfaces) {
        Class<?>[] interfaces = new Class<?>[additionalInterfaces.length + 1];
        interfaces[0] = primary;
        System.arraycopy(additionalInterfaces, 0, interfaces, 1, additionalInterfaces.length);
        return (T) Proxy.newProxyInstance(
            ServerSchedulerReflectionBenchmark.class.getClassLoader(),
            interfaces,
            handler
        );
    }

    private static Object interfaceProxy(Class<?> type, InvocationHandler handler) {
        if (!type.isInterface()) {
            throw new IllegalStateException("Expected an interface capability: " + type.getName());
        }
        return Proxy.newProxyInstance(
            ServerSchedulerReflectionBenchmark.class.getClassLoader(),
            new Class<?>[] {type},
            handler
        );
    }

    private static final class Counter {
        private int value;
    }

    private record Harness(
        ServerScheduler scheduler,
        Entity entity,
        Counter foliaInvocations,
        Counter paperInvocations
    ) {
    }
}
