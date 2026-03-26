package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.KlawCli
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryCategoriesListCommandTest {
    private var capturedCommand = ""
    private var capturedParams = mapOf<String, String>()

    private fun cli(
        requestFn: EngineRequest = { cmd, params ->
            capturedCommand = cmd
            capturedParams = params
            "No memory categories found."
        },
    ): KlawCli =
        KlawCli(
            requestFn = requestFn,
            configDir = "/nonexistent",
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `categories list sends command with empty params`() {
        val result = cli().test("memory categories list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("memory_categories_list", capturedCommand)
        assertTrue(capturedParams.isEmpty(), "Expected empty params, got: $capturedParams")
    }

    @Test
    fun `categories list with --json sends json param`() {
        val result = cli().test("memory categories list --json")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("memory_categories_list", capturedCommand)
        assertEquals("true", capturedParams["json"])
        assertEquals(1, capturedParams.size, "Expected only json param, got: $capturedParams")
    }
}
