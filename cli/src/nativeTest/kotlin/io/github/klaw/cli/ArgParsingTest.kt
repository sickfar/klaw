package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArgParsingTest {
    private fun fakeRequest(response: String): (String, Map<String, String>) -> String = { _, _ -> response }

    @Test
    fun `klaw logs --follow parsed correctly`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent/conversations",
                engineChecker = { false },
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
                engineChecker = { false },
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
                engineChecker = { false },
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
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("schedule add daily \"0 9 * * *\" \"Hello\" --model glm/glm-4-plus")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw memory search is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("memory search test-query")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw unknown_command exits with non-zero status`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("unknown_command_xyz")
        assertEquals(1, result.statusCode)
    }

    @Test
    fun `klaw service start engine is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("service start engine")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw service stop engine is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("service stop engine")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw service restart engine is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("service restart engine")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw service start gateway is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("service start gateway")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw service stop gateway is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("service stop gateway")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw service restart gateway is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("service restart gateway")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw service stop all is a valid command`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
                commandRunner = { 0 },
            )
        val result = cli.test("service stop all")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `klaw --version exits with status 0 and contains version`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("--version")
        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains(BuildConfig.VERSION),
            "Output should contain version string, got: ${result.output}",
        )
    }

    @Test
    fun `klaw --verbose flag is accepted`() {
        val cli =
            KlawCli(
                requestFn = fakeRequest("{}"),
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("--verbose status")
        assertEquals(0, result.statusCode)
    }
}
