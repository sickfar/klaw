package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.util.writeFileText
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceCommandTest {
    private val tmpDir = "/tmp/klaw-svc-cmd-test-${getpid()}"
    private val commands = mutableListOf<String>()
    private val commandRunner: (String) -> Int = { cmd ->
        commands += cmd
        0
    }

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
        commands.clear()
    }

    @AfterTest
    fun cleanup() {
        unlink("$tmpDir/deploy.conf")
        rmdir(tmpDir)
    }

    private fun cli(runner: (String) -> Int = commandRunner): KlawCli =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            configDir = tmpDir,
            logDir = "/nonexistent/logs",
            commandRunner = runner,
        )

    // --- StopCommand ---

    @Test
    fun `stop uses docker compose when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("stop")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("stop") },
            "Expected docker compose stop, got: $commands",
        )
    }

    @Test
    fun `stop uses hybrid compose file path when deploy conf is hybrid`() {
        writeFileText("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("stop")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("stop") &&
                    it.contains("$tmpDir/docker-compose.json")
            },
            "Expected docker compose stop with hybrid compose file, got: $commands",
        )
    }

    @Test
    fun `stop uses systemctl when deploy conf is native or missing`() {
        // No deploy.conf → defaults to NATIVE
        val result = cli().test("stop")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("systemctl") || it.contains("launchctl") },
            "Expected systemctl or launchctl command, got: $commands",
        )
    }

    @Test
    fun `stop prints success message on success`() {
        val result = cli().test("stop")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("stopped", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    @Test
    fun `stop prints failure message on failure`() {
        val failRunner: (String) -> Int = {
            commands += it
            1
        }
        val result = cli(failRunner).test("stop")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("failed", ignoreCase = true),
            "Expected failure message, got: ${result.output}",
        )
    }

    // --- EngineCommand ---

    @Test
    fun `engine start uses docker compose when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("engine start")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("up -d") && it.contains("engine") },
            "Expected docker compose up -d engine, got: $commands",
        )
    }

    @Test
    fun `engine stop uses hybrid compose file when deploy conf is hybrid`() {
        writeFileText("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("engine stop")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("stop") &&
                    it.contains("engine") && it.contains("$tmpDir/docker-compose.json")
            },
            "Expected docker compose stop engine with hybrid path, got: $commands",
        )
    }

    @Test
    fun `engine restart uses native when deploy conf missing`() {
        val result = cli().test("engine restart")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("systemctl") || it.contains("launchctl") },
            "Expected systemctl or launchctl, got: $commands",
        )
    }

    @Test
    fun `engine start prints success message`() {
        val result = cli().test("engine start")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("started", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    @Test
    fun `engine stop prints failure message on failure`() {
        val failRunner: (String) -> Int = {
            commands += it
            1
        }
        val result = cli(failRunner).test("engine stop")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("failed", ignoreCase = true),
            "Expected failure message, got: ${result.output}",
        )
    }

    @Test
    fun `engine restart prints success message`() {
        val result = cli().test("engine restart")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("restarted", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    // --- GatewayCommand ---

    @Test
    fun `gateway start uses docker compose when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("gateway start")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("up -d") && it.contains("gateway") },
            "Expected docker compose up -d gateway, got: $commands",
        )
    }

    @Test
    fun `gateway stop uses hybrid compose file when deploy conf is hybrid`() {
        writeFileText("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("gateway stop")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("stop") &&
                    it.contains("gateway") && it.contains("$tmpDir/docker-compose.json")
            },
            "Expected docker compose stop gateway with hybrid path, got: $commands",
        )
    }

    @Test
    fun `gateway restart uses native when deploy conf missing`() {
        val result = cli().test("gateway restart")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("systemctl") || it.contains("launchctl") },
            "Expected systemctl or launchctl, got: $commands",
        )
    }

    @Test
    fun `gateway start prints success message`() {
        val result = cli().test("gateway start")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("started", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    @Test
    fun `gateway stop prints failure message on failure`() {
        val failRunner: (String) -> Int = {
            commands += it
            1
        }
        val result = cli(failRunner).test("gateway stop")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("failed", ignoreCase = true),
            "Expected failure message, got: ${result.output}",
        )
    }

    @Test
    fun `gateway restart prints success message`() {
        val result = cli().test("gateway restart")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("restarted", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }
}
