package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.socket.EngineNotRunningException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EngineNotRunningTest {
    private fun engineDownRequest(): (String, Map<String, String>) -> String = { _, _ -> throw EngineNotRunningException() }

    private fun cli() =
        KlawCli(
            requestFn = engineDownRequest(),
            conversationsDir = "/nonexistent",
            engineSocketPath = "/nonexistent/engine.sock",
            configDir = "/nonexistent",
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `status returns helpful error when engine not running`() {
        val result = cli().test("status")
        assertContains(result.output, "not running")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `schedule list returns helpful error when engine not running`() {
        val result = cli().test("schedule list")
        assertContains(result.output, "not running")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `memory search returns helpful error when engine not running`() {
        val result = cli().test("memory search \"test query\"")
        assertContains(result.output, "not running")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `error message includes systemctl start command`() {
        val result = cli().test("status")
        assertContains(result.output, "klaw-engine")
    }
}
