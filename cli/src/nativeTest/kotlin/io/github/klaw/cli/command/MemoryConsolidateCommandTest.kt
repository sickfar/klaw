package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.socket.EngineNotRunningException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryConsolidateCommandTest {
    private var capturedCommand = ""
    private var capturedParams = mapOf<String, String>()

    private fun cli(
        requestFn: EngineRequest = { cmd, params, _ ->
            capturedCommand = cmd
            capturedParams = params
            "Consolidation complete"
        },
    ): KlawCli =
        KlawCli(
            requestFn = requestFn,
            configDir = "/nonexistent",
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `consolidate with no flags sends command with empty params`() {
        val result = cli().test("memory consolidate")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("memory_consolidate", capturedCommand)
        assertTrue(capturedParams.isEmpty(), "Expected empty params, got: $capturedParams")
        assertTrue(result.output.contains("Consolidation complete"), "output: ${result.output}")
    }

    @Test
    fun `consolidate with --date sends date param`() {
        val result = cli().test("memory consolidate --date 2026-03-19")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("memory_consolidate", capturedCommand)
        assertEquals("2026-03-19", capturedParams["date"])
        assertEquals(1, capturedParams.size, "Expected only date param, got: $capturedParams")
    }

    @Test
    fun `consolidate with --force sends force param`() {
        val result = cli().test("memory consolidate --force")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("memory_consolidate", capturedCommand)
        assertEquals("true", capturedParams["force"])
        assertEquals(1, capturedParams.size, "Expected only force param, got: $capturedParams")
    }

    @Test
    fun `consolidate with --date and --force sends both params`() {
        val result = cli().test("memory consolidate --date 2026-03-18 --force")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("memory_consolidate", capturedCommand)
        assertEquals("2026-03-18", capturedParams["date"])
        assertEquals("true", capturedParams["force"])
        assertEquals(2, capturedParams.size, "Expected 2 params, got: $capturedParams")
    }

    @Test
    fun `consolidate prints engine not running when engine is down`() {
        val failingRequest: EngineRequest = { _, _, _ -> throw EngineNotRunningException() }
        val result = cli(failingRequest).test("memory consolidate")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Engine is not running"), "output: ${result.output}")
    }
}
