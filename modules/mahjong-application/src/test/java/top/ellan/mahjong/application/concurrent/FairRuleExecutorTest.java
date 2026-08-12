package top.ellan.mahjong.application.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.RuleExecutionBudget;
import top.ellan.mahjong.spi.RuleId;

class FairRuleExecutorTest {
    @Test
    void singleRulePackUsesTheWholePool() throws Exception {
        try (FairRuleExecutor executor = new FairRuleExecutor(4, 32, "fair-rule-test")) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch poolExhausted = new CountDownLatch(1);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            List<java.util.concurrent.CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                futures.add(
                        executor.submit(
                                new RuleId("riichi"),
                                () -> {
                                    int now = active.incrementAndGet();
                                    if (maximum.accumulateAndGet(now, Math::max) >= 4) {
                                        poolExhausted.countDown();
                                    }
                                    await(release);
                                    active.decrementAndGet();
                                    return 1;
                                }));
            }

            assertTrue(poolExhausted.await(2, TimeUnit.SECONDS));
            assertEquals(4, maximum.get());
            release.countDown();
            for (var future : futures) {
                assertEquals(1, future.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void twoRulePacksShareThePoolWhenBothAreActive() throws Exception {
        try (FairRuleExecutor executor = new FairRuleExecutor(4, 32, "fair-rule-test")) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch bothPrimed = new CountDownLatch(2);
            // Prime both packs first so the per-pack ceiling is workerCount / 2 while the
            // heavy load arrives; both primed operations stay active until release.
            executor.submit(
                    new RuleId("riichi"),
                    () -> {
                        bothPrimed.countDown();
                        await(release);
                        return 1;
                    });
            executor.submit(
                    new RuleId("mcr"),
                    () -> {
                        bothPrimed.countDown();
                        await(release);
                        return 1;
                    });
            assertTrue(bothPrimed.await(2, TimeUnit.SECONDS));

            AtomicInteger riichiActive = new AtomicInteger();
            AtomicInteger riichiMaximum = new AtomicInteger();
            AtomicInteger mcrActive = new AtomicInteger();
            AtomicInteger mcrMaximum = new AtomicInteger();
            List<java.util.concurrent.CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(
                        executor.submit(
                                new RuleId("riichi"),
                                () -> {
                                    int now = riichiActive.incrementAndGet();
                                    riichiMaximum.accumulateAndGet(now, Math::max);
                                    await(release);
                                    riichiActive.decrementAndGet();
                                    return 1;
                                }));
                futures.add(
                        executor.submit(
                                new RuleId("mcr"),
                                () -> {
                                    int now = mcrActive.incrementAndGet();
                                    mcrMaximum.accumulateAndGet(now, Math::max);
                                    await(release);
                                    mcrActive.decrementAndGet();
                                    return 1;
                                }));
            }
            Thread.sleep(200);

            assertTrue(riichiMaximum.get() <= 2);
            assertTrue(mcrMaximum.get() <= 2);
            release.countDown();
            for (var future : futures) {
                assertEquals(1, future.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void releaseSignalsAtMostOneWaiterSoQueuesAlwaysProgress() throws Exception {
        try (FairRuleExecutor executor = new FairRuleExecutor(2, 32, "fair-rule-test")) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch poolExhausted = new CountDownLatch(1);
            AtomicInteger active = new AtomicInteger();
            List<java.util.concurrent.CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(
                        executor.submit(
                                new RuleId("riichi"),
                                () -> {
                                    if (active.incrementAndGet() >= 2) {
                                        poolExhausted.countDown();
                                    }
                                    await(release);
                                    active.decrementAndGet();
                                    return 1;
                                }));
            }

            assertTrue(poolExhausted.await(2, TimeUnit.SECONDS));
            release.countDown();
            for (var future : futures) {
                assertEquals(1, future.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void renewingWorkersReplacesEveryThreadAndKeepsAcceptingWork() throws Exception {
        try (FairRuleExecutor executor = new FairRuleExecutor(2, 32, "fair-rule-renew")) {
            // Record which threads served work before and after the renewal. Unloading a rule pack
            // relies on the old threads actually going away, since a surviving thread could still
            // hold a thread local whose value keeps the pack's classloader reachable.
            java.util.Set<String> before = threadNames(executor, 4);

            executor.renewWorkers();

            java.util.Set<String> after = java.util.Set.of();
            for (int attempt = 0; attempt < 20; attempt++) {
                after = threadNames(executor, 4);
                if (java.util.Collections.disjoint(before, after)) {
                    break;
                }
                Thread.sleep(25L);
            }
            assertTrue(
                    java.util.Collections.disjoint(before, after),
                    "renewed pool still uses old threads: " + before + " vs " + after);
            assertEquals(2, after.size(), "the pool must keep its configured width");
        }
    }

    @Test
    void onePackCannotConsumeAnotherPacksQueueQuota() throws Exception {
        try (FairRuleExecutor executor =
                new FairRuleExecutor(1, 4, 1, "fair-rule-quota")) {
            RuleId noisy = new RuleId("riichi");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var running = executor.submit(noisy, () -> {
                entered.countDown();
                await(release);
                return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = executor.submit(noisy, () -> 2);

            assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    () -> executor.submit(noisy, () -> 3));
            var other = executor.submit(new RuleId("mcr"), () -> 4);

            release.countDown();
            assertEquals(1, running.get(2, TimeUnit.SECONDS));
            assertEquals(2, queued.get(2, TimeUnit.SECONDS));
            assertEquals(4, other.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutIsolatesOnlyTheOffendingPackUntilReset() throws Exception {
        try (FairRuleExecutor executor =
                new FairRuleExecutor(2, 16, 8, "fair-rule-timeout")) {
            RuleId faulty = new RuleId("riichi");
            var timedOut = executor.submit(
                    faulty,
                    Duration.ofMillis(25),
                    () -> {
                        while (true) {
                            RuleExecutionBudget.checkpoint();
                        }
                    });

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> timedOut.get(2, TimeUnit.SECONDS));
            assertThrows(
                    RulePackCircuitOpenException.class,
                    () -> executor.submit(faulty, () -> 1));
            assertEquals(
                    2,
                    executor.submit(new RuleId("mcr"), () -> 2)
                            .get(2, TimeUnit.SECONDS));

            executor.resetCircuit(faulty);
            assertEquals(3, executor.submit(faulty, () -> 3).get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void threeConsecutiveProviderFailuresOpenTheCircuit() throws Exception {
        try (FairRuleExecutor executor =
                new FairRuleExecutor(1, 16, 8, "fair-rule-failure")) {
            RuleId ruleId = new RuleId("sichuan");
            for (int failure = 0; failure < 3; failure++) {
                var future = executor.submit(ruleId, () -> {
                    throw new IllegalStateException("broken provider");
                });
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> future.get(2, TimeUnit.SECONDS));
            }

            assertThrows(
                    RulePackCircuitOpenException.class,
                    () -> executor.submit(ruleId, () -> 1));
        }
    }

    private static java.util.Set<String> threadNames(FairRuleExecutor executor, int tasks)
            throws Exception {
        // Hold every worker at once so each one reports its own name exactly once.
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        java.util.Set<String> names = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<java.util.concurrent.CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int index = 0; index < tasks; index++) {
            futures.add(
                    executor.submit(
                            new RuleId("riichi"),
                            () -> {
                                names.add(Thread.currentThread().getName());
                                entered.countDown();
                                await(release);
                                return 1;
                            }));
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        release.countDown();
        for (var future : futures) {
            future.get(2, TimeUnit.SECONDS);
        }
        return java.util.Set.copyOf(names);
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
