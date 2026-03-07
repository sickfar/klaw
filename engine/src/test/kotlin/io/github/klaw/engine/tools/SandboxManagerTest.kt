package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SandboxManagerTest {
    @TempDir
    lateinit var stateDir: File

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
            val manager = SandboxManager(config(keepAlive = false), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)
            manager.execute("echo 2", 30)

            assertEquals(2, docker.runCalls.size, "Expected 2 docker run calls for oneshot mode")
            assertEquals(0, docker.execCalls.size, "Expected 0 docker exec calls for oneshot mode")
            assertTrue(docker.runCalls.all { it.remove }, "Oneshot runs should use --rm")
        }

    @Test
    fun `keepAlive=true reuses container`() =
        runTest {
            val docker = FakeDockerClient()
            val manager =
                SandboxManager(config(keepAlive = true, maxExecutions = 10), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)
            manager.execute("echo 2", 30)

            assertEquals(1, docker.runCalls.size, "Expected 1 docker run call for keep-alive")
            assertEquals(2, docker.execCalls.size, "Expected 2 docker exec calls for keep-alive")
        }

    @Test
    fun `container recreated after maxExecutions`() =
        runTest {
            val docker = FakeDockerClient()
            val manager =
                SandboxManager(config(keepAlive = true, maxExecutions = 2), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)
            manager.execute("echo 2", 30)
            manager.execute("echo 3", 30)

            assertEquals(2, docker.runCalls.size, "Expected 2 docker run calls after maxExecutions")
            assertEquals(1, docker.stopCalls.size, "Expected 1 stop call when recreating container")
        }

    @Test
    fun `shutdown stops container`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = true), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)
            manager.shutdown()

            assertEquals(1, docker.stopCalls.size, "Expected 1 stop call on shutdown")
            assertEquals(1, docker.rmCalls.size, "Expected 1 rm call on shutdown")
        }

    @Test
    fun `shutdown with no container is no-op`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = true), docker, stateDir = stateDir.absolutePath)

            manager.shutdown()

            assertEquals(0, docker.stopCalls.size)
            assertEquals(0, docker.rmCalls.size)
        }

    @Suppress("SwallowedException")
    @Test
    fun `oneshot propagates docker unavailable exception`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextRunException = SandboxExecutionException.DockerUnavailable()
            val manager = SandboxManager(config(keepAlive = false), docker, stateDir = stateDir.absolutePath)

            try {
                manager.execute("echo 1", 30)
                fail("Should have thrown DockerUnavailable")
            } catch (e: SandboxExecutionException.DockerUnavailable) {
                // expected
            }
        }

    @Suppress("SwallowedException")
    @Test
    fun `oneshot propagates image not found exception`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextRunException = SandboxExecutionException.ImageNotFound("klaw-sandbox:latest")
            val manager = SandboxManager(config(keepAlive = false), docker, stateDir = stateDir.absolutePath)

            try {
                manager.execute("echo 1", 30)
                fail("Should have thrown ImageNotFound")
            } catch (e: SandboxExecutionException.ImageNotFound) {
                assertTrue(e.message!!.contains("klaw-sandbox:latest"))
            }
        }

    @Suppress("SwallowedException")
    @Test
    fun `keep-alive classifies OOM from exec result`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "Killed", exitCode = 137)
            val manager =
                SandboxManager(config(keepAlive = true, maxExecutions = 10), docker, stateDir = stateDir.absolutePath)

            try {
                manager.execute("python3 -c 'x = [0]*10**9'", 30)
                fail("Should have thrown OutOfMemory")
            } catch (e: SandboxExecutionException.OutOfMemory) {
                assertTrue(e.message!!.contains("memory"))
            }
        }

    @Suppress("SwallowedException")
    @Test
    fun `keep-alive classifies permission denied from exec result`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "permission denied", exitCode = 1)
            val manager =
                SandboxManager(config(keepAlive = true, maxExecutions = 10), docker, stateDir = stateDir.absolutePath)

            try {
                manager.execute("cat /etc/shadow", 30)
                fail("Should have thrown PermissionDenied")
            } catch (e: SandboxExecutionException.PermissionDenied) {
                // expected
            }
        }

    @Test
    fun `hostWorkspacePath used in volume mount when set`() =
        runTest {
            val docker = FakeDockerClient()
            val manager =
                SandboxManager(
                    config(keepAlive = false),
                    docker,
                    workspacePath = "/workspace",
                    hostWorkspacePath = "/home/pi/klaw-workspace",
                    stateDir = stateDir.absolutePath,
                )

            manager.execute("echo 1", 30)

            val volumes = docker.runCalls[0].volumes
            assertTrue(
                volumes.any { it.startsWith("/home/pi/klaw-workspace:/workspace") },
                "Expected host workspace path in volume mount, got: $volumes",
            )
        }

    @Test
    fun `fallback to workspacePath when hostWorkspacePath is null`() =
        runTest {
            val docker = FakeDockerClient()
            val manager =
                SandboxManager(
                    config(keepAlive = false),
                    docker,
                    workspacePath = "/workspace",
                    stateDir = stateDir.absolutePath,
                )

            manager.execute("echo 1", 30)

            val volumes = docker.runCalls[0].volumes
            assertTrue(
                volumes.any { it.startsWith("/workspace:/workspace") },
                "Expected workspacePath in volume mount, got: $volumes",
            )
        }

    @Test
    fun `keepAlive persists container ID to state file`() =
        runTest {
            val docker = FakeDockerClient()
            val manager =
                SandboxManager(config(keepAlive = true, maxExecutions = 10), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)

            val pidFile = File(stateDir, "sandbox.pid")
            assertTrue(pidFile.exists(), "sandbox.pid should exist after container creation")
            assertEquals("fake-container-id", pidFile.readText().trim())
        }

    @Test
    fun `shutdown deletes state file`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = true), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)
            manager.shutdown()

            val pidFile = File(stateDir, "sandbox.pid")
            assertFalse(pidFile.exists(), "sandbox.pid should be deleted after shutdown")
        }

    @Test
    fun `new manager recovers orphaned container from state file and destroys it`() =
        runTest {
            val pidFile = File(stateDir, "sandbox.pid")
            pidFile.writeText("orphaned-container-id")

            val docker = FakeDockerClient()
            val manager =
                SandboxManager(config(keepAlive = true, maxExecutions = 10), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)

            assertTrue(docker.stopCalls.contains("orphaned-container-id"), "Orphaned container should be stopped")
            assertTrue(docker.rmCalls.contains("orphaned-container-id"), "Orphaned container should be removed")
            assertEquals("fake-container-id", pidFile.readText().trim(), "New container ID should be persisted")
        }

    @Test
    fun `container recreated after maxExecutions deletes and recreates state file`() =
        runTest {
            val docker = FakeDockerClient()
            val manager =
                SandboxManager(config(keepAlive = true, maxExecutions = 2), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)
            manager.execute("echo 2", 30)
            manager.execute("echo 3", 30)

            val pidFile = File(stateDir, "sandbox.pid")
            assertTrue(pidFile.exists(), "sandbox.pid should exist after recreation")
        }

    @Test
    fun `oneshot mode does not create state file`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config(keepAlive = false), docker, stateDir = stateDir.absolutePath)

            manager.execute("echo 1", 30)

            val pidFile = File(stateDir, "sandbox.pid")
            assertFalse(pidFile.exists(), "sandbox.pid should not exist in oneshot mode")
        }
}
