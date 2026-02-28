package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals

class ArgParsingTest {
    private fun fakeRequest(response: String): (String, Map<String, String>) -> String = { _, _ -> response }

    @Test
    fun `klaw logs --follow parsed correctly`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent/conversations",
                engineSocketPath = "/nonexistent/engine.sock",
                configDir = "/nonexistent/config",
                modelsDir = "/nonexistent/models",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("logs --follow")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw logs --chat filters by chat_id`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent/conversations",
                engineSocketPath = "/nonexistent/engine.sock",
                configDir = "/nonexistent/config",
                modelsDir = "/nonexistent/models",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("logs --chat telegram_123456")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw schedule add parses NAME CRON MESSAGE`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("""{"result":"ok"}"""),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("schedule add daily \"0 9 * * *\" \"Good morning\"")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw schedule add with --model flag`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("""{"result":"ok"}"""),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("schedule add daily \"0 9 * * *\" \"Hello\" --model glm/glm-4-plus")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw memory show is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("memory show")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw unknown_command exits with non-zero status`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("unknown_command_xyz")
        assertEquals(1, result.statusCode)
    }

    @Test
    fun `klaw engine start is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("engine start")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw engine stop is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("engine stop")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw engine restart is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("engine restart")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw gateway start is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("gateway start")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw gateway stop is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("gateway stop")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw gateway restart is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("gateway restart")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw stop is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("stop")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw --verbose flag is accepted`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("--verbose status")
        assertEquals(0, result.statusCode)
    }
}
