package top.ellan.mahjong.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

final class ServerSchedulerReflectionCacheTest {
    private static final Runnable NO_OP = () -> {
    };

    @Test
    void cachesAvailableAndUnavailableMethodResolutionsPerRuntimeClass() {
        assertEquals(0, ServerScheduler.cachedMethodResolutionCount(ReflectionProbe.class));

        Method first = ServerScheduler.cachedMethod(ReflectionProbe.class, "accept", String.class);
        Method second = ServerScheduler.cachedMethod(ReflectionProbe.class, "accept", String.class);

        assertSame(first, second);
        assertEquals(1, ServerScheduler.cachedMethodResolutionCount(ReflectionProbe.class));

        assertNull(ServerScheduler.cachedMethod(ReflectionProbe.class, "unsupported"));
        assertNull(ServerScheduler.cachedMethod(ReflectionProbe.class, "unsupported"));
        assertEquals(2, ServerScheduler.cachedMethodResolutionCount(ReflectionProbe.class));
    }

    @Test
    void invokesPaperSchedulerAndReusesItsCapabilityByServerIdentity() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        AtomicInteger executions = new AtomicInteger();

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(globalScheduler.run(any(Plugin.class), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ScheduledTask> consumer = invocation.getArgument(1, Consumer.class);
            consumer.accept(scheduledTask);
            return scheduledTask;
        });

        ServerScheduler scheduler = new ServerScheduler(plugin);
        PluginTask first = scheduler.runGlobal(executions::incrementAndGet);
        PluginTask second = scheduler.runGlobal(executions::incrementAndGet);

