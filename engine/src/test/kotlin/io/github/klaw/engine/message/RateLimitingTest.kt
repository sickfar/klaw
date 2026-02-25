package io.github.klaw.engine.message

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class RateLimitingTest {
    @Test
    fun `maxConcurrentLlm limit respected - no more than N tasks run concurrently`() =
        runTest {
            val maxConcurrent = 2
            val limiter = PriorityLlmLimiter(maxConcurrent)
            val concurrentCount = AtomicInteger(0)
            val maxObserved = AtomicInteger(0)
            val totalTasks = 6

            val jobs =
                (1..totalTasks).map {
                    launch {
                        limiter.withInteractivePermit {
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
    fun `all requests complete even when limit is reached`() =
        runTest {
            val maxConcurrent = 2
            val limiter = PriorityLlmLimiter(maxConcurrent)
            val completedCount = AtomicInteger(0)
            val totalTasks = 5

            val jobs =
                (1..totalTasks).map {
                    launch {
                        limiter.withInteractivePermit {
                            delay(5)
                            completedCount.incrementAndGet()
                        }
                    }
                }
            jobs.forEach { it.join() }

            assertEquals(
                totalTasks,
                completedCount.get(),
                "All $totalTasks requests should complete even when limit is exhausted",
            )
        }

    @Test
    fun `interactive requests processed before subagents when contended`() =
        runTest {
            val limiter = PriorityLlmLimiter(1) // only 1 permit
            val order = Collections.synchronizedList(mutableListOf<String>())

            // Occupy the single permit
            val blocker =
                launch {
                    limiter.withInteractivePermit {
                        // Wait until subagent and interactive requests are queued
                        delay(50)
                    }
                }

            // Give blocker time to acquire
            delay(10)

            // Queue a subagent request first
            val subagentJob =
                launch {
                    limiter.withSubagentPermit {
                        order.add("subagent")
                    }
                }

            // Then queue an interactive request
            delay(5) // ensure subagent enqueues first
            val interactiveJob =
                launch {
                    limiter.withInteractivePermit {
                        order.add("interactive")
                    }
                }

            blocker.join()
            interactiveJob.join()
            subagentJob.join()

            assertEquals(
                listOf("interactive", "subagent"),
                order,
                "Interactive should be served before subagent even though subagent was queued first",
            )
        }
}
