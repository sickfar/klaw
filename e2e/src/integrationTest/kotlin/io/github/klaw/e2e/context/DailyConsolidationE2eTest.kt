package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration

/**
 * E2E test verifying daily memory consolidation:
 * - Cron fires and triggers consolidation
 * - LLM receives conversation history and returns memory_save tool calls
 * - Facts are saved to the database with the correct source prefix
 * - Idempotency: second cron tick does not create duplicate facts
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DailyConsolidationE2eTest {
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
                        maxToolCallRounds = MAX_TOOL_CALL_ROUNDS,
                        consolidationEnabled = true,
                        consolidationCron = CONSOLIDATION_CRON,
                        consolidationMinMessages = CONSOLIDATION_MIN_MESSAGES,
                        consolidationCategory = CONSOLIDATION_CATEGORY,
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

    @Test
    @Order(1)
    fun `consolidation creates facts via memory_save tool calls`() {
        // Stub chat responses for the user messages we'll send
        wireMock.stubChatResponse(
            "I noted that. ${E2eConstants.ASST_MSG_PADDING}",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        // Send a few messages to create conversation history
        repeat(MESSAGES_TO_SEND) { i ->
            client.sendAndReceive(
                "Important decision #$i: we chose Kotlin for the project",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }
        client.drainFrames()

        // Now stub consolidation LLM responses:
        // First call returns a memory_save tool call, second call returns final text
        wireMock.reset()
        wireMock.stubConsolidationResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "cons_call_1",
                            name = "memory_save",
                            arguments =
                                """{"content":"The user decided to use Kotlin for the project","category":"$CONSOLIDATION_CATEGORY"}""",
                        ),
                    ),
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Consolidation complete. Saved 1 fact.",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )
        // Also stub a default chat response so non-consolidation requests don't fail
        wireMock.stubChatResponse(
            "OK",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        // Wait for the consolidation cron to fire and create facts in the database
        awaitCondition(
            description = "consolidation facts appear in database",
            timeout = Duration.ofSeconds(CONSOLIDATION_WAIT_TIMEOUT_SECONDS),
        ) {
            DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
                db.getMemoryFactsBySourcePrefix(SOURCE_PREFIX).isNotEmpty()
            }
        }

        // Verify: facts exist with correct source prefix and category
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val facts = db.getMemoryFactsBySourcePrefix(SOURCE_PREFIX)
            assertTrue(
                facts.isNotEmpty(),
                "Should have at least 1 consolidation fact, got ${facts.size}",
            )
            assertTrue(
                facts.all { it.source.startsWith(SOURCE_PREFIX) },
                "All facts should have source prefix '$SOURCE_PREFIX', got: ${facts.map { it.source }}",
            )

            val categories = db.getMemoryCategories()
            assertTrue(
                categories.any { it.name.equals(CONSOLIDATION_CATEGORY, ignoreCase = true) },
                "Should have category '$CONSOLIDATION_CATEGORY', got: ${categories.map { it.name }}",
            )
        }

        // Verify WireMock received a consolidation request
        assertTrue(
            wireMock.getConsolidationCallCount() > 0,
            "WireMock should have received at least 1 consolidation request",
        )
    }

    @Test
    @Order(2)
    fun `second cron tick is idempotent and does not create duplicate facts`() {
        // Record current fact count
        val factCountBefore =
            DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
                db.getMemoryFactsBySourcePrefix(SOURCE_PREFIX).size
            }

        // Wait for another cron tick (cron fires every 5 seconds)
        Thread.sleep(IDEMPOTENCY_WAIT_MS)

        // Verify fact count hasn't changed
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val factCountAfter = db.getMemoryFactsBySourcePrefix(SOURCE_PREFIX).size
            assertEquals(
                factCountBefore,
                factCountAfter,
                "Fact count should not change after idempotent cron tick",
            )
        }
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val CONSOLIDATION_CRON = "*/5 * * * * ?"
        private const val CONSOLIDATION_MIN_MESSAGES = 1
        private const val CONSOLIDATION_CATEGORY = "daily-summary"
        private const val SOURCE_PREFIX = "daily-consolidation:"
        private const val MESSAGES_TO_SEND = 3
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
        private const val CONSOLIDATION_WAIT_TIMEOUT_SECONDS = 60L
        private const val IDEMPOTENCY_WAIT_MS = 8_000L
    }
}
