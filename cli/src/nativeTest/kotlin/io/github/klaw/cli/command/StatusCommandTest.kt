package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.socket.EngineNotRunningException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusCommandTest {
    private val calledCommands = mutableListOf<String>()
    private val requestFn: (String, Map<String, String>, String) -> String = { cmd, _, _ ->
        calledCommands += cmd
        when (cmd) {
            "status" -> """{"status": "running"}"""
            else -> "{}"
        }
    }

    private fun cli(fn: (String, Map<String, String>, String) -> String = requestFn): KlawCli =
        KlawCli(
            requestFn = fn,
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `status shows engine status`() {
        val result = cli().test("status")
        assertEquals(0, result.statusCode)
        assertContains(result.output, "running")
        assertTrue(calledCommands.contains("status"))
    }

    @Test
    fun `status shows error when engine not running`() {
        val fn: (String, Map<String, String>, String) -> String = { _, _, _ ->
            throw EngineNotRunningException()
        }
        val result = cli(fn).test("status")
        assertEquals(0, result.statusCode)
        assertContains(result.output, "not running")
    }
}
