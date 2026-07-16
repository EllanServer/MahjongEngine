package top.ellan.mahjong.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.momirealms.sparrow.reflection.method.SMethod;
import net.momirealms.sparrow.reflection.method.SparrowMethod;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class ServerScheduler {
    private static final MethodSignature GET_GLOBAL_REGION_SCHEDULER = signature("getGlobalRegionScheduler");
    private static final MethodSignature GET_REGION_SCHEDULER = signature("getRegionScheduler");
    private static final MethodSignature GET_ENTITY_SCHEDULER = signature("getScheduler");
    private static final MethodSignature RUN_GLOBAL = signature("run", Plugin.class, Consumer.class);
    private static final MethodSignature RUN_GLOBAL_DELAYED = signature(
        "runDelayed",
        Plugin.class,
        Consumer.class,
        long.class
    );
    private static final MethodSignature RUN_GLOBAL_TIMER = signature(
        "runAtFixedRate",
        Plugin.class,
        Consumer.class,
        long.class,
        long.class
    );
    private static final MethodSignature RUN_REGION = signature(
        "run",
        Plugin.class,
        Location.class,
        Consumer.class
    );
    private static final MethodSignature RUN_REGION_DELAYED = signature(
        "runDelayed",
        Plugin.class,
        Location.class,
        Consumer.class,
        long.class
    );
    private static final MethodSignature RUN_REGION_TIMER = signature(
        "runAtFixedRate",
        Plugin.class,
        Location.class,
        Consumer.class,
        long.class,
        long.class
    );
    private static final MethodSignature RUN_ENTITY = signature(
        "run",
        Plugin.class,
        Consumer.class,
        Runnable.class
    );
    private static final MethodSignature RUN_ENTITY_DELAYED = signature(
        "runDelayed",
        Plugin.class,
        Consumer.class,
        Runnable.class,
        long.class
    );
    private static final MethodSignature TELEPORT_ASYNC = signature("teleportAsync", Location.class);
    private static final MethodSignature CANCEL_TASK = signature("cancel");
    private static final MethodSignature IS_TASK_CANCELLED = signature("isCancelled");
    private static final ClassValue<Cache<MethodSignature, MethodResolution>> METHOD_CACHE =
        new ClassValue<>() {
            @Override
            protected Cache<MethodSignature, MethodResolution> computeValue(Class<?> type) {
                return Caffeine.newBuilder().build();
            }
        };

    private static final PluginTask NO_OP_TASK = new PluginTask() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    private final Plugin plugin;

    public ServerScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public PluginTask runGlobal(Runnable runnable) {
        if (runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.globalRegionScheduler();
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_GLOBAL,
                this.plugin,
                taskConsumer(runnable)
            );
            if (task != null) {
                return task;
            }
        }
        return wrap(this.plugin.getServer().getScheduler().runTask(this.plugin, runnable));
    }

    public PluginTask runGlobalDelayed(Runnable runnable, long delayTicks) {
        if (runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.globalRegionScheduler();
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_GLOBAL_DELAYED,
                this.plugin,
                taskConsumer(runnable),
                delayTicks
            );
            if (task != null) {
                return task;
            }
        }
        return wrap(this.plugin.getServer().getScheduler().runTaskLater(this.plugin, runnable, delayTicks));
    }

    public PluginTask runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.globalRegionScheduler();
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_GLOBAL_TIMER,
                this.plugin,
                taskConsumer(runnable),
                delayTicks,
                periodTicks
            );
            if (task != null) {
                return task;
            }
        }
        return wrap(this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, runnable, delayTicks, periodTicks));
    }

    public PluginTask runRegion(Location location, Runnable runnable) {
        if (location == null || location.getWorld() == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.regionScheduler();
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_REGION,
                this.plugin,
                location,
                taskConsumer(runnable)
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobal(runnable);
    }

    public PluginTask runRegionDelayed(Location location, Runnable runnable, long delayTicks) {
        if (location == null || location.getWorld() == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.regionScheduler();
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_REGION_DELAYED,
                this.plugin,
                location,
                taskConsumer(runnable),
                delayTicks
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobalDelayed(runnable, delayTicks);
    }

    public PluginTask runRegionTimer(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.regionScheduler();
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_REGION_TIMER,
                this.plugin,
                location,
                taskConsumer(runnable),
                delayTicks,
                periodTicks
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobalTimer(runnable, delayTicks, periodTicks);
    }

    public PluginTask runEntity(Entity entity, Runnable runnable) {
        if (entity == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.entityScheduler(entity);
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_ENTITY,
                this.plugin,
                taskConsumer(runnable),
                NO_OP_RUNNABLE
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobal(runnable);
    }

    public PluginTask runEntityDelayed(Entity entity, Runnable runnable, long delayTicks) {
        if (entity == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Object scheduler = this.entityScheduler(entity);
        if (scheduler != null) {
            PluginTask task = this.invokeSchedulerTask(
                scheduler,
                RUN_ENTITY_DELAYED,
                this.plugin,
                taskConsumer(runnable),
                NO_OP_RUNNABLE,
                delayTicks
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobalDelayed(runnable, delayTicks);
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> teleport(Entity entity, Location location) {
        if (entity == null || location == null || !this.isPluginEnabled()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        MethodResolution teleportAsync = resolveMethod(entity.getClass(), TELEPORT_ASYNC);
        if (teleportAsync.isAvailable()) {
            try {
                Object result = teleportAsync.invoke(entity, location);
                if (result instanceof CompletableFuture<?> future) {
                    return (CompletableFuture<Boolean>) future;
                }
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "teleportAsync returned an unsupported result"
                ));
            } catch (IllegalAccessException exception) {
                // The method exists but this runtime does not allow reflective access; use Bukkit below.
            } catch (RuntimeException | LinkageError exception) {
                // The target method was entered (or its direct invoker failed). Never invoke teleport twice.
                return CompletableFuture.failedFuture(exception);
            }
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        this.runEntity(entity, () -> {
            try {
                future.complete(entity.teleport(location));
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    public PluginTask removeEntity(Entity entity) {
        return this.removeEntity(entity, 0L);
    }

    public PluginTask removeEntity(Entity entity, long delayTicks) {
        if (entity == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Runnable removeTask = () -> {
            if (!entity.isDead() && entity.isValid()) {
                entity.remove();
            }
        };
        if (delayTicks <= 0L) {
            return this.runEntity(entity, removeTask);
        }
        return this.runEntityDelayed(entity, removeTask, delayTicks);
    }

    private Object globalRegionScheduler() {
        return this.invokeNoArgs(this.plugin.getServer(), GET_GLOBAL_REGION_SCHEDULER);
    }

    private Object regionScheduler() {
        return this.invokeNoArgs(this.plugin.getServer(), GET_REGION_SCHEDULER);
    }

    private Object entityScheduler(Entity entity) {
        return this.invokeNoArgs(entity, GET_ENTITY_SCHEDULER);
    }

    private boolean isPluginEnabled() {
        return this.plugin.isEnabled();
    }

    private Object invokeNoArgs(Object target, MethodSignature signature) {
        if (target == null) {
            return null;
        }
        MethodResolution resolution = resolveMethod(target.getClass(), signature);
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return resolution.invoke(target);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private PluginTask invokeSchedulerTask(Object scheduler, MethodSignature signature, Object... args) {
        MethodResolution resolution = resolveMethod(scheduler.getClass(), signature);
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return wrap(resolution.invoke(scheduler, args));
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static Consumer<Object> taskConsumer(Runnable runnable) {
        return task -> runnable.run();
    }

    private static PluginTask wrap(BukkitTask task) {
        return task == null ? NO_OP_TASK : new BukkitTaskHandle(task);
    }

    private static PluginTask wrap(Object task) {
        return task == null ? NO_OP_TASK : new ScheduledTaskHandle(task);
    }

    private record BukkitTaskHandle(BukkitTask task) implements PluginTask {
        @Override
        public void cancel() {
            this.task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return this.task.isCancelled();
        }
    }

    private record ScheduledTaskHandle(Object task) implements PluginTask {
        @Override
        public void cancel() {
            invokeTaskMethod(this.task, CANCEL_TASK);
        }

        @Override
        public boolean isCancelled() {
            Object result = invokeTaskMethod(this.task, IS_TASK_CANCELLED);
            return result instanceof Boolean cancelled && cancelled;
        }
    }

    private static Object invokeTaskMethod(Object task, MethodSignature signature) {
        if (task == null) {
            return null;
        }
        MethodResolution resolution = resolveMethod(task.getClass(), signature);
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return resolution.invoke(task);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    static Method cachedMethod(Class<?> targetClass, String methodName, Class<?>... parameterTypes) {
        return resolveMethod(targetClass, signature(methodName, parameterTypes)).method();
    }

    static int cachedMethodResolutionCount(Class<?> targetClass) {
        return Math.toIntExact(METHOD_CACHE.get(targetClass).estimatedSize());
    }

    private static MethodResolution resolveMethod(Class<?> targetClass, MethodSignature signature) {
        return METHOD_CACHE.get(targetClass).get(signature, key -> resolveMethodUncached(targetClass, key));
    }

    private static MethodResolution resolveMethodUncached(Class<?> targetClass, MethodSignature signature) {
        try {
            Method method = targetClass.getMethod(signature.name(), signature.parameterArray());
            try {
                method.trySetAccessible();
            } catch (RuntimeException | LinkageError ignored) {
                // Public methods may still be invocable; an access failure is handled without re-resolving.
            }
            return MethodResolution.available(method);
        } catch (NoSuchMethodException | RuntimeException | LinkageError exception) {
            return MethodResolution.UNAVAILABLE;
        }
    }

    private static MethodSignature signature(String name, Class<?>... parameterTypes) {
        return new MethodSignature(name, List.of(parameterTypes));
    }

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        private MethodSignature {
            Objects.requireNonNull(name, "name");
            parameterTypes = List.copyOf(parameterTypes);
        }

        private Class<?>[] parameterArray() {
            return this.parameterTypes.toArray(Class<?>[]::new);
        }
    }

    private record MethodResolution(Method method, SMethod invoker) {
        private static final MethodResolution UNAVAILABLE = new MethodResolution(null, null);

        private static MethodResolution available(Method method) {
            SMethod invoker = null;
            try {
                invoker = SparrowMethod.of(method).asm();
            } catch (RuntimeException | LinkageError exception) {
                // Some generated or strongly encapsulated runtime classes reject hidden invoker generation.
            }
            return new MethodResolution(method, invoker);
        }

        private boolean isAvailable() {
            return this.method != null;
        }

        private Object invoke(Object target, Object... args) throws IllegalAccessException {
            if (this.method == null) {
                throw new IllegalStateException("Cannot invoke an unavailable method");
            }
            if (this.invoker != null) {
                return this.invoker.invoke(target, args);
            }
            try {
                return this.method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Cached reflective method invocation failed", cause);
            }
        }
    }

    private static final Runnable NO_OP_RUNNABLE = () -> {
    };
}
