package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * E2E tests for the skill_list tool when skills exceed maxInlineSkills threshold.
 *
 * Config: maxInlineSkills=3, 6 skills created.
 * Expected behavior: skills NOT inlined, skill_list + skill_load tools available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillListToolE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()

        // Create 6 skills to exceed maxInlineSkills=3
        for (i in 1..SKILL_COUNT) {
            WorkspaceGenerator.createSkillFile(
                File(workspaceDir, "skills"),
                "skill-$i",
                "Description for skill number $i",
                "# Skill $i\n\nBody content of skill $i.",
            )
        }

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
                        maxInlineSkills = MAX_INLINE_SKILLS,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

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
    fun `inline skills section absent when skills exceed maxInlineSkills`() {
        wireMock.stubChatResponse("NO-INLINE-OK")

        val response = client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("NO-INLINE-OK"), "Response should contain marker but was: $response")

        val systemContent =
            wireMock
                .getNthRequestMessages(0)
                .first { msg ->
                    msg.jsonObject["role"]?.jsonPrimitive?.content == "system"
                }.jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertFalse(
            systemContent.contains("## Available Skills"),
            "System prompt should NOT contain ## Available Skills when skills exceed maxInlineSkills",
        )

        // But capabilities should still mention skills
        assertTrue(
            systemContent.contains("extensible skills"),
            "Capabilities should mention 'extensible skills' even when not inlined",
        )
    }

    @Test
    @Order(2)
    fun `skill_list and skill_load tools available when skills exceed maxInlineSkills`() {
        wireMock.stubChatResponse("TOOLS-PRESENT-OK")

        client.sendAndReceive("Check tools", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(wireMock.getNthRequestHasTools(0), "Request should include tools")

        val body = wireMock.getNthRequestBody(0)
        val tools = body["tools"]!!.jsonArray

        val skillListTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "skill_list"
            }
        val skillLoadTool =
            tools.firstOrNull { tool ->
                tool.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content == "skill_load"
            }

        assertNotNull(skillListTool, "skill_list tool should be present when skills exceed maxInlineSkills")
        assertNotNull(skillLoadTool, "skill_load tool should be present")
    }

    @Test
    @Order(3)
    fun `skill_list tool returns all skills with descriptions`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_list1",
                            name = "skill_list",
                            arguments = "{}",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "SKILL-LIST-OK: listing complete",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("List all skills", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(response.contains("SKILL-LIST-OK"), "Response should contain marker but was: $response")

        // Verify tool result contains all skills
        val lastMessages = wireMock.getLastChatRequestMessages()
        val toolMessages =
            lastMessages.filter { msg ->
                msg.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
        assertTrue(toolMessages.isNotEmpty(), "Should have tool result messages")

        val toolContent =
            toolMessages
                .last()
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        // All 6 skills should be listed
        for (i in 1..SKILL_COUNT) {
            assertTrue(
                toolContent.contains("skill-$i"),
                "Tool result should contain skill-$i but was: $toolContent",
            )
            assertTrue(
                toolContent.contains("Description for skill number $i"),
                "Tool result should contain description for skill-$i",
            )
        }
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val MAX_INLINE_SKILLS = 3
        private const val SKILL_COUNT = 6
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
