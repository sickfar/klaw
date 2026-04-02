package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * E2E test verifying that category matching is case insensitive.
 * MEMORY.md contains a "Projects" category with one fact. A memory_save
 * tool call with category="projects" (lowercase) should merge into the
 * existing category rather than creating a duplicate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryCaseInsensitiveE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
        WorkspaceGenerator.createMemoryMd(
            workspaceDir,
            MEMORY_MD_CONTENT,
        )
        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        memoryInjectSummary = true,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
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
    fun resetState() {
        wireMock.reset()
        Thread.sleep(RESET_DELAY_MS)
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()
    }

    @Test
    fun `memory_save with lowercase category merges into existing category`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_1",
                            name = "memory_fact_add",
                            arguments = """{"content":"new fact","category":"projects"}""",
                        ),
                    ),
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "CASE-MERGE-DONE: fact saved.",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Save this project info", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("CASE-MERGE-DONE"),
            "Response should contain CASE-MERGE-DONE marker but was: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categories = db.getMemoryCategories()
            assertTrue(
                categories.size == 1,
                "Should have exactly 1 category (case-insensitive merge), got ${categories.size}: " +
                    categories.map { it.name },
            )

            val factCount = db.getMemoryFactCountByCategory("Projects")
            assertTrue(
                factCount >= 2,
                "Projects category should have at least 2 facts (1 from MEMORY.md + 1 from tool call), got $factCount",
            )
        }
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 2
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30

        private val MEMORY_MD_CONTENT =
            """
            |## Projects
            |Klaw.
            """.trimMargin()
    }
}
