package io.github.klaw.engine.agent

import io.github.klaw.common.config.AgentConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class AgentRegistryTest {
    private fun stubContext(agentId: String): AgentContext =
        AgentContext(
            agentId = agentId,
            agentConfig = AgentConfig(workspace = "/tmp/test-$agentId"),
        )

    @Test
    fun `get returns registered context`() {
        val registry = AgentRegistry()
        val ctx = stubContext("main")
        registry.register("main", ctx)
        assertSame(ctx, registry.get("main"))
    }

    @Test
    fun `get throws for unknown agent`() {
        val registry = AgentRegistry()
        val ex = assertThrows<IllegalArgumentException> { registry.get("nope") }
        assertTrue(ex.message!!.contains("nope"))
    }

    @Test
    fun `getOrNull returns null for unknown agent`() {
        val registry = AgentRegistry()
        assertNull(registry.getOrNull("nope"))
    }

    @Test
    fun `all returns all registered contexts`() {
        val registry = AgentRegistry()
        val a = stubContext("a")
        val b = stubContext("b")
        registry.register("a", a)
        registry.register("b", b)
        val all = registry.all()
        assertEquals(2, all.size)
        assertTrue(all.contains(a))
        assertTrue(all.contains(b))
    }

    @Test
    fun `shutdown shuts down all agents`() {
        val registry = AgentRegistry()
        var shutdownCount = 0
        val ctx1 = stubContext("a")
        val ctx2 = stubContext("b")
        // We'll verify shutdown was called by checking the registry is empty after
        registry.register("a", ctx1)
        registry.register("b", ctx2)
        registry.shutdown()
        // After shutdown, registry should be empty
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `concurrent register and get is safe`() {
        val registry = AgentRegistry()
        val threads = 10
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val errors = mutableListOf<Throwable>()

        repeat(threads) { i ->
            Thread {
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    val ctx = stubContext("agent-$i")
                    registry.register("agent-$i", ctx)
                    assertSame(ctx, registry.get("agent-$i"))
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(errors.isEmpty(), "Errors: $errors")
        assertEquals(threads, registry.all().size)
    }
}
