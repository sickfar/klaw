package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SandboxExecToolTest {
    private val config =
        CodeExecutionConfig(
            dockerImage = "klaw-sandbox:latest",
            timeout = 30,
            keepAlive = true,
            keepAliveMaxExecutions = 50,
        )

    @Test
    fun `executes bash code via sandbox manager`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "hello\n", stderr = "", exitCode = 0)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("echo hello")

            assertEquals("hello\n", result)
            val userExec = docker.execCalls.last()
            assertTrue(userExec.cmd.contains("bash"))
        }

    @Test
    fun `passes timeout from args`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "", exitCode = 0)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            tool.execute("echo 1", timeout = 60)

            val userExec = docker.execCalls.last()
            assertEquals(60, userExec.timeout)
        }

    @Test
    fun `uses default timeout when not specified`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "", exitCode = 0)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            tool.execute("echo 1")

            val userExec = docker.execCalls.last()
            assertEquals(config.timeout, userExec.timeout)
        }

    @Test
    fun `returns formatted output`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "result", stderr = "warn", exitCode = 1)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("echo 1")

            assertTrue(result.contains("result"), "Should contain stdout")
            assertTrue(result.contains("warn"), "Should contain stderr")
            assertTrue(result.contains("exit code: 1"), "Should contain exit code")
        }

    @Test
    fun `returns docker unavailable error`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextRunException = SandboxExecutionException.DockerUnavailable()
            val oneshotConfig = config.copy(keepAlive = false)
            val manager = SandboxManager(oneshotConfig, docker)
            val tool = SandboxExecTool(manager, oneshotConfig)

            val result = tool.execute("echo 1")

            assertTrue(result.contains("Docker is not available"), "Should report Docker unavailable: $result")
        }

    @Test
    fun `returns image not found error`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextRunException = SandboxExecutionException.ImageNotFound("klaw-sandbox:latest")
            val oneshotConfig = config.copy(keepAlive = false)
            val manager = SandboxManager(oneshotConfig, docker)
            val tool = SandboxExecTool(manager, oneshotConfig)

            val result = tool.execute("echo 1")

            assertTrue(
                result.contains("image 'klaw-sandbox:latest' not found"),
                "Should report image not found: $result",
            )
        }

    @Test
    fun `returns OOM error for keep-alive exec`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "Killed", exitCode = 137)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("python3 -c 'x = [0] * 10**9'")

            assertTrue(result.contains("ran out of memory"), "Should report OOM: $result")
        }

    @Test
    fun `returns permission denied error for keep-alive exec`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "permission denied", exitCode = 1)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("cat /etc/shadow")

            assertTrue(result.contains("Permission denied"), "Should report permission denied: $result")
        }

    @Test
    fun `returns container start failure error`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextRunException = SandboxExecutionException.ContainerStartFailure("conflict: name already in use")
            val oneshotConfig = config.copy(keepAlive = false)
            val manager = SandboxManager(oneshotConfig, docker)
            val tool = SandboxExecTool(manager, oneshotConfig)

            val result = tool.execute("echo 1")

            assertTrue(
                result.contains("Failed to start sandbox container"),
                "Should report container start failure: $result",
            )
        }

    @Test
    fun `timeout result is not classified as OOM`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "", exitCode = 137, timedOut = true)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("sleep 999")

            assertTrue(result.contains("timed out"), "Timeout should not be classified as OOM: $result")
        }
}
