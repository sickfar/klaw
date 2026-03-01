package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SandboxManagerTest {
    private fun config(
        keepAlive: Boolean = true,
        maxExecutions: Int = 3,
        idleTimeoutMin: Int = 10,
    ) = CodeExecutionConfig(
        dockerImage = "klaw-sandbox:latest",
        timeout = 30,
        keepAlive = keepAlive,
        keepAliveMaxExecutions = maxExecutions,
        keepAliveIdleTimeoutMin = idleTimeoutMin,
    )

    @Test
    fun `keepAlive=false uses oneshot for each execution`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = false), docker)

            manager.execute("python", "print(1)", 30)
            manager.execute("python", "print(2)", 30)

            assertEquals(2, docker.runCalls.size, "Expected 2 docker run calls for oneshot mode")
            assertEquals(0, docker.execCalls.size, "Expected 0 docker exec calls for oneshot mode")
            // Oneshot containers get --rm, so no stop/rm needed
            assertTrue(docker.runCalls.all { it.remove }, "Oneshot runs should use --rm")
        }

    @Test
    fun `keepAlive=true reuses container`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = true, maxExecutions = 10), docker)

            manager.execute("python", "print(1)", 30)
            manager.execute("python", "print(2)", 30)

            assertEquals(1, docker.runCalls.size, "Expected 1 docker run call for keep-alive")
            assertEquals(2, docker.execCalls.size, "Expected 2 docker exec calls for keep-alive")
        }

    @Test
    fun `container recreated after maxExecutions`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = true, maxExecutions = 2), docker)

            manager.execute("python", "print(1)", 30)
            manager.execute("python", "print(2)", 30)
            // maxExecutions reached, next call should recreate
            manager.execute("python", "print(3)", 30)

            assertEquals(2, docker.runCalls.size, "Expected 2 docker run calls after maxExecutions")
            assertEquals(1, docker.stopCalls.size, "Expected 1 stop call when recreating container")
        }

    @Test
    fun `shutdown stops container`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = true), docker)

            manager.execute("python", "print(1)", 30)
            manager.shutdown()

            assertEquals(1, docker.stopCalls.size, "Expected 1 stop call on shutdown")
            assertEquals(1, docker.rmCalls.size, "Expected 1 rm call on shutdown")
        }

    @Test
    fun `shutdown with no container is no-op`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = true), docker)

            manager.shutdown()

            assertEquals(0, docker.stopCalls.size)
            assertEquals(0, docker.rmCalls.size)
        }
}
