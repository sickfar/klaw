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
 * E2E test verifying that a memory_save tool call creates a category and fact
 * in the database. Uses an empty workspace (no MEMORY.md) so the only category
 * and fact come from the tool call itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemorySaveWithCategoryE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
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
    fun `memory_save tool call creates category and fact in database`() {
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "call_1",
                            name = "memory_save",
                            arguments = """{"content":"test fact","category":"Test Topic"}""",
                        ),
                    ),
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "MEMORY-SAVE-DONE: I saved the fact.",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Remember this info", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("MEMORY-SAVE-DONE"),
            "Response should contain MEMORY-SAVE-DONE marker but was: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categories = db.getMemoryCategories()
            assertTrue(
                categories.isNotEmpty(),
                "Should have at least 1 category after memory_save, got ${categories.size}",
            )
            assertTrue(
                categories.any { it.name.equals("Test Topic", ignoreCase = true) },
                "Should have a category named 'Test Topic', got: ${categories.map { it.name }}",
            )

            val factCount = db.getMemoryFactCount()
            assertTrue(
                factCount >= 1,
                "Should have at least 1 fact after memory_save, got $factCount",
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
    }
}
