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
                coreMemoryPath = "/nonexistent/core_memory.json",
                engineSocketPath = "/nonexistent/engine.sock",
                configDir = "/nonexistent/config",
                modelsDir = "/nonexistent/models",
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
                coreMemoryPath = "/nonexistent/core_memory.json",
                engineSocketPath = "/nonexistent/engine.sock",
                configDir = "/nonexistent/config",
                modelsDir = "/nonexistent/models",
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
                coreMemoryPath = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
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
                coreMemoryPath = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
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
                coreMemoryPath = "/nonexistent/core_memory.json",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
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
                coreMemoryPath = "/nonexistent",
                engineSocketPath = "/nonexistent",
                configDir = "/nonexistent",
                modelsDir = "/nonexistent",
            )
        val result = cli.test("unknown_command_xyz")
        assertEquals(1, result.statusCode)
    }
}