        assertEquals(2, executions.get());
        verify(server, times(1)).getGlobalRegionScheduler();
        assertFalse(first.isCancelled());
        assertFalse(second.isCancelled());
        when(scheduledTask.isCancelled()).thenReturn(true);
        assertTrue(first.isCancelled());
        first.cancel();
        verify(scheduledTask).cancel();
    }

    @Test
    void negativelyCachesUnavailableCapabilitiesOnPaper() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        Entity entity = mock(Entity.class);
        BukkitScheduler bukkitScheduler = mock(BukkitScheduler.class);
        BukkitTask bukkitTask = mock(BukkitTask.class);
        Location location = new Location(world, 0.0, 64.0, 0.0);

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(bukkitScheduler);
        when(bukkitScheduler.runTask(eq(plugin), any(Runnable.class))).thenReturn(bukkitTask);

        ServerScheduler scheduler = new ServerScheduler(plugin);
        scheduler.runGlobal(NO_OP);
        scheduler.runGlobal(NO_OP);
        scheduler.runRegion(location, NO_OP);
        scheduler.runRegion(location, NO_OP);
        scheduler.runEntity(entity, NO_OP);
        scheduler.runEntity(entity, NO_OP);

        verify(server, times(1)).getGlobalRegionScheduler();
        verify(server, times(1)).getRegionScheduler();
        verify(entity, times(1)).getScheduler();
        verify(bukkitScheduler, times(6)).runTask(eq(plugin), any(Runnable.class));
    }

    @Test
    void invalidatesCapabilitiesWhenTargetIdentityChanges() {
        Plugin globalPlugin = mock(Plugin.class);
        Server firstServer = mock(Server.class);
        Server secondServer = mock(Server.class);
        GlobalRegionScheduler firstGlobal = mock(GlobalRegionScheduler.class);
        GlobalRegionScheduler secondGlobal = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);

        when(globalPlugin.isEnabled()).thenReturn(true);
        when(globalPlugin.getServer()).thenReturn(firstServer, firstServer, secondServer, secondServer);
        when(firstServer.getGlobalRegionScheduler()).thenReturn(firstGlobal);
        when(secondServer.getGlobalRegionScheduler()).thenReturn(secondGlobal);
        when(firstGlobal.run(any(Plugin.class), any())).thenReturn(task);
        when(secondGlobal.run(any(Plugin.class), any())).thenReturn(task);

        ServerScheduler globalScheduler = new ServerScheduler(globalPlugin);
        globalScheduler.runGlobal(NO_OP);
        globalScheduler.runGlobal(NO_OP);
        globalScheduler.runGlobal(NO_OP);
        globalScheduler.runGlobal(NO_OP);

        verify(firstServer, times(1)).getGlobalRegionScheduler();
        verify(secondServer, times(1)).getGlobalRegionScheduler();

        Plugin regionPlugin = mock(Plugin.class);
        RegionScheduler firstRegion = mock(RegionScheduler.class);
        RegionScheduler secondRegion = mock(RegionScheduler.class);
        World world = mock(World.class);
        Location location = new Location(world, 0.0, 64.0, 0.0);

        when(regionPlugin.isEnabled()).thenReturn(true);
        when(regionPlugin.getServer()).thenReturn(firstServer, firstServer, secondServer, secondServer);
        when(firstServer.getRegionScheduler()).thenReturn(firstRegion);
        when(secondServer.getRegionScheduler()).thenReturn(secondRegion);
        when(firstRegion.run(any(Plugin.class), any(Location.class), any())).thenReturn(task);
        when(secondRegion.run(any(Plugin.class), any(Location.class), any())).thenReturn(task);

        ServerScheduler regionScheduler = new ServerScheduler(regionPlugin);
        regionScheduler.runRegion(location, NO_OP);
        regionScheduler.runRegion(location, NO_OP);
        regionScheduler.runRegion(location, NO_OP);
        regionScheduler.runRegion(location, NO_OP);

        verify(firstServer, times(1)).getRegionScheduler();
        verify(secondServer, times(1)).getRegionScheduler();

        Plugin entityPlugin = mock(Plugin.class);
        Entity firstEntity = mock(Entity.class);
        Entity secondEntity = mock(Entity.class);
        EntityScheduler firstEntityScheduler = mock(EntityScheduler.class);
        EntityScheduler secondEntityScheduler = mock(EntityScheduler.class);

        when(entityPlugin.isEnabled()).thenReturn(true);
        when(firstEntity.getScheduler()).thenReturn(firstEntityScheduler);
        when(secondEntity.getScheduler()).thenReturn(secondEntityScheduler);
        when(firstEntityScheduler.run(any(Plugin.class), any(), any(Runnable.class))).thenReturn(task);
        when(secondEntityScheduler.run(any(Plugin.class), any(), any(Runnable.class))).thenReturn(task);

        ServerScheduler entityScheduler = new ServerScheduler(entityPlugin);
        entityScheduler.runEntity(firstEntity, NO_OP);
        entityScheduler.runEntity(firstEntity, NO_OP);
        entityScheduler.runEntity(secondEntity, NO_OP);
        entityScheduler.runEntity(secondEntity, NO_OP);
        entityScheduler.runEntity(firstEntity, NO_OP);

        verify(firstEntity, times(2)).getScheduler();
        verify(secondEntity, times(1)).getScheduler();
    }

    @Test
    void neverRoutesConcurrentEntityCallsThroughAnotherEntityScheduler() throws InterruptedException {
        Plugin plugin = mock(Plugin.class);
        Entity firstEntity = mock(Entity.class);
        Entity secondEntity = mock(Entity.class);
        EntityScheduler firstEntityScheduler = mock(EntityScheduler.class);
        EntityScheduler secondEntityScheduler = mock(EntityScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        AtomicInteger wrongRoutes = new AtomicInteger();

        when(plugin.isEnabled()).thenReturn(true);
        when(firstEntity.getScheduler()).thenReturn(firstEntityScheduler);
        when(secondEntity.getScheduler()).thenReturn(secondEntityScheduler);
        when(firstEntityScheduler.run(any(Plugin.class), any(), any(Runnable.class))).thenAnswer(invocation -> {
            if (!Thread.currentThread().getName().equals("scheduler-entity-first")) {
                wrongRoutes.incrementAndGet();
            }
            return task;
        });
        when(firstEntityScheduler.runDelayed(any(Plugin.class), any(), any(Runnable.class), anyLong()))
            .thenAnswer(invocation -> {
                if (!Thread.currentThread().getName().equals("scheduler-entity-first")) {
                    wrongRoutes.incrementAndGet();
                }
                return task;
            });
        when(secondEntityScheduler.run(any(Plugin.class), any(), any(Runnable.class))).thenAnswer(invocation -> {
            if (!Thread.currentThread().getName().equals("scheduler-entity-second")) {
                wrongRoutes.incrementAndGet();
            }
            return task;
        });
        when(secondEntityScheduler.runDelayed(any(Plugin.class), any(), any(Runnable.class), anyLong()))
            .thenAnswer(invocation -> {
                if (!Thread.currentThread().getName().equals("scheduler-entity-second")) {
                    wrongRoutes.incrementAndGet();
                }
                return task;
            });

        ServerScheduler scheduler = new ServerScheduler(plugin);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstThread = new Thread(
            () -> runEntityLoop(scheduler, firstEntity, ready, start, done, failure),
            "scheduler-entity-first"
        );
        Thread secondThread = new Thread(
            () -> runEntityLoop(scheduler, secondEntity, ready, start, done, failure),
            "scheduler-entity-second"
        );

        firstThread.start();
        secondThread.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.interrupt();
            secondThread.interrupt();
            firstThread.join();
            secondThread.join();
        }

        if (failure.get() != null) {
            throw new AssertionError("Concurrent scheduler invocation failed", failure.get());
        }
        assertEquals(0, wrongRoutes.get());
    }

    @Test
    void doesNotFallbackAndScheduleTwiceWhenPaperSchedulerThrows() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        AtomicInteger schedulerCalls = new AtomicInteger();

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(globalScheduler.run(any(Plugin.class), any())).thenAnswer(invocation -> {
            schedulerCalls.incrementAndGet();
            throw new IllegalStateException("scheduler failed after entry");
        });

        assertThrows(IllegalStateException.class, () -> new ServerScheduler(plugin).runGlobal(NO_OP));

        assertEquals(1, schedulerCalls.get());
        verify(server, never()).getScheduler();
    }

    private static void runEntityLoop(
        ServerScheduler scheduler,
        Entity entity,
        CountDownLatch ready,
        CountDownLatch start,
        CountDownLatch done,
        AtomicReference<Throwable> failure
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent scheduler start timed out");
            }
            for (int iteration = 0; iteration < 20_000; iteration++) {
                scheduler.runEntity(entity, NO_OP);
                scheduler.runEntityDelayed(entity, NO_OP, 1L);
            }
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        } finally {
            done.countDown();
        }
    }

    private static final class ReflectionProbe {
        public void accept(String value) {
        }
    }
}
