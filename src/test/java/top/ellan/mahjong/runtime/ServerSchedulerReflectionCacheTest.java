package top.ellan.mahjong.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

final class ServerSchedulerReflectionCacheTest {
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
    void invokesPaperSchedulerAndWrapsItsTaskThroughCachedMethods() {
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

        PluginTask task = new ServerScheduler(plugin).runGlobal(executions::incrementAndGet);

        assertEquals(1, executions.get());
        assertFalse(task.isCancelled());
        when(scheduledTask.isCancelled()).thenReturn(true);
        assertTrue(task.isCancelled());
        task.cancel();
        verify(scheduledTask).cancel();
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

        assertThrows(IllegalStateException.class, () -> new ServerScheduler(plugin).runGlobal(() -> {
        }));

        assertEquals(1, schedulerCalls.get());
        verify(server, never()).getScheduler();
    }

    private static final class ReflectionProbe {
        public void accept(String value) {
        }
    }
}
