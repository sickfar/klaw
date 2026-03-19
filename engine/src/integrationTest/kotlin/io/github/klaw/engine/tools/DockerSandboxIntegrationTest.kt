package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerSandboxIntegrationTest {
    private val docker = ProcessDockerClient()

    private fun config(
        keepAlive: Boolean = false,
        timeout: Int = 10,
        volumeMounts: List<String> = emptyList(),
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
        volumeMounts = volumeMounts,
    )

    private var manager: SandboxManager? = null

    private fun sandbox(
        keepAlive: Boolean = false,
        timeout: Int = 10,
        volumeMounts: List<String> = emptyList(),
    ): SandboxManager {
        val m = SandboxManager(config(keepAlive, timeout, volumeMounts), docker)
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
            val result = sb.execute("python3 -c \"print('hello from sandbox')\"", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("hello from sandbox"))
            assertFalse(result.timedOut)
        }

    @Test
    fun `bash code executes and returns output`() =
        runTest {
            val sb = sandbox()
            val result = sb.execute("echo 'bash works'", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("bash works"))
            assertFalse(result.timedOut)
        }

    @Test
    fun `python computation returns correct result`() =
        runTest {
            val sb = sandbox()
            val result = sb.execute("python3 -c 'print(sum(range(1, 101)))'", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.trim().contains("5050"))
        }

    @Test
    fun `stderr captured on python error`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("python3 -c \"import sys; sys.stderr.write('err msg\\n'); print('out')\"", 10)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("out"))
            assertTrue(result.stderr.contains("err msg"))
        }

    @Test
    fun `non-zero exit code on python syntax error`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("python3 -c 'def invalid('", 10)
            assertTrue(result.exitCode != 0, "Should have non-zero exit code for syntax error")
            assertTrue(result.stderr.isNotEmpty(), "Should have stderr for syntax error")
        }

    @Test
    fun `timeout enforced for infinite loop`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("python3 -c 'import time\nwhile True: time.sleep(0.1)'", 3)
            assertTrue(result.timedOut, "Should timeout for infinite loop")
        }

    @Test
    fun `keep-alive mode reuses container`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val r1 = sb.execute("python3 -c \"print('run1')\"", 10)
            assertEquals(0, r1.exitCode)
            assertTrue(r1.stdout.contains("run1"))

            val r2 = sb.execute("python3 -c \"print('run2')\"", 10)
            assertEquals(0, r2.exitCode)
            assertTrue(r2.stdout.contains("run2"))
        }

    @Test
    fun `read-only rootfs prevents writes outside tmpfs`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("touch /opt/testfile 2>&1; echo \"exit:\$?\"", 10)
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
            val result = sb.execute("echo 'data' > /tmp/test.txt && cat /tmp/test.txt", 10)
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
            val result = sb.execute("python3 -c \"$code\"", 10)
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
            val result = sb.execute("python3 -c \"print('formatted output test')\"", 10)
            val formatted = result.formatForLlm()
            assertTrue(formatted.contains("formatted output test"))
        }

    // --- Security Hardening Tests (Issue #20) ---

    @Test
    fun `sensitive host paths never mounted even if configured`() =
        runTest {
            val sb = sandbox(volumeMounts = listOf("/etc/passwd:/data:ro"))
            val result = sb.execute("cat /data 2>&1 || echo 'not mounted'", 10)
            assertTrue(
                result.stdout.contains("not mounted") ||
                    result.stdout.contains("No such file") ||
                    result.stderr.contains("No such file"),
                "Sensitive path /etc/passwd should not be mounted: stdout=${result.stdout}, stderr=${result.stderr}",
            )
        }

    @Test
    fun `docker run directory never mounted even if configured`() =
        runTest {
            val sb = sandbox(volumeMounts = listOf("/var/run/docker:/docker:ro"))
            val result = sb.execute("ls /docker 2>&1 || echo 'not mounted'", 10)
            assertTrue(
                result.stdout.contains("not mounted") ||
                    result.stdout.contains("No such file") ||
                    result.stderr.contains("No such file"),
                "Docker run directory should not be mounted: stdout=${result.stdout}, stderr=${result.stderr}",
            )
        }

    @Test
    fun `container runs as non-root user`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("id -u", 10)
            assertEquals(0, result.exitCode)
            val uid = result.stdout.trim()
            assertTrue(uid != "0", "Container should not run as root (uid=0), got uid=$uid")
        }

    @Test
    fun `container user matches configured runAsUser`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("echo \"$(id -u):$(id -g)\"", 10)
            assertEquals(0, result.exitCode)
            assertEquals("1000:1000", result.stdout.trim(), "User:group should match default runAsUser")
        }

    @Test
    fun `tmp cleared between keep-alive executions`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val write = sb.execute("echo 'secret' > /tmp/leak.txt && echo 'written'", 10)
            assertEquals(0, write.exitCode)
            assertTrue(write.stdout.contains("written"))

            val read = sb.execute("cat /tmp/leak.txt 2>&1 || echo 'not found'", 10)
            assertTrue(
                read.stdout.contains("not found") ||
                    read.stderr.contains("No such file"),
                "File from previous execution should not exist: stdout=${read.stdout}, stderr=${read.stderr}",
            )
        }

    @Test
    fun `capabilities are dropped`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("cat /proc/self/status | grep CapEff", 10)
            assertEquals(0, result.exitCode)
            val capLine = result.stdout.trim()
            assertTrue(
                capLine.contains("0000000000000000"),
                "CapEff should be all zeros (all capabilities dropped), got: $capLine",
            )
        }

    @Test
    fun `no-new-privileges is enforced`() =
        runTest {
            val sb = sandbox(keepAlive = true)
            val result = sb.execute("grep NoNewPrivs /proc/self/status", 10)
            assertEquals(0, result.exitCode)
            assertTrue(
                result.stdout.contains("1"),
                "NoNewPrivs should be 1 (enforced), got: ${result.stdout.trim()}",
            )
        }

    @Test
    fun `pids limit enforced`() =
        runTest {
            val sb = sandbox(keepAlive = true)

            @Suppress("MaxLineLength")
            val code =
                "python3 -c \"import os,sys\nfor i in range(200):\n try:\n  os.fork()\n except OSError:\n  print(f'fork failed at {i}')\n  sys.exit(1)\nprint('all forked')\""
            val result = sb.execute(code, 10)
            assertTrue(
                result.exitCode != 0 ||
                    result.stdout.contains("fork failed"),
                "Fork bomb should be limited: exitCode=${result.exitCode}, stdout=${result.stdout}",
            )
        }
}
