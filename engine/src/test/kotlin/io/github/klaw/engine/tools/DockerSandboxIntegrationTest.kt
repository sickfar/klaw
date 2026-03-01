package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class DockerSandboxIntegrationTest {
    private val docker = ProcessDockerClient()

    private fun config(
        keepAlive: Boolean = false,
        timeout: Int = 10,
    ) = CodeExecutionConfig(
        dockerImage = "python:3.12-slim",
        timeout = timeout,
        allowNetwork = false,
        maxMemory = "128m",
        maxCpus = "0.5",
        readOnlyRootfs = true,
        keepAlive = keepAlive,
        keepAliveIdleTimeoutMin = 5,
        keepAliveMaxExecutions = 100,
    )

    private var manager: SandboxManager? = null

    private fun sandbox(
        keepAlive: Boolean = false,
        timeout: Int = 10,
    ): SandboxManager {
        val m = SandboxManager(config(keepAlive, timeout), docker)
        manager = m
        return m
    }

    @AfterEach
    fun tearDown() =
        runTest {
            manager?.shutdown()
        }

    @Test
    fun `python code executes and returns output`() =
        runTest {
            val sb = sandbox()
            val result = sb.execute("python", "print('hello from sandbox')", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("hello from sandbox"))
            assertFalse(result.timedOut)
        }

    @Test
    fun `bash code executes and returns output`() =
        runTest {
            val sb = sandbox()
            val result = sb.execute("bash", "echo 'bash works'", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("bash works"))
            assertFalse(result.timedOut)
        }

    @Test
    fun `python computation returns correct result`() =
        runTest {
            val sb = sandbox()
            val result = sb.execute("python", "print(sum(range(1, 101)))", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.trim().contains("5050"))
        }

    @Test
    fun `stderr captured on python error`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("python", "import sys; sys.stderr.write('err msg\\n'); print('out')", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("out"))
            assertTrue(result.stderr.contains("err msg"))
        }

    @Test
    fun `non-zero exit code on python syntax error`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("python", "def invalid(", 10)
            assertTrue(result.exitCode != 0, "Should have non-zero exit code for syntax error")
            assertTrue(result.stderr.isNotEmpty(), "Should have stderr for syntax error")
        }

    @Test
    fun `timeout enforced for infinite loop`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("python", "import time\nwhile True: time.sleep(0.1)", 3)
            assertTrue(result.timedOut, "Should timeout for infinite loop")
        }

    @Test
    fun `keep-alive mode reuses container`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val r1 = sb.execute("python", "print('run1')", 10)
            assertEquals(0, r1.exitCode)
            assertTrue(r1.stdout.contains("run1"))

            val r2 = sb.execute("python", "print('run2')", 10)
            assertEquals(0, r2.exitCode)
            assertTrue(r2.stdout.contains("run2"))
        }

    @Test
    fun `read-only rootfs prevents writes outside tmpfs`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("bash", "touch /opt/testfile 2>&1; echo \"exit:\$?\"", 10)
            assertTrue(
                result.stdout.contains("exit:1") ||
                    result.stdout.contains("Read-only") ||
                    result.stderr.contains("Read-only"),
                "Should fail to write to read-only rootfs: stdout=${result.stdout}, stderr=${result.stderr}",
            )
        }

    @Test
    fun `tmpfs writable at tmp`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("bash", "echo 'data' > /tmp/test.txt && cat /tmp/test.txt", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("data"))
        }

    @Test
    fun `network disabled in sandbox`() =
        runTest {
            val sb = sandbox(keepAlive = true)

            @Suppress("MaxLineLength")
            val code =
                "import socket,sys\ntry:\n socket.create_connection(('1.1.1.1',80),timeout=2)\n print('connected')\nexcept Exception as e:\n print(f'blocked:{type(e).__name__}')\n sys.exit(1)"
            val result = sb.execute("python", code, 10)
            assertTrue(
                result.exitCode != 0 ||
                    result.stdout.contains("blocked:"),
                "Network should be disabled: stdout=${result.stdout}, stderr=${result.stderr}",
            )
        }

    @Test
    fun `formatForLlm produces readable output`() =
        runTest {
            val sb = sandbox()
            val result = sb.execute("python", "print('formatted output test')", 10)
            val formatted = result.formatForLlm()
            assertTrue(formatted.contains("formatted output test"))
        }
}
