package top.ellan.mahjong.application.concurrent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedDeadlineSchedulerTest {
    @Test
    void transientTargetSaturationDoesNotDropTheDeadline() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch executed = new CountDownLatch(1);
        java.util.concurrent.Executor rejectOnce =
                task -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new RejectedExecutionException("full");
                    }
                    task.run();
                };

        try (BoundedDeadlineScheduler scheduler =
                new BoundedDeadlineScheduler(1, rejectOnce, "deadline-retry-test")) {
            scheduler.schedule(executed::countDown, Duration.ZERO);

            assertTrue(executed.await(2, TimeUnit.SECONDS));
            assertTrue(attempts.get() >= 2);
        }
    }
}
