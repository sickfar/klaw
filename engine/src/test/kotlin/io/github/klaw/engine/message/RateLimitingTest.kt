package io.github.klaw.engine.message

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class RateLimitingTest {
    @Test
    fun `maxConcurrentLlm limit respected - no more than N tasks run concurrently`() =
        runTest {
            val maxConcurrent = 2
            val semaphore = Semaphore(maxConcurrent)
            val concurrentCount = AtomicInteger(0)
            val maxObserved = AtomicInteger(0)
            val totalTasks = 6

            val jobs =
                (1..totalTasks).map {
                    launch {
                        semaphore.withPermit {
                            val current = concurrentCount.incrementAndGet()
                            maxObserved.getAndUpdate { prev -> maxOf(prev, current) }
                            delay(10)
                            concurrentCount.decrementAndGet()
                        }
                    }
                }
            jobs.forEach { it.join() }

            assertTrue(
                maxObserved.get() <= maxConcurrent,
                "At most $maxConcurrent tasks should run concurrently, observed: ${maxObserved.get()}",
            )
        }

    @Test
    fun `all requests complete even when semaphore limit is reached`() =
        runTest {
            val maxConcurrent = 2
            val semaphore = Semaphore(maxConcurrent)
            val completedCount = AtomicInteger(0)
            val totalTasks = 5

            val jobs =
                (1..totalTasks).map {
                    launch {
                        semaphore.withPermit {
                            delay(5)
                            completedCount.incrementAndGet()
                        }
                    }
                }
            jobs.forEach { it.join() }

            assertEquals(
                totalTasks,
                completedCount.get(),
                "All $totalTasks requests should complete even when semaphore is exhausted",
            )
        }
}
