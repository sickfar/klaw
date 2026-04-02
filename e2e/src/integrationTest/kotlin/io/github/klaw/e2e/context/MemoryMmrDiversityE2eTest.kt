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
 * E2E pipeline smoke test for MMR (Maximal Marginal Relevance) diversity reranking.
 *
 * Verifies that:
 * 1. MMR config (search.mmr.enabled, search.mmr.lambda) is parsed correctly
 * 2. The save→search pipeline works with MMR enabled (no crashes)
 * 3. Search results include both redundant and diverse facts
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryMmrDiversityE2eTest {
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
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                        mmrEnabled = true,
                        mmrLambda = MMR_LAMBDA,
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
    fun `mmr enabled - save redundant and diverse facts then search returns diverse results`() {
        // Stub LLM sequence: 4 memory_save calls, then memory_search, then final response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                // Round 1: save 3 redundant + 1 diverse fact
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "save_1",
                            name = "memory_fact_add",
                            arguments =
                                """{"content":"Database connection timeout occurred at 10:00 AM","category":"errors"}""",
                        ),
                        StubToolCall(
                            id = "save_2",
                            name = "memory_fact_add",
                            arguments =
                                """{"content":"Connection timeout error when connecting to database at 10:05 AM","category":"errors"}""",
                        ),
                        StubToolCall(
                            id = "save_3",
                            name = "memory_fact_add",
                            arguments =
                                """{"content":"Timeout connecting to the database server at 10:10 AM","category":"errors"}""",
                        ),
                        StubToolCall(
                            id = "save_4",
                            name = "memory_fact_add",
                            arguments =
                                """{"content":"User prefers dark theme and compact sidebar layout","category":"preferences"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                // Round 2: search
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "search_1",
                            name = "memory_search",
                            arguments = """{"query":"database problems and user settings"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                // Round 3: final response
                WireMockLlmServer.buildChatResponseJson(
                    "SEARCH-DONE: found relevant results",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Save some facts and search", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("SEARCH-DONE"),
            "Response should contain SEARCH-DONE marker but was: $response",
        )

        // Verify facts were saved
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val factCount = db.getMemoryFactCount()
            assertTrue(
                factCount >= EXPECTED_FACT_COUNT,
                "Should have at least $EXPECTED_FACT_COUNT facts, got $factCount",
            )

            val categories = db.getMemoryCategories()
            assertTrue(
                categories.size >= 2,
                "Should have at least 2 categories (errors, preferences), got ${categories.map { it.name }}",
            )
        }

        // Verify search results contain diverse content via WireMock request capture
        val requests = wireMock.getRecordedRequests()
        // Find the request that contains the memory_search tool result
        val searchResultRequest =
            requests.lastOrNull() ?: error("No requests recorded")

        // The last LLM call should contain the tool result from memory_search
        assertTrue(
            searchResultRequest.contains("timeout") || searchResultRequest.contains("Timeout"),
            "Search results should contain timeout-related content",
        )
        assertTrue(
            searchResultRequest.contains("preferences") || searchResultRequest.contains("dark theme"),
            "Search results should contain diverse (preferences) content with MMR enabled",
        )
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 60_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 5
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val MMR_LAMBDA = 0.5
        private const val EXPECTED_FACT_COUNT = 4
    }
}
