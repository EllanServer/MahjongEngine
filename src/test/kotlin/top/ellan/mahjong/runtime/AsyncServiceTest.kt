package top.ellan.mahjong.runtime

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsyncServiceTest {
    @Test
    fun `retry policy has deterministic capped exponential delays`() {
        val policy = AsyncService.RetryPolicy(6, 25L, 100L)

        assertEquals(
            listOf(0L, 25L, 50L, 100L, 100L, 100L),
            (1..6).map(policy::delayMillisBeforeAttempt),
        )
    }

    @Test
    fun `retry operation succeeds without overlapping attempts`() {
        val service = AsyncService(Logger.getLogger("AsyncServiceTest-success"))
        val attempts = AtomicInteger()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        try {
            val result =
                service.executeWithRetry(
                    "eventual-success",
                    {
                        val active = inFlight.incrementAndGet()
                        maxInFlight.accumulateAndGet(active, Math::max)
                        try {
                            if (attempts.incrementAndGet() < 3) {
                                throw IOException("transient")
                            }
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    },
                    AsyncService.RetryPolicy(4, 5L, 10L),
                )

            result.get(2L, TimeUnit.SECONDS)
            assertEquals(3, attempts.get())
            assertEquals(1, maxInFlight.get())
        } finally {
            service.close()
        }
    }

    @Test
    fun `retry operation stops after the configured attempt limit`() {
        val service = AsyncService(Logger.getLogger("AsyncServiceTest-exhausted"))
        val attempts = AtomicInteger()
        try {
            val result =
                service.executeWithRetry(
                    "always-fails",
                    {
                        attempts.incrementAndGet()
                        throw IOException("still unavailable")
                    },
                    AsyncService.RetryPolicy(3, 1L, 2L),
                )

            assertFailsWith<ExecutionException> { result.get(2L, TimeUnit.SECONDS) }
            assertEquals(3, attempts.get())
        } finally {
            service.close()
        }
    }

    @Test
    fun `quiescence waits for ordinary operations to finish`() {
        val service = AsyncService(Logger.getLogger("AsyncServiceTest-quiescence-ordinary"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            service.execute("blocked") {
                started.countDown()
                release.await()
            }
            started.await(1L, TimeUnit.SECONDS)

            assertEquals(false, service.awaitQuiescence(1L))
            release.countDown()
            assertEquals(true, service.awaitQuiescence(2L))
        } finally {
            release.countDown()
            service.close()
        }
    }

    @Test
    fun `cpu operations use the bounded worker pool and participate in quiescence`() {
        val service = AsyncService(Logger.getLogger("AsyncServiceTest-cpu"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var workerName = ""
        try {
            service.executeCpu("blocked-cpu") {
                workerName = Thread.currentThread().name
                started.countDown()
                release.await()
            }
            assertTrue(started.await(1L, TimeUnit.SECONDS))

            assertTrue(workerName.startsWith("MahjongPaper-CPU-"))
            assertEquals(false, service.awaitQuiescence(1L))
            release.countDown()
            assertEquals(true, service.awaitQuiescence(2L))
        } finally {
            release.countDown()
            service.close()
        }
    }

    @Test
    fun `quiescence includes retry backoff`() {
        val service = AsyncService(Logger.getLogger("AsyncServiceTest-quiescence-retry"))
        val firstAttempt = CountDownLatch(1)
        val attempts = AtomicInteger()
        try {
            service.executeWithRetry(
                "backoff",
                {
                    if (attempts.incrementAndGet() == 1) {
                        firstAttempt.countDown()
                        throw IOException("retry me")
                    }
                },
                AsyncService.RetryPolicy(2, 1_200L, 1_200L),
            )
            firstAttempt.await(1L, TimeUnit.SECONDS)

            assertEquals(false, service.awaitQuiescence(1L))
            assertEquals(true, service.awaitQuiescence(2L))
            assertEquals(2, attempts.get())
        } finally {
            service.close()
        }
    }
}
