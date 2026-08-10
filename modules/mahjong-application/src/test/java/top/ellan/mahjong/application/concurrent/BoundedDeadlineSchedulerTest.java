package top.ellan.mahjong.application.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

    @Test
    void cancelledDeadlinesCompactTheHeapAndKeepPendingTasksAccurate() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool()) {
            BoundedDeadlineScheduler scheduler =
                    new BoundedDeadlineScheduler(8, executor, "deadline-cancel-test");
            List<Cancellable> tasks = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                tasks.add(scheduler.schedule(executions::incrementAndGet, Duration.ofDays(1)));
            }
            // Cancel entries that are not the heap head so the timer thread stays asleep and
            // only the lazy-cancel counter moves. Four cancelled entries reach the compaction
            // threshold (capacity / 2), rebuilding the heap around the two survivors.
            for (int index = 1; index <= 4; index++) {
                assertTrue(tasks.get(index).cancel());
            }
            assertEquals(2, scheduler.pendingTasks());
            // Cancellation never blocks new schedules: the new work actually runs.
            for (int index = 0; index < 2; index++) {
                scheduler.schedule(executions::incrementAndGet, Duration.ofMillis(1));
            }
            Thread.sleep(250);
            assertEquals(2, executions.get());
            assertEquals(2, scheduler.pendingTasks());
            scheduler.close();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelledDeadlinesDoNotBlockNewSchedules() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool()) {
            BoundedDeadlineScheduler scheduler =
                    new BoundedDeadlineScheduler(4, executor, "deadline-fill-test");
            List<Cancellable> tasks = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                tasks.add(scheduler.schedule(executions::incrementAndGet, Duration.ofMillis(1)));
            }
            // Cancel everything: the live count drops to zero, so new schedules are accepted
            // even though the heap still physically holds the cancelled entries.
            for (Cancellable task : tasks) {
                assertTrue(task.cancel());
            }
            for (int index = 0; index < 4; index++) {
                scheduler.schedule(executions::incrementAndGet, Duration.ofMillis(1));
            }
            Thread.sleep(200);
            assertEquals(4, executions.get());
            scheduler.close();
            executor.shutdownNow();
        }
    }
}
