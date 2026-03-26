package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.socket.EngineNotRunningException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryFactsListCommandTest {
    private var capturedCommand = ""
    private var capturedParams = mapOf<String, String>()

    private fun cli(
        requestFn: EngineRequest = { cmd, params ->
            capturedCommand = cmd
            capturedParams = params
            "fact1\nfact2"
        },
    ): KlawCli =
        KlawCli(
            requestFn = requestFn,
            configDir = "/nonexistent",
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `facts list sends correct command with category`() {
        val result = cli().test("memory facts list my-category")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("memory_facts_list", capturedCommand)
        assertEquals("my-category", capturedParams["category"])
        assertEquals(1, capturedParams.size, "Expected only category param, got: $capturedParams")
    }

    @Test
    fun `facts list prints engine response`() {
        val result = cli().test("memory facts list test-cat")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("fact1"), "output: ${result.output}")
        assertTrue(result.output.contains("fact2"), "output: ${result.output}")
    }

    @Test
    fun `facts list prints engine not running when engine is down`() {
        val failingRequest: EngineRequest = { _, _ -> throw EngineNotRunningException() }
        val result = cli(failingRequest).test("memory facts list my-category")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Engine is not running"), "output: ${result.output}")
    }
}
