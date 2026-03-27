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
 * E2E pipeline smoke test for temporal decay scoring.
 *
 * Verifies that:
 * 1. Temporal decay config (search.temporalDecay.enabled, halfLifeDays) is parsed correctly
 * 2. The save→search pipeline works with temporal decay enabled (no crashes)
 * 3. Search returns results (recently saved facts are not penalized much)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryTemporalDecayE2eTest {
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
                        temporalDecayEnabled = true,
                        temporalDecayHalfLifeDays = HALF_LIFE_DAYS,
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
    fun `temporal decay enabled - save facts and search returns results without crash`() {
        // Stub LLM: save 2 facts, then search, then final response
        wireMock.stubChatResponseSequenceRaw(
            listOf(
                // Round 1: save 2 facts
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "save_1",
                            name = "memory_save",
                            arguments =
                                """{"content":"The server runs on Raspberry Pi 5 with 8GB RAM","category":"infrastructure"}""",
                        ),
                        StubToolCall(
                            id = "save_2",
                            name = "memory_save",
                            arguments =
                                """{"content":"Weekly backup runs every Sunday at 3 AM","category":"operations"}""",
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
                            arguments = """{"query":"server infrastructure and operations"}""",
                        ),
                    ),
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
                // Round 3: final response
                WireMockLlmServer.buildChatResponseJson(
                    "DECAY-SEARCH-DONE: results retrieved with temporal decay",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )

        val response = client.sendAndReceive("Save and search with decay", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("DECAY-SEARCH-DONE"),
            "Response should contain DECAY-SEARCH-DONE marker but was: $response",
        )

        // Verify facts were saved
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val factCount = db.getMemoryFactCount()
            assertTrue(
                factCount >= EXPECTED_FACT_COUNT,
                "Should have at least $EXPECTED_FACT_COUNT facts, got $factCount",
            )
        }

        // Verify search returned results (recent facts should not be heavily penalized)
        val requests = wireMock.getRecordedRequests()
        val lastRequest = requests.lastOrNull() ?: error("No requests recorded")
        // The tool result from memory_search should contain fact content
        assertTrue(
            lastRequest.contains("Raspberry Pi") || lastRequest.contains("backup"),
            "Search results should contain saved fact content (recent facts barely decayed)",
        )
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 60_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 5
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val HALF_LIFE_DAYS = 30
        private const val EXPECTED_FACT_COUNT = 2
    }
}
