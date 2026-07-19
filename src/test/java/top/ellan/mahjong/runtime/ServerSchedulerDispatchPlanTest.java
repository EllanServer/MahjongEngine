package top.ellan.mahjong.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

final class ServerSchedulerDispatchPlanTest {
    private static final Runnable NO_OP = () -> {
    };

    @Test
    void dispatchesEveryFoliaMethodShapeThroughCachedPlans() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        Location location = new Location(world, 8.0, 64.0, -8.0);
        Entity entity = mock(Entity.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        RegionScheduler region = mock(RegionScheduler.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(server.getRegionScheduler()).thenReturn(region);
        when(entity.getScheduler()).thenReturn(entityScheduler);
        when(global.run(eq(plugin), any())).thenReturn(task);
        when(global.runDelayed(eq(plugin), any(), anyLong())).thenReturn(task);
        when(global.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(task);
        when(region.run(eq(plugin), eq(location), any())).thenReturn(task);
        when(region.runDelayed(eq(plugin), eq(location), any(), anyLong())).thenReturn(task);
        when(region.runAtFixedRate(eq(plugin), eq(location), any(), anyLong(), anyLong())).thenReturn(task);
        when(entityScheduler.run(eq(plugin), any(), any(Runnable.class))).thenReturn(task);
        when(entityScheduler.runDelayed(eq(plugin), any(), any(Runnable.class), anyLong())).thenReturn(task);

        ServerScheduler scheduler = new ServerScheduler(plugin);

        assertFalse(scheduler.runGlobal(NO_OP).isCancelled());
        assertFalse(scheduler.runGlobalDelayed(NO_OP, 11L).isCancelled());
        assertFalse(scheduler.runGlobalTimer(NO_OP, 13L, 17L).isCancelled());
        assertFalse(scheduler.runRegion(location, NO_OP).isCancelled());
        assertFalse(scheduler.runRegionDelayed(location, NO_OP, 19L).isCancelled());
        assertFalse(scheduler.runRegionTimer(location, NO_OP, 23L, 29L).isCancelled());
        assertFalse(scheduler.runEntity(entity, NO_OP).isCancelled());
        assertFalse(scheduler.runEntityDelayed(entity, NO_OP, 31L).isCancelled());

        verify(server, times(1)).getGlobalRegionScheduler();
        verify(server, times(1)).getRegionScheduler();
        verify(entity, times(1)).getScheduler();
        verify(global).run(eq(plugin), any());
        verify(global).runDelayed(eq(plugin), any(), eq(11L));
        verify(global).runAtFixedRate(eq(plugin), any(), eq(13L), eq(17L));
        verify(region).run(eq(plugin), eq(location), any());
        verify(region).runDelayed(eq(plugin), eq(location), any(), eq(19L));
        verify(region).runAtFixedRate(eq(plugin), eq(location), any(), eq(23L), eq(29L));
        verify(entityScheduler).run(eq(plugin), any(), any(Runnable.class));
        verify(entityScheduler).runDelayed(eq(plugin), any(), any(Runnable.class), eq(31L));
    }

    @Test
    void missingRegionAndEntityCapabilitiesFallBackToGlobalFoliaScheduler() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        Location location = new Location(world, 0.0, 64.0, 0.0);
        Entity entity = mock(Entity.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        AtomicInteger executions = new AtomicInteger();

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(global.run(eq(plugin), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ScheduledTask> consumer = invocation.getArgument(1, Consumer.class);
            consumer.accept(task);
            return task;
        });

        ServerScheduler scheduler = new ServerScheduler(plugin);
        scheduler.runRegion(location, executions::incrementAndGet);
        scheduler.runEntity(entity, executions::incrementAndGet);

        assertEquals(2, executions.get());
        verify(server, times(1)).getRegionScheduler();
        verify(entity, times(1)).getScheduler();
        verify(server, times(1)).getGlobalRegionScheduler();
        verify(global, times(2)).run(eq(plugin), any());
        verify(server, never()).getScheduler();
    }

    @Test
    void cachesBukkitFallbackByServerIdentityAndInvalidatesOnServerChange() {
        Plugin plugin = mock(Plugin.class);
        Server firstServer = mock(Server.class);
        Server secondServer = mock(Server.class);
        BukkitScheduler firstScheduler = mock(BukkitScheduler.class);
        BukkitScheduler secondScheduler = mock(BukkitScheduler.class);
        BukkitTask firstTask = mock(BukkitTask.class);
        BukkitTask secondTask = mock(BukkitTask.class);
        World world = mock(World.class);
        Location location = new Location(world, 0.0, 64.0, 0.0);
        Entity entity = mock(Entity.class);
        AtomicReference<Server> currentServer = new AtomicReference<>(firstServer);

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenAnswer(invocation -> currentServer.get());
        when(firstServer.getScheduler()).thenReturn(firstScheduler);
        when(secondServer.getScheduler()).thenReturn(secondScheduler);
        when(firstScheduler.runTask(eq(plugin), any(Runnable.class))).thenReturn(firstTask);
        when(secondScheduler.runTask(eq(plugin), any(Runnable.class))).thenReturn(secondTask);

        ServerScheduler scheduler = new ServerScheduler(plugin);
        scheduler.runGlobal(NO_OP);
        scheduler.runRegion(location, NO_OP);
        scheduler.runEntity(entity, NO_OP);
        currentServer.set(secondServer);
        scheduler.runGlobal(NO_OP);
        scheduler.runRegion(location, NO_OP);
        scheduler.runEntity(entity, NO_OP);

        verify(plugin, times(6)).getServer();
        verify(firstServer, times(1)).getScheduler();
        verify(secondServer, times(1)).getScheduler();
        verify(firstScheduler, times(3)).runTask(eq(plugin), any(Runnable.class));
        verify(secondScheduler, times(3)).runTask(eq(plugin), any(Runnable.class));
    }

    @Test
    void nullFoliaTaskDoesNotScheduleAgainThroughBukkit() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(global.run(eq(plugin), any())).thenReturn(null);

        PluginTask task = new ServerScheduler(plugin).runGlobal(NO_OP);

        assertTrue(task.isCancelled());
        verify(global, times(1)).run(eq(plugin), any());
        verify(server, never()).getScheduler();
    }

    @Test
    void regionSchedulerFailureNeverFallsBackOrSchedulesTwice() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        Location location = new Location(world, 0.0, 64.0, 0.0);
        RegionScheduler region = mock(RegionScheduler.class);

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getRegionScheduler()).thenReturn(region);
        when(region.run(eq(plugin), eq(location), any())).thenThrow(new IllegalStateException("region failed"));

