package top.ellan.mahjong.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.RuleId;

class FairRuleExecutorTest {
    @Test
    void onePackCannotConsumeMoreThanHalfTheWorkers() throws Exception {
        try (FairRuleExecutor executor = new FairRuleExecutor(4, 32, "fair-rule-test")) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch riichiStarted = new CountDownLatch(2);
            CountDownLatch mcrStarted = new CountDownLatch(2);
            AtomicInteger riichiActive = new AtomicInteger();
            AtomicInteger riichiMaximum = new AtomicInteger();
            List<java.util.concurrent.CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                futures.add(
                        executor.submit(
                                new RuleId("riichi"),
                                () -> {
                                    int active = riichiActive.incrementAndGet();
                                    riichiMaximum.accumulateAndGet(active, Math::max);
                                    riichiStarted.countDown();
                                    await(release);
                                    riichiActive.decrementAndGet();
                                    return 1;
                                }));
            }
            for (int index = 0; index < 2; index++) {
                futures.add(
                        executor.submit(
                                new RuleId("mcr"),
                                () -> {
                                    mcrStarted.countDown();
                                    await(release);
                                    return 1;
                                }));
            }

            assertTrue(riichiStarted.await(2, TimeUnit.SECONDS));
            assertTrue(mcrStarted.await(2, TimeUnit.SECONDS));
            assertEquals(2, riichiMaximum.get());
            release.countDown();
            for (var future : futures) {
                assertEquals(1, future.get(2, TimeUnit.SECONDS));
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
