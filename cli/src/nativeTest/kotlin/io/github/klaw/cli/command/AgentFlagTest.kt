package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.KlawCli
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests that --agent / -a flag is correctly passed to engine requests
 * for all agent-scoped commands.
 */
class AgentFlagTest {
    private var capturedAgentId = "not-set"

    private fun cli(
        requestFn: EngineRequest = { _, _, agentId ->
            capturedAgentId = agentId
            "ok"
        },
    ): KlawCli =
        KlawCli(
            requestFn = requestFn,
            logDir = "/nonexistent/logs",
        )

    // --- sessions ---

    @Test
    fun `sessions list default agent is 'default'`() {
        cli().test("sessions list")
        assertEquals("default", capturedAgentId)
    }

    @Test
    fun `sessions --agent myagent list passes agentId`() {
        cli().test("sessions --agent myagent list")
        assertEquals("myagent", capturedAgentId)
    }

    @Test
    fun `sessions -a prod list passes agentId via short flag`() {
        cli().test("sessions -a prod list")
        assertEquals("prod", capturedAgentId)
    }

    @Test
    fun `sessions --agent a1 cleanup passes agentId`() {
        cli().test("sessions --agent a1 cleanup")
        assertEquals("a1", capturedAgentId)
    }

    // --- schedule ---

    @Test
    fun `schedule list default agent is 'default'`() {
        cli().test("schedule list")
        assertEquals("default", capturedAgentId)
    }

    @Test
    fun `schedule --agent myagent list passes agentId`() {
        cli().test("schedule --agent myagent list")
        assertEquals("myagent", capturedAgentId)
    }

    @Test
    fun `schedule -a prod list passes agentId via short flag`() {
        cli().test("schedule -a prod list")
        assertEquals("prod", capturedAgentId)
    }

    @Test
    fun `schedule --agent myagent status passes agentId`() {
        cli().test("schedule --agent myagent status")
        assertEquals("myagent", capturedAgentId)
    }

    // --- memory ---

    @Test
    fun `memory search default agent is 'default'`() {
        cli().test("memory search foo")
        assertEquals("default", capturedAgentId)
    }

    @Test
    fun `memory --agent myagent search passes agentId`() {
        cli().test("memory --agent myagent search foo")
        assertEquals("myagent", capturedAgentId)
    }

    @Test
    fun `memory -a prod search passes agentId via short flag`() {
        cli().test("memory -a prod search foo")
        assertEquals("prod", capturedAgentId)
    }

    @Test
    fun `memory --agent myagent consolidate passes agentId`() {
        cli().test("memory --agent myagent consolidate")
        assertEquals("myagent", capturedAgentId)
    }

    @Test
    fun `memory --agent myagent categories list passes agentId`() {
        cli().test("memory --agent myagent categories list")
        assertEquals("myagent", capturedAgentId)
    }

    // --- context ---

    @Test
    fun `context default agent is 'default'`() {
        cli().test("context")
        assertEquals("default", capturedAgentId)
    }

    @Test
    fun `context --agent myagent passes agentId`() {
        cli().test("context --agent myagent")
        assertEquals("myagent", capturedAgentId)
    }

    @Test
    fun `context -a prod passes agentId via short flag`() {
        cli().test("context -a prod")
        assertEquals("prod", capturedAgentId)
    }
}
