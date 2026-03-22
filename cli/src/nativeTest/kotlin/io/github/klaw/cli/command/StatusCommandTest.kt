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
    private val requestFn: (String, Map<String, String>) -> String = { cmd, _ ->
        calledCommands += cmd
        when (cmd) {
            "status" -> """{"status": "running"}"""
            "sessions" -> """[{"id": "test-session"}]"""
            else -> "{}"
        }
    }

    private fun cli(fn: (String, Map<String, String>) -> String = requestFn): KlawCli =
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
    fun `status --sessions includes session list`() {
        val result = cli().test("status --sessions")
        assertEquals(0, result.statusCode)
        assertContains(result.output, "running")
        assertContains(result.output, "test-session")
        assertTrue(calledCommands.contains("status"))
        assertTrue(calledCommands.contains("sessions"))
    }

    @Test
    fun `status without --sessions does not call sessions endpoint`() {
        cli().test("status")
        assertTrue(calledCommands.contains("status"))
        assertTrue(!calledCommands.contains("sessions"))
    }

    @Test
    fun `status --sessions shows error when engine not running`() {
        val fn: (String, Map<String, String>) -> String = { _, _ ->
            throw EngineNotRunningException()
        }
        val result = cli(fn).test("status --sessions")
        assertEquals(0, result.statusCode)
        assertContains(result.output, "not running")
    }
}
