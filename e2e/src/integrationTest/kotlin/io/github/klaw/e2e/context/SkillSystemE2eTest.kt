package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * E2E tests for the skill system: discovery, inline display, tool calls,
 * global vs workspace skills, override behavior, and env var interpolation.
 *
 * Config: contextBudgetTokens=5000, maxToolCallRounds=3, summarizationEnabled=false,
 * autoRagEnabled=false, heartbeat=off, docs=false.
 *
 * Skills setup:
 * - Workspace skills: ws-alpha, ws-beta, override-test (WORKSPACE-DESC)
 * - Data (global) skills: data-only, override-test (GLOBAL-DESC)
 * - Env var skill: env-vars-skill (contains $KLAW_WORKSPACE and $KLAW_SKILL_DIR placeholders)
 *
 * Total: 4 unique skills (ws-alpha, ws-beta, data-only, override-test=workspace wins, env-vars-skill) = 5 skills
 * maxInlineSkills default = 5, so all should be inlined.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillSystemE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()

        // Create workspace skills
        WorkspaceGenerator.createSkillFile(
            File(workspaceDir, "skills"),
            "ws-alpha",
            "Alpha workspace skill",
            "# Alpha Skill\n\nThis is the alpha skill body content for testing.",
        )
        WorkspaceGenerator.createSkillFile(
            File(workspaceDir, "skills"),
            "ws-beta",
            "Beta workspace skill",
            "# Beta Skill\n\nBeta skill body content.",
        )
        WorkspaceGenerator.createSkillFile(
            File(workspaceDir, "skills"),
            "override-test",
            "WORKSPACE-DESC",
            "# Override Skill\n\nThis is the workspace version.",
        )
        WorkspaceGenerator.createSkillFile(
            File(workspaceDir, "skills"),
            "env-vars-skill",
            "Env vars test skill",
            "# Env Vars Skill\n\nWorkspace: \${KLAW_WORKSPACE}/projects\n" +
                "Skill dir: \$KLAW_SKILL_DIR/scripts/run.sh\n" +
                "Data: \${KLAW_DATA}/db\n" +
                "Config: \$KLAW_CONFIG/engine.json",
        )

        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        // Create data (global) skills after start — discover() runs on every message
        WorkspaceGenerator.createSkillFile(
            File(containers.engineDataPath, "skills"),
            "data-only",
            "Data-only global skill",
            "# Data Only\n\nGlobal data skill body.",
        )
        WorkspaceGenerator.createSkillFile(
            File(containers.engineDataPath, "skills"),
            "override-test",
            "GLOBAL-DESC",
            "# Override Skill\n\nThis is the global version that should be overridden.",
        )

        client = WebSocketChatClient()
        client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @BeforeEach
    fun resetWireMock() {
        wireMock.reset()
    }

    @Test
    @Order(1)
    fun `workspace skills inlined in system prompt`() {
        wireMock.stubChatResponse("SKILLS-INLINE-OK")

        val response = client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("SKILLS-INLINE-OK"), "Response should contain marker but was: $response")

        val systemContent = extractFirstSystemContent(wireMock.getNthRequestMessages(0))

        assertTrue(
            systemContent.contains("## Available Skills"),
            "System prompt should contain ## Available Skills section",
        )
        assertTrue(
            systemContent.contains("ws-alpha"),
            "System prompt should contain ws-alpha skill",
        )
        assertTrue(
            systemContent.contains("ws-beta"),
            "System prompt should contain ws-beta skill",
        )
    }

    @Test
    @Order(2)
    fun `skill frontmatter description format is correct`() {
        wireMock.stubChatResponse("FORMAT-OK")

        client.sendAndReceive("Check format", timeoutMs = RESPONSE_TIMEOUT_MS)

        val systemContent = extractFirstSystemContent(wireMock.getNthRequestMessages(0))

        assertTrue(
            systemContent.contains("- ws-alpha: Alpha workspace skill"),
            "Should contain exact format '- ws-alpha: Alpha workspace skill' but system content was:\n$systemContent",
        )
        assertTrue(
            systemContent.contains("- ws-beta: Beta workspace skill"),
            "Should contain exact format '- ws-beta: Beta workspace skill'",
        )
    }

    @Test
    @Order(3)
    fun `global data skills are discovered`() {
        wireMock.stubChatResponse("GLOBAL-OK")

        client.sendAndReceive("Check global", timeoutMs = RESPONSE_TIMEOUT_MS)

        val systemContent = extractFirstSystemContent(wireMock.getNthRequestMessages(0))

        assertTrue(
            systemContent.contains("data-only"),
            "System prompt should contain data-only skill",
        )
        assertTrue(
            systemContent.contains("Data-only global skill"),
            "System prompt should contain data-only skill description",
        )
    }

    @Test
    @Order(4)
    fun `workspace skills override global with same name`() {
        wireMock.stubChatResponse("OVERRIDE-OK")

        client.sendAndReceive("Check override", timeoutMs = RESPONSE_TIMEOUT_MS)

        val systemContent = extractFirstSystemContent(wireMock.getNthRequestMessages(0))

        assertTrue(
            systemContent.contains("WORKSPACE-DESC"),
            "System prompt should show workspace description for override-test",
        )
        assertFalse(
            systemContent.contains("GLOBAL-DESC"),
            "System prompt should NOT show global description for override-test",
        )
    }

    @Test
    @Order(5)
    @Suppress("MaxLineLength")
    fun `both global and workspace skills appear together`() {
        wireMock.stubChatResponse("BOTH-OK")

        client.sendAndReceive("Check both", timeoutMs = RESPONSE_TIMEOUT_MS)

        val systemContent = extractFirstSystemContent(wireMock.getNthRequestMessages(0))

        assertTrue(systemContent.contains("ws-alpha"), "Should contain workspace skill ws-alpha")
        assertTrue(systemContent.contains("ws-beta"), "Should contain workspace skill ws-beta")
        assertTrue(systemContent.contains("data-only"), "Should contain global skill data-only")
        assertTrue(systemContent.contains("override-test"), "Should contain override-test (workspace version)")
    }

    @Test
    @Order(6)
    fun `capabilities section mentions skills`() {
        wireMock.stubChatResponse("CAPS-OK")

        client.sendAndReceive("Check capabilities", timeoutMs = RESPONSE_TIMEOUT_MS)

        val systemContent = extractFirstSystemContent(wireMock.getNthRequestMessages(0))

        assertTrue(
            systemContent.contains("extensible skills"),
            "Capabilities section should mention 'extensible skills'",
        )
    }

    @Test
    @Order(7)
    fun `skill_load returns full content`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_load1",
                            name = "skill_load",
                            arguments = """{"name":"ws-alpha"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "SKILL-LOADED-OK: content received",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Load ws-alpha skill", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("SKILL-LOADED-OK"), "Response should contain marker but was: $response")

        // Verify tool result in second LLM request
        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractLastToolResultContent(lastMessages)

        assertTrue(
            toolContent.contains("Alpha Skill"),
            "Tool result should contain skill body '# Alpha Skill' but was: $toolContent",
        )
        assertTrue(
            toolContent.contains("alpha skill body content"),
            "Tool result should contain full body text",
        )
    }

    @Test
    @Order(8)
    fun `skill_load returns error for nonexistent`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_missing",
                            name = "skill_load",
                            arguments = """{"name":"nonexistent-skill"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "SKILL-MISSING-OK: handled",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Load missing skill", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("SKILL-MISSING-OK"), "Response should contain marker but was: $response")

        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractLastToolResultContent(lastMessages)

        assertTrue(
            toolContent.contains("nonexistent-skill") && toolContent.contains("not found"),
            "Tool result should contain error about 'nonexistent-skill' not found but was: $toolContent",
        )
    }

    @Test
    @Order(9)
    fun `environment variables resolved in skill content`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_envvars",
                            name = "skill_load",
                            arguments = """{"name":"env-vars-skill"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "ENVVARS-OK: variables resolved",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Load env vars skill", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("ENVVARS-OK"), "Response should contain marker but was: $response")

        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolContent = extractLastToolResultContent(lastMessages)

        // ${KLAW_WORKSPACE} should be resolved to /workspace (Docker mount)
        assertFalse(
            toolContent.contains("\${KLAW_WORKSPACE}"),
            "Tool result should NOT contain raw \${KLAW_WORKSPACE} placeholder. Content: $toolContent",
        )
        assertTrue(
            toolContent.contains("/workspace/projects"),
            "Tool result should contain resolved workspace path '/workspace/projects'. Content: $toolContent",
        )

        // $KLAW_SKILL_DIR should be resolved (without braces)
        assertFalse(
            toolContent.contains("\$KLAW_SKILL_DIR"),
            "Tool result should NOT contain raw \$KLAW_SKILL_DIR placeholder. Content: $toolContent",
        )
        assertTrue(
            toolContent.contains("/scripts/run.sh"),
            "Tool result should contain resolved skill dir path with '/scripts/run.sh'. Content: $toolContent",
        )

        // ${KLAW_DATA} should be resolved
        assertFalse(
            toolContent.contains("\${KLAW_DATA}"),
            "Tool result should NOT contain raw \${KLAW_DATA}. Content: $toolContent",
        )

        // $KLAW_CONFIG should be resolved
        assertFalse(
            toolContent.contains("\$KLAW_CONFIG"),
            "Tool result should NOT contain raw \$KLAW_CONFIG. Content: $toolContent",
        )
    }

    private fun extractFirstSystemContent(messages: kotlinx.serialization.json.JsonArray): String =
        messages
            .first { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "system"
            }.jsonObject["content"]
            ?.jsonPrimitive
            ?.content ?: ""

    private fun extractLastToolResultContent(messages: kotlinx.serialization.json.JsonArray): String {
        val toolMessages =
            messages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
        return toolMessages
            .lastOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content ?: ""
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