        assertThrows(IllegalStateException.class, () -> new ServerScheduler(plugin).runRegion(location, NO_OP));

        verify(region, times(1)).run(eq(plugin), eq(location), any());
        verify(server, never()).getGlobalRegionScheduler();
        verify(server, never()).getScheduler();
    }

    @Test
    void entitySchedulerFailureNeverFallsBackOrSchedulesTwice() {
        Plugin plugin = mock(Plugin.class);
        Entity entity = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);

        when(plugin.isEnabled()).thenReturn(true);
        when(entity.getScheduler()).thenReturn(entityScheduler);
        when(entityScheduler.run(eq(plugin), any(), any(Runnable.class)))
            .thenThrow(new IllegalStateException("entity failed"));

        assertThrows(IllegalStateException.class, () -> new ServerScheduler(plugin).runEntity(entity, NO_OP));

        verify(entityScheduler, times(1)).run(eq(plugin), any(), any(Runnable.class));
        verify(plugin, never()).getServer();
    }

    @Test
    void neverRoutesConcurrentGlobalCallsThroughAnotherServersScheduler() throws InterruptedException {
        Plugin plugin = mock(Plugin.class);
        Server firstServer = mock(Server.class);
        Server secondServer = mock(Server.class);
        GlobalRegionScheduler firstScheduler = mock(GlobalRegionScheduler.class);
        GlobalRegionScheduler secondScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        ThreadLocal<Server> threadServer = new ThreadLocal<>();
        AtomicInteger wrongRoutes = new AtomicInteger();

        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenAnswer(invocation -> threadServer.get());
        when(firstServer.getGlobalRegionScheduler()).thenReturn(firstScheduler);
        when(secondServer.getGlobalRegionScheduler()).thenReturn(secondScheduler);
        when(firstScheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            if (!Thread.currentThread().getName().equals("scheduler-server-first")) {
                wrongRoutes.incrementAndGet();
            }
            return task;
        });
        when(secondScheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            if (!Thread.currentThread().getName().equals("scheduler-server-second")) {
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
            () -> runGlobalLoop(scheduler, threadServer, firstServer, ready, start, done, failure),
            "scheduler-server-first"
        );
        Thread secondThread = new Thread(
            () -> runGlobalLoop(scheduler, threadServer, secondServer, ready, start, done, failure),
            "scheduler-server-second"
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

    private static void runGlobalLoop(
        ServerScheduler scheduler,
        ThreadLocal<Server> threadServer,
        Server server,
        CountDownLatch ready,
        CountDownLatch start,
        CountDownLatch done,
        AtomicReference<Throwable> failure
    ) {
        threadServer.set(server);
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent scheduler start timed out");
            }
            for (int iteration = 0; iteration < 20_000; iteration++) {
                scheduler.runGlobal(NO_OP);
            }
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        } finally {
            threadServer.remove();
            done.countDown();
        }
    }
}
