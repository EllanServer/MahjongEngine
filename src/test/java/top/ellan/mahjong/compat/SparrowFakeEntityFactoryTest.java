package top.ellan.mahjong.compat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

final class SparrowFakeEntityFactoryTest {
    @Test
    void itemAndTextAllocationsShareOneCreationLock() throws Exception {
        SparrowHeart heart = mock(SparrowHeart.class);
        Location location = mock(Location.class);
        CountDownLatch firstAllocationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAllocation = new CountDownLatch(1);
        CountDownLatch secondTaskStarted = new CountDownLatch(1);
        when(heart.createFakeItemDisplay(any(Location.class))).thenAnswer(invocation -> {
            firstAllocationEntered.countDown();
            releaseFirstAllocation.await(2L, TimeUnit.SECONDS);
            return mock(FakeItemDisplay.class);
        });
        when(heart.createFakeTextDisplay(any(Location.class))).thenReturn(mock(FakeTextDisplay.class));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> item = workers.submit(() -> SparrowFakeEntityFactory.createItemDisplay(heart, location));
            assertTrue(firstAllocationEntered.await(1L, TimeUnit.SECONDS));
            Future<?> text = workers.submit(() -> {
                secondTaskStarted.countDown();
                return SparrowFakeEntityFactory.createTextDisplay(heart, location);
            });
            assertTrue(secondTaskStarted.await(1L, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () -> text.get(100L, TimeUnit.MILLISECONDS));

            releaseFirstAllocation.countDown();
            item.get(1L, TimeUnit.SECONDS);
            text.get(1L, TimeUnit.SECONDS);
        } finally {
            releaseFirstAllocation.countDown();
            workers.shutdownNow();
        }
    }
}
