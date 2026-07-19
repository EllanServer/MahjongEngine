package top.ellan.mahjong.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.momirealms.sparrow.reflection.method.SMethod0;
import net.momirealms.sparrow.reflection.method.SMethod1;
import net.momirealms.sparrow.reflection.method.SMethod2;
import net.momirealms.sparrow.reflection.method.SMethod3;
import net.momirealms.sparrow.reflection.method.SMethod4;
import net.momirealms.sparrow.reflection.method.SMethod5;
import net.momirealms.sparrow.reflection.method.SparrowMethod;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
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
    private static final ClassValue<GlobalSchedulerDispatchPlan> GLOBAL_SCHEDULER_DISPATCH_PLAN_CACHE =
        new ClassValue<>() {
            @Override
            protected GlobalSchedulerDispatchPlan computeValue(Class<?> type) {
                return new GlobalSchedulerDispatchPlan(
                    resolveMethod(type, RUN_GLOBAL),
                    resolveMethod(type, RUN_GLOBAL_DELAYED),
                    resolveMethod(type, RUN_GLOBAL_TIMER)
                );
            }
        };
    private static final ClassValue<RegionSchedulerDispatchPlan> REGION_SCHEDULER_DISPATCH_PLAN_CACHE =
        new ClassValue<>() {
            @Override
            protected RegionSchedulerDispatchPlan computeValue(Class<?> type) {
                return new RegionSchedulerDispatchPlan(
                    resolveMethod(type, RUN_REGION),
                    resolveMethod(type, RUN_REGION_DELAYED),
                    resolveMethod(type, RUN_REGION_TIMER)
                );
            }
        };
    private static final ClassValue<EntitySchedulerDispatchPlan> ENTITY_SCHEDULER_DISPATCH_PLAN_CACHE =
        new ClassValue<>() {
            @Override
            protected EntitySchedulerDispatchPlan computeValue(Class<?> type) {
                return new EntitySchedulerDispatchPlan(
                    resolveMethod(type, RUN_ENTITY),
                    resolveMethod(type, RUN_ENTITY_DELAYED)
                );
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

    /**
     * Each capability is published as one immutable entry so readers can never
     * observe a target from one resolution paired with another target's scheduler
     * or invocation plan. Null scheduler values intentionally negative-cache
     * unavailable Folia capabilities on standard Paper runtimes.
     */
    private volatile GlobalSchedulerCapability globalSchedulerCapability;
    private volatile RegionSchedulerCapability regionSchedulerCapability;
    private volatile EntitySchedulerCapability entitySchedulerCapability;
    private volatile BukkitSchedulerCapability bukkitSchedulerCapability;

    public ServerScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public PluginTask runGlobal(Runnable runnable) {
        if (runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        return this.runGlobalValidated(this.plugin.getServer(), runnable);
    }

    public PluginTask runGlobalDelayed(Runnable runnable, long delayTicks) {
        if (runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        return this.runGlobalDelayedValidated(this.plugin.getServer(), runnable, delayTicks);
    }

    public PluginTask runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        return this.runGlobalTimerValidated(this.plugin.getServer(), runnable, delayTicks, periodTicks);
    }

    public PluginTask runRegion(Location location, Runnable runnable) {
        if (location == null || location.getWorld() == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Server server = this.plugin.getServer();
        RegionSchedulerCapability capability = this.regionSchedulerCapability(server);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeRun(
                capability.scheduler(),
                this.plugin,
                location,
                taskConsumer(runnable)
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobalValidated(server, runnable);
    }

    public PluginTask runRegionDelayed(Location location, Runnable runnable, long delayTicks) {
        if (location == null || location.getWorld() == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Server server = this.plugin.getServer();
        RegionSchedulerCapability capability = this.regionSchedulerCapability(server);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeDelayed(
                capability.scheduler(),
                this.plugin,
                location,
                taskConsumer(runnable),
                delayTicks
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobalDelayedValidated(server, runnable, delayTicks);
    }

    public PluginTask runRegionTimer(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        Server server = this.plugin.getServer();
        RegionSchedulerCapability capability = this.regionSchedulerCapability(server);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeTimer(
                capability.scheduler(),
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
        return this.runGlobalTimerValidated(server, runnable, delayTicks, periodTicks);
    }

    public PluginTask runEntity(Entity entity, Runnable runnable) {
        if (entity == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        EntitySchedulerCapability capability = this.entitySchedulerCapability(entity);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeRun(
                capability.scheduler(),
                this.plugin,
                taskConsumer(runnable),
                NO_OP_RUNNABLE
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobalValidated(this.plugin.getServer(), runnable);
    }

    public PluginTask runEntityDelayed(Entity entity, Runnable runnable, long delayTicks) {
        if (entity == null || runnable == null || !this.isPluginEnabled()) {
            return NO_OP_TASK;
        }
        EntitySchedulerCapability capability = this.entitySchedulerCapability(entity);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeDelayed(
                capability.scheduler(),
                this.plugin,
                taskConsumer(runnable),
                NO_OP_RUNNABLE,
                delayTicks
            );
            if (task != null) {
                return task;
            }
        }
        return this.runGlobalDelayedValidated(this.plugin.getServer(), runnable, delayTicks);
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> teleport(Entity entity, Location location) {
        if (entity == null || location == null || !this.isPluginEnabled()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        MethodResolution teleportAsync = resolveMethod(entity.getClass(), TELEPORT_ASYNC);
        if (teleportAsync.isAvailable()) {
            try {
                Object result = teleportAsync.invoke1(entity, location);
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

    private PluginTask runGlobalValidated(Server server, Runnable runnable) {
        GlobalSchedulerCapability capability = this.globalSchedulerCapability(server);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeRun(
                capability.scheduler(),
                this.plugin,
                taskConsumer(runnable)
            );
            if (task != null) {
                return task;
            }
        }
        return wrap(this.bukkitScheduler(server).runTask(this.plugin, runnable));
    }

    private PluginTask runGlobalDelayedValidated(Server server, Runnable runnable, long delayTicks) {
        GlobalSchedulerCapability capability = this.globalSchedulerCapability(server);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeDelayed(
                capability.scheduler(),
                this.plugin,
                taskConsumer(runnable),
                delayTicks
            );
            if (task != null) {
                return task;
            }
        }
        return wrap(this.bukkitScheduler(server).runTaskLater(this.plugin, runnable, delayTicks));
    }

    private PluginTask runGlobalTimerValidated(Server server, Runnable runnable, long delayTicks, long periodTicks) {
        GlobalSchedulerCapability capability = this.globalSchedulerCapability(server);
        if (capability.scheduler() != null) {
            PluginTask task = capability.dispatchPlan().invokeTimer(
                capability.scheduler(),
                this.plugin,
                taskConsumer(runnable),
                delayTicks,
                periodTicks
            );
            if (task != null) {
                return task;
            }
        }
        return wrap(this.bukkitScheduler(server).runTaskTimer(this.plugin, runnable, delayTicks, periodTicks));
    }

    private GlobalSchedulerCapability globalSchedulerCapability(Server server) {
        GlobalSchedulerCapability capability = this.globalSchedulerCapability;
        if (capability != null && capability.target() == server) {
            return capability;
        }
        Object scheduler = invokeNoArgs(server, GET_GLOBAL_REGION_SCHEDULER);
        GlobalSchedulerDispatchPlan dispatchPlan = scheduler == null
            ? null
            : GLOBAL_SCHEDULER_DISPATCH_PLAN_CACHE.get(scheduler.getClass());
        capability = new GlobalSchedulerCapability(server, scheduler, dispatchPlan);
        this.globalSchedulerCapability = capability;
        return capability;
    }

    private RegionSchedulerCapability regionSchedulerCapability(Server server) {
        RegionSchedulerCapability capability = this.regionSchedulerCapability;
        if (capability != null && capability.target() == server) {
            return capability;
        }
        Object scheduler = invokeNoArgs(server, GET_REGION_SCHEDULER);
        RegionSchedulerDispatchPlan dispatchPlan = scheduler == null
            ? null
            : REGION_SCHEDULER_DISPATCH_PLAN_CACHE.get(scheduler.getClass());
        capability = new RegionSchedulerCapability(server, scheduler, dispatchPlan);
        this.regionSchedulerCapability = capability;
        return capability;
    }

    private EntitySchedulerCapability entitySchedulerCapability(Entity entity) {
        EntitySchedulerCapability capability = this.entitySchedulerCapability;
        if (capability != null && capability.target() == entity) {
            return capability;
        }
        Object scheduler = invokeNoArgs(entity, GET_ENTITY_SCHEDULER);
        EntitySchedulerDispatchPlan dispatchPlan = scheduler == null
            ? null
            : ENTITY_SCHEDULER_DISPATCH_PLAN_CACHE.get(scheduler.getClass());
        capability = new EntitySchedulerCapability(entity, scheduler, dispatchPlan);
        this.entitySchedulerCapability = capability;
        return capability;
    }

    private BukkitScheduler bukkitScheduler(Server server) {
        BukkitSchedulerCapability capability = this.bukkitSchedulerCapability;
        if (capability != null && capability.target() == server) {
            return capability.scheduler();
        }
        BukkitScheduler scheduler = server.getScheduler();
        this.bukkitSchedulerCapability = new BukkitSchedulerCapability(server, scheduler);
        return scheduler;
    }

    private boolean isPluginEnabled() {
        return this.plugin.isEnabled();
    }

    private static Object invokeNoArgs(Object target, MethodSignature signature) {
        if (target == null) {
            return null;
        }
        MethodResolution resolution = resolveMethod(target.getClass(), signature);
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return resolution.invoke0(target);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static PluginTask invokeSchedulerTask(
        Object scheduler,
        MethodResolution resolution,
        Object argument0,
        Object argument1
    ) {
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return wrap(resolution.invoke2(scheduler, argument0, argument1));
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static PluginTask invokeSchedulerTask(
        Object scheduler,
        MethodResolution resolution,
        Object argument0,
        Object argument1,
        Object argument2
    ) {
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return wrap(resolution.invoke3(scheduler, argument0, argument1, argument2));
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static PluginTask invokeSchedulerTask(
        Object scheduler,
        MethodResolution resolution,
        Object argument0,
        Object argument1,
        Object argument2,
        Object argument3
    ) {
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return wrap(resolution.invoke4(scheduler, argument0, argument1, argument2, argument3));
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static PluginTask invokeSchedulerTask(
        Object scheduler,
        MethodResolution resolution,
        Object argument0,
        Object argument1,
        Object argument2,
        Object argument3,
        Object argument4
    ) {
        if (!resolution.isAvailable()) {
            return null;
        }
        try {
            return wrap(resolution.invoke5(scheduler, argument0, argument1, argument2, argument3, argument4));
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

    private record GlobalSchedulerDispatchPlan(
        MethodResolution runMethod,
        MethodResolution delayedMethod,
        MethodResolution timerMethod
    ) {
        private PluginTask invokeRun(Object scheduler, Plugin plugin, Consumer<Object> consumer) {
            return invokeSchedulerTask(scheduler, this.runMethod, plugin, consumer);
        }

        private PluginTask invokeDelayed(Object scheduler, Plugin plugin, Consumer<Object> consumer, long delayTicks) {
            return invokeSchedulerTask(scheduler, this.delayedMethod, plugin, consumer, delayTicks);
        }

        private PluginTask invokeTimer(
            Object scheduler,
            Plugin plugin,
            Consumer<Object> consumer,
            long delayTicks,
            long periodTicks
        ) {
            return invokeSchedulerTask(scheduler, this.timerMethod, plugin, consumer, delayTicks, periodTicks);
        }
    }

    private record RegionSchedulerDispatchPlan(
        MethodResolution runMethod,
        MethodResolution delayedMethod,
        MethodResolution timerMethod
    ) {
        private PluginTask invokeRun(
            Object scheduler,
            Plugin plugin,
            Location location,
            Consumer<Object> consumer
        ) {
            return invokeSchedulerTask(scheduler, this.runMethod, plugin, location, consumer);
        }

        private PluginTask invokeDelayed(
            Object scheduler,
            Plugin plugin,
            Location location,
            Consumer<Object> consumer,
            long delayTicks
        ) {
            return invokeSchedulerTask(scheduler, this.delayedMethod, plugin, location, consumer, delayTicks);
        }

        private PluginTask invokeTimer(
            Object scheduler,
            Plugin plugin,
            Location location,
            Consumer<Object> consumer,
            long delayTicks,
            long periodTicks
        ) {
            return invokeSchedulerTask(
                scheduler,
                this.timerMethod,
                plugin,
                location,
                consumer,
                delayTicks,
                periodTicks
            );
        }
    }

    private record EntitySchedulerDispatchPlan(MethodResolution runMethod, MethodResolution delayedMethod) {
        private PluginTask invokeRun(
            Object scheduler,
            Plugin plugin,
            Consumer<Object> consumer,
            Runnable retired
        ) {
            return invokeSchedulerTask(scheduler, this.runMethod, plugin, consumer, retired);
        }

        private PluginTask invokeDelayed(
            Object scheduler,
            Plugin plugin,
            Consumer<Object> consumer,
            Runnable retired,
            long delayTicks
        ) {
            return invokeSchedulerTask(scheduler, this.delayedMethod, plugin, consumer, retired, delayTicks);
        }
    }

    private record GlobalSchedulerCapability(
        Server target,
        Object scheduler,
        GlobalSchedulerDispatchPlan dispatchPlan
    ) {
    }

    private record RegionSchedulerCapability(
        Server target,
        Object scheduler,
        RegionSchedulerDispatchPlan dispatchPlan
    ) {
    }

    private record EntitySchedulerCapability(
        Entity target,
        Object scheduler,
        EntitySchedulerDispatchPlan dispatchPlan
    ) {
    }

    private record BukkitSchedulerCapability(Server target, BukkitScheduler scheduler) {
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
            return resolution.invoke0(task);
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

    private static final class MethodResolution {
        private static final MethodResolution UNAVAILABLE = new MethodResolution(
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        private final Method method;
        private final SMethod0 invoker0;
        private final SMethod1 invoker1;
        private final SMethod2 invoker2;
        private final SMethod3 invoker3;
        private final SMethod4 invoker4;
        private final SMethod5 invoker5;

        private MethodResolution(
            Method method,
            SMethod0 invoker0,
            SMethod1 invoker1,
            SMethod2 invoker2,
            SMethod3 invoker3,
            SMethod4 invoker4,
            SMethod5 invoker5
        ) {
            this.method = method;
            this.invoker0 = invoker0;
            this.invoker1 = invoker1;
            this.invoker2 = invoker2;
            this.invoker3 = invoker3;
            this.invoker4 = invoker4;
            this.invoker5 = invoker5;
        }

        private static MethodResolution available(Method method) {
            SMethod0 invoker0 = null;
            SMethod1 invoker1 = null;
            SMethod2 invoker2 = null;
            SMethod3 invoker3 = null;
            SMethod4 invoker4 = null;
            SMethod5 invoker5 = null;
            try {
                SparrowMethod sparrowMethod = SparrowMethod.of(method);
                switch (method.getParameterCount()) {
                    case 0 -> invoker0 = sparrowMethod.asm$0();
                    case 1 -> invoker1 = sparrowMethod.asm$1();
                    case 2 -> invoker2 = sparrowMethod.asm$2();
                    case 3 -> invoker3 = sparrowMethod.asm$3();
                    case 4 -> invoker4 = sparrowMethod.asm$4();
                    case 5 -> invoker5 = sparrowMethod.asm$5();
                    default -> {
                    }
                }
            } catch (RuntimeException | LinkageError exception) {
                // Some generated or strongly encapsulated runtime classes reject hidden invoker generation.
            }
            return new MethodResolution(method, invoker0, invoker1, invoker2, invoker3, invoker4, invoker5);
        }

        private Method method() {
            return this.method;
        }

        private boolean isAvailable() {
            return this.method != null;
        }

        private Object invoke0(Object target) throws IllegalAccessException {
            this.requireAvailable();
            if (this.invoker0 != null) {
                return this.invoker0.invoke(target);
            }
            return this.invokeReflectively(target);
        }

        private Object invoke1(Object target, Object argument0) throws IllegalAccessException {
            this.requireAvailable();
            if (this.invoker1 != null) {
                return this.invoker1.invoke(target, argument0);
            }
            return this.invokeReflectively(target, argument0);
        }

        private Object invoke2(Object target, Object argument0, Object argument1) throws IllegalAccessException {
            this.requireAvailable();
            if (this.invoker2 != null) {
                return this.invoker2.invoke(target, argument0, argument1);
            }
            return this.invokeReflectively(target, argument0, argument1);
        }

        private Object invoke3(Object target, Object argument0, Object argument1, Object argument2)
            throws IllegalAccessException {
            this.requireAvailable();
            if (this.invoker3 != null) {
                return this.invoker3.invoke(target, argument0, argument1, argument2);
            }
            return this.invokeReflectively(target, argument0, argument1, argument2);
        }

        private Object invoke4(
            Object target,
            Object argument0,
            Object argument1,
            Object argument2,
            Object argument3
        ) throws IllegalAccessException {
            this.requireAvailable();
            if (this.invoker4 != null) {
                return this.invoker4.invoke(target, argument0, argument1, argument2, argument3);
            }
            return this.invokeReflectively(target, argument0, argument1, argument2, argument3);
        }

        private Object invoke5(
            Object target,
            Object argument0,
            Object argument1,
            Object argument2,
            Object argument3,
            Object argument4
        ) throws IllegalAccessException {
            this.requireAvailable();
            if (this.invoker5 != null) {
                return this.invoker5.invoke(target, argument0, argument1, argument2, argument3, argument4);
            }
            return this.invokeReflectively(target, argument0, argument1, argument2, argument3, argument4);
        }

        private void requireAvailable() {
            if (this.method == null) {
                throw new IllegalStateException("Cannot invoke an unavailable method");
            }
        }

        private Object invokeReflectively(Object target, Object... arguments) throws IllegalAccessException {
            try {
                return this.method.invoke(target, arguments);
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
