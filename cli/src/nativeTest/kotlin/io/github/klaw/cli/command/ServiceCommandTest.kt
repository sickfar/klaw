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
            requestFn = { _, _, _ -> "{}" },
            configDir = tmpDir,
            logDir = "/nonexistent/logs",
            commandRunner = runner,
        )

    // --- service stop all ---

    @Test
    fun `service stop all uses docker compose when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("service stop all")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("stop") },
            "Expected docker compose stop, got: $commands",
        )
    }

    @Test
    fun `service stop all uses hybrid compose file path when deploy conf is hybrid`() {
        writeFileText("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("service stop all")
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
    fun `service stop all uses systemctl when deploy conf is native or missing`() {
        val result = cli().test("service stop all")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("systemctl") || it.contains("launchctl") },
            "Expected systemctl or launchctl command, got: $commands",
        )
    }

    @Test
    fun `service stop all prints success message on success`() {
        val result = cli().test("service stop all")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("stopped", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    @Test
    fun `service stop all prints failure message on failure`() {
        val failRunner: (String) -> Int = {
            commands += it
            1
        }
        val result = cli(failRunner).test("service stop all")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("failed", ignoreCase = true),
            "Expected failure message, got: ${result.output}",
        )
    }

    // --- service start engine ---

    @Test
    fun `service start engine uses docker compose when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("service start engine")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("up -d") && it.contains("engine") },
            "Expected docker compose up -d engine, got: $commands",
        )
    }

    @Test
    fun `service stop engine uses hybrid compose file when deploy conf is hybrid`() {
        writeFileText("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("service stop engine")
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
    fun `service restart engine uses docker compose up force-recreate when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("service restart engine")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("up -d --no-deps --force-recreate") &&
                    it.contains("engine")
            },
            "Expected docker compose up -d --no-deps --force-recreate engine, got: $commands",
        )
    }

    @Test
    fun `service restart engine uses native when deploy conf missing`() {
        val result = cli().test("service restart engine")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("systemctl") || it.contains("launchctl") },
            "Expected systemctl or launchctl, got: $commands",
        )
    }

    @Test
    fun `service start engine prints success message`() {
        val result = cli().test("service start engine")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("started", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    @Test
    fun `service stop engine prints failure message on failure`() {
        val failRunner: (String) -> Int = {
            commands += it
            1
        }
        val result = cli(failRunner).test("service stop engine")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("failed", ignoreCase = true),
            "Expected failure message, got: ${result.output}",
        )
    }

    @Test
    fun `service restart engine prints success message`() {
        val result = cli().test("service restart engine")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("restarted", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    // --- service start/stop/restart gateway ---

    @Test
    fun `service start gateway uses docker compose when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("service start gateway")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("up -d") && it.contains("gateway") },
            "Expected docker compose up -d gateway, got: $commands",
        )
    }

    @Test
    fun `service stop gateway uses hybrid compose file when deploy conf is hybrid`() {
        writeFileText("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("service stop gateway")
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
    fun `service restart gateway uses docker compose up force-recreate when deploy conf is docker`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("service restart gateway")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("up -d --no-deps --force-recreate") &&
                    it.contains("gateway")
            },
            "Expected docker compose up -d --no-deps --force-recreate gateway, got: $commands",
        )
    }

    @Test
    fun `service restart gateway uses native when deploy conf missing`() {
        val result = cli().test("service restart gateway")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any { it.contains("systemctl") || it.contains("launchctl") },
            "Expected systemctl or launchctl, got: $commands",
        )
    }

    @Test
    fun `service start gateway prints success message`() {
        val result = cli().test("service start gateway")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("started", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    @Test
    fun `service stop gateway prints failure message on failure`() {
        val failRunner: (String) -> Int = {
            commands += it
            1
        }
        val result = cli(failRunner).test("service stop gateway")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("failed", ignoreCase = true),
            "Expected failure message, got: ${result.output}",
        )
    }

    @Test
    fun `service restart gateway prints success message`() {
        val result = cli().test("service restart gateway")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("restarted", ignoreCase = true),
            "Expected success message, got: ${result.output}",
        )
    }

    // --- service start/restart all ---

    @Test
    fun `service start all starts both engine and gateway`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("service start all")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("up -d") &&
                    it.contains("engine") && it.contains("gateway")
            },
            "Expected docker compose up -d engine gateway, got: $commands",
        )
    }

    @Test
    fun `service restart all restarts both`() {
        writeFileText("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("service restart all")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("up -d --no-deps --force-recreate") &&
                    it.contains("engine") && it.contains("gateway")
            },
            "Expected docker compose up force-recreate for both, got: $commands",
        )
    }

    // --- missing argument ---

    @Test
    fun `service start without argument shows error`() {
        val result = cli().test("service start")
        assertTrue(
            result.statusCode != 0,
            "Expected non-zero exit for missing argument, got: ${result.statusCode}",
        )
    }

    // --- unknown target ---

    @Test
    fun `service start with unknown target prints error`() {
        val result = cli().test("service start invalid-target")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("Unknown target"),
            "Expected unknown target message, got: ${result.output}",
        )
        assertTrue(commands.isEmpty(), "No commands should run for unknown target, got: $commands")
    }
}
