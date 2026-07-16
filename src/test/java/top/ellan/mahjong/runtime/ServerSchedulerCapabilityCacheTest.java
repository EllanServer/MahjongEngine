package top.ellan.mahjong.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class ServerSchedulerCapabilityCacheTest {
    private static final Runnable NO_OP = () -> {
    };

    @Test
    void routesEveryFoliaSignatureThroughItsReflectiveCapability() {
        Harness harness = foliaHarness(false);

        invokeEverySignature(harness);
        invokeEverySignature(harness);

        assertEquals(16, harness.foliaInvocations.value);
        assertEquals(0, harness.paperInvocations.value);
    }

    @Test
    void preservesPaperFallbackWhenCapabilitiesAreAbsent() {
        Harness harness = paperHarness();

        invokeEverySignature(harness);
        invokeEverySignature(harness);

        assertEquals(0, harness.foliaInvocations.value);
        assertEquals(16, harness.paperInvocations.value);
    }

    @Test
    void preservesPaperFallbackWhenAReflectiveCapabilityThrows() {
        Harness harness = foliaHarness(true);

        assertActive(harness.scheduler.runGlobal(NO_OP));
        assertActive(harness.scheduler.runGlobal(NO_OP));

        assertEquals(2, harness.foliaInvocations.value);
        assertEquals(2, harness.paperInvocations.value);
    }

    private static void invokeEverySignature(Harness harness) {
        assertActive(harness.scheduler.runGlobal(NO_OP));
        assertActive(harness.scheduler.runGlobalDelayed(NO_OP, 1L));
        assertActive(harness.scheduler.runGlobalTimer(NO_OP, 1L, 2L));
        assertActive(harness.scheduler.runRegion(harness.location, NO_OP));
        assertActive(harness.scheduler.runRegionDelayed(harness.location, NO_OP, 1L));
        assertActive(harness.scheduler.runRegionTimer(harness.location, NO_OP, 1L, 2L));
        assertActive(harness.scheduler.runEntity(harness.entity, NO_OP));
        assertActive(harness.scheduler.runEntityDelayed(harness.entity, NO_OP, 1L));
    }

    private static void assertActive(PluginTask task) {
        assertFalse(task.isCancelled());
    }

    private static Harness foliaHarness(boolean throwGlobal) {
        Counter foliaInvocations = new Counter();
        Counter paperInvocations = new Counter();
        BukkitScheduler bukkitScheduler = bukkitScheduler(paperInvocations);
        Object globalScheduler = schedulerProxy(
            capabilityReturnType(Server.class, "getGlobalRegionScheduler"),
            new Class<?>[] {Plugin.class, Consumer.class},
            foliaInvocations,
            throwGlobal
        );
        Object regionScheduler = schedulerProxy(
            capabilityReturnType(Server.class, "getRegionScheduler"),
            new Class<?>[] {Plugin.class, Location.class, Consumer.class},
            foliaInvocations,
            false
        );
        Object entityScheduler = schedulerProxy(
            capabilityReturnType(Entity.class, "getScheduler"),
            new Class<?>[] {Plugin.class, Consumer.class, Runnable.class},
            foliaInvocations,
            false
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
        Entity entity = proxy(
            Entity.class,
            (instance, method, arguments) -> {
                if (method.getName().equals("getScheduler") && method.getParameterCount() == 0) {
                    return entityScheduler;
                }
                return unsupported(instance, method, arguments);
            }
        );
        return harness(server, entity, foliaInvocations, paperInvocations);
    }

    private static Harness paperHarness() {
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
        Entity entity = proxy(
            Entity.class,
            (instance, method, arguments) -> {
                if (method.getName().equals("getScheduler") && method.getParameterCount() == 0) {
                    return null;
                }
                return unsupported(instance, method, arguments);
            }
        );
        return harness(server, entity, new Counter(), paperInvocations);
    }

    private static Harness harness(Server server, Entity entity, Counter foliaInvocations, Counter paperInvocations) {
        Plugin plugin = proxy(
            Plugin.class,
            (instance, method, arguments) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getServer" -> server;
                default -> unsupported(instance, method, arguments);
            }
        );
        World world = proxy(World.class, ServerSchedulerCapabilityCacheTest::unsupported);
        return new Harness(
            new ServerScheduler(plugin),
            entity,
            new Location(world, 8.0, 64.0, -8.0),
            world,
            foliaInvocations,
            paperInvocations
        );
    }

    private static Object schedulerProxy(
        Class<?> schedulerType,
        Class<?>[] runParameters,
        Counter invocations,
        boolean throwOnRun
    ) {
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
                    if (throwOnRun) {
                        throw new IllegalStateException("expected test failure");
                    }
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
            ServerSchedulerCapabilityCacheTest.class.getClassLoader(),
            interfaces,
            handler
        );
    }

    private static Object interfaceProxy(Class<?> type, InvocationHandler handler) {
        if (!type.isInterface()) {
            throw new IllegalStateException("Expected an interface capability: " + type.getName());
        }
        return Proxy.newProxyInstance(
            ServerSchedulerCapabilityCacheTest.class.getClassLoader(),
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
        Location location,
        World world,
        Counter foliaInvocations,
        Counter paperInvocations
    ) {
    }
}
