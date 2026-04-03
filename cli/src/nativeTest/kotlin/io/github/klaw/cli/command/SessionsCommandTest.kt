package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.socket.EngineNotRunningException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionsCommandTest {
    private var capturedCommand = ""
    private var capturedParams = mapOf<String, String>()
    private var capturedAgentId = ""

    private fun cli(
        requestFn: EngineRequest = { cmd, params, agentId ->
            capturedCommand = cmd
            capturedParams = params
            capturedAgentId = agentId
            """[{"chatId":"test","model":"glm-5"}]"""
        },
    ): KlawCli =
        KlawCli(
            requestFn = requestFn,
            logDir = "/nonexistent/logs",
        )

    @Test
    fun `sessions list sends sessions_list command`() {
        val result = cli().test("sessions list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("sessions_list", capturedCommand)
    }

    @Test
    fun `sessions list --active 30 sends active_minutes param`() {
        val result = cli().test("sessions list --active 30")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("sessions_list", capturedCommand)
        assertEquals("30", capturedParams["active_minutes"])
    }

    @Test
    fun `sessions list --json sends json param`() {
        val result = cli().test("sessions list --json")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("sessions_list", capturedCommand)
        assertEquals("true", capturedParams["json"])
    }

    @Test
    fun `sessions list --verbose sends verbose param`() {
        val result = cli().test("sessions list --verbose")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("sessions_list", capturedCommand)
        assertEquals("true", capturedParams["verbose"])
    }

    @Test
    fun `sessions list --active 30 --json --verbose sends all params`() {
        val result = cli().test("sessions list --active 30 --json --verbose")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("sessions_list", capturedCommand)
        assertEquals("30", capturedParams["active_minutes"])
        assertEquals("true", capturedParams["json"])
        assertEquals("true", capturedParams["verbose"])
        assertEquals(3, capturedParams.size, "Expected exactly 3 params, got: $capturedParams")
    }

    @Test
    fun `sessions cleanup sends sessions_cleanup command`() {
        val result = cli().test("sessions cleanup")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("sessions_cleanup", capturedCommand)
        assertTrue(capturedParams.isEmpty(), "Expected empty params, got: $capturedParams")
    }

    @Test
    fun `sessions cleanup --older-than 60 sends older_than_minutes param`() {
        val result = cli().test("sessions cleanup --older-than 60")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertEquals("sessions_cleanup", capturedCommand)
        assertEquals("60", capturedParams["older_than_minutes"])
    }

    @Test
    fun `sessions list uses default agent when no --agent flag`() {
        cli().test("sessions list")
        assertEquals("default", capturedAgentId)
    }

    @Test
    fun `sessions --agent myagent list passes agentId`() {
        cli().test("sessions --agent myagent list")
        assertEquals("myagent", capturedAgentId)
    }

    @Test
    fun `sessions -a prod list passes agentId via short option`() {
        cli().test("sessions -a prod list")
        assertEquals("prod", capturedAgentId)
    }

    @Test
    fun `sessions list shows engine not running on failure`() {
        val failingRequest: EngineRequest = { _, _, _ -> throw EngineNotRunningException() }
        val result = cli(failingRequest).test("sessions list")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Engine is not running"), "output: ${result.output}")
    }

    @Test
    fun `sessions cleanup shows engine not running on failure`() {
        val failingRequest: EngineRequest = { _, _, _ -> throw EngineNotRunningException() }
        val result = cli(failingRequest).test("sessions cleanup")
        assertEquals(0, result.statusCode, "output: ${result.output}")
        assertTrue(result.output.contains("Engine is not running"), "output: ${result.output}")
    }
}
