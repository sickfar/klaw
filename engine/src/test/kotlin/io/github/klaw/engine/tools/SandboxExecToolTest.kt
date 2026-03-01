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
    fun `executes python code via sandbox manager`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "42\n", stderr = "", exitCode = 0)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("python", "print(42)")

            assertEquals("42\n", result)
            assertEquals(1, docker.execCalls.size)
            assertTrue(docker.execCalls[0].cmd.contains("python3"))
        }

    @Test
    fun `executes bash code via sandbox manager`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "hello\n", stderr = "", exitCode = 0)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("bash", "echo hello")

            assertEquals("hello\n", result)
            assertTrue(docker.execCalls[0].cmd.contains("bash"))
        }

    @Test
    fun `passes timeout from args`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "", exitCode = 0)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            tool.execute("python", "print(1)", timeout = 60)

            assertEquals(60, docker.execCalls[0].timeout)
        }

    @Test
    fun `uses default timeout when not specified`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "", stderr = "", exitCode = 0)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            tool.execute("python", "print(1)")

            assertEquals(config.timeout, docker.execCalls[0].timeout)
        }

    @Test
    fun `returns formatted output`() =
        runTest {
            val docker = FakeDockerClient()
            docker.nextExecResult = ExecutionResult(stdout = "result", stderr = "warn", exitCode = 1)
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("python", "print(1)")

            assertTrue(result.contains("result"), "Should contain stdout")
            assertTrue(result.contains("warn"), "Should contain stderr")
            assertTrue(result.contains("exit code: 1"), "Should contain exit code")
        }

    @Test
    fun `returns error for unsupported language`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            val tool = SandboxExecTool(manager, config)

            val result = tool.execute("ruby", "puts 1")

            assertTrue(result.contains("Unsupported language"), "Should report unsupported language")
        }
}
