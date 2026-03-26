package io.github.klaw.e2e.cli

import io.github.klaw.e2e.context.E2eConstants
import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.EngineCliClient
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubToolCall
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration
import java.time.LocalDate

/**
 * E2E tests for `klaw memory` CLI subcommands (Issue #44).
 *
 * Tests cover:
 * 1-5. categories list (empty, after add, json) + facts add
 * 6. facts list by category
 * 7-8. memory search (match + no match)
 * 9-10. categories rename + verify
 * 11-12. categories merge
 * 13. categories delete
 * 14. consolidate too few messages
 * 15. consolidate with messages saves facts
 * 16. consolidate already done without force
 * 17. consolidate force reruns
 * 18. consolidate invalid date
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemoryCommandE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var cliClient: EngineCliClient
    private lateinit var chatClient: WebSocketChatClient
    private val json = Json { ignoreUnknownKeys = true }

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
                        consolidationMinMessages = CONSOLIDATION_MIN_MESSAGES,
                        consolidationCategory = CONSOLIDATION_CATEGORY,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        cliClient = EngineCliClient(containers.engineHost, containers.engineMappedPort)

        chatClient = WebSocketChatClient()
        chatClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        chatClient.close()
        containers.stop()
        wireMock.stop()
    }

    // --- Categories & Facts CRUD ---

    @Test
    @Order(1)
    fun `categories_list returns empty when no categories exist`() {
        val response = cliClient.request("memory_categories_list")
        assertTrue(
            response.contains("No memory categories found", ignoreCase = true),
            "Expected no categories message, got: $response",
        )
    }

    @Test
    @Order(2)
    fun `facts_add creates category and fact`() {
        val response =
            cliClient.request(
                "memory_facts_add",
                mapOf("category" to TEST_CATEGORY, "content" to "Kotlin is the primary language"),
            )
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected success, got: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categories = db.getMemoryCategories()
            assertTrue(
                categories.any { it.name.equals(TEST_CATEGORY, ignoreCase = true) },
                "Expected category '$TEST_CATEGORY', got: ${categories.map { it.name }}",
            )
            val factCount = db.getMemoryFactCountByCategory(TEST_CATEGORY)
            assertEquals(1, factCount, "Expected 1 fact in '$TEST_CATEGORY'")
        }
    }

    @Test
    @Order(3)
    fun `facts_add second fact to same category`() {
        val response =
            cliClient.request(
                "memory_facts_add",
                mapOf("category" to TEST_CATEGORY, "content" to "Micronaut is the DI framework"),
            )
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected success, got: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val factCount = db.getMemoryFactCountByCategory(TEST_CATEGORY)
            assertEquals(2, factCount, "Expected 2 facts in '$TEST_CATEGORY'")
        }
    }

    @Test
    @Order(4)
    fun `categories_list shows category with entry count`() {
        val response = cliClient.request("memory_categories_list")
        assertTrue(
            response.contains(TEST_CATEGORY, ignoreCase = true),
            "Expected '$TEST_CATEGORY' in list, got: $response",
        )
        assertTrue(
            response.contains("2 entries"),
            "Expected '2 entries' in list, got: $response",
        )
    }

    @Test
    @Order(5)
    fun `categories_list json format`() {
        val response = cliClient.request("memory_categories_list", mapOf("json" to "true"))
        val parsed = json.parseToJsonElement(response).jsonObject

        assertTrue(parsed.containsKey("categories"), "Response should have 'categories' field")
        assertTrue(parsed.containsKey("total"), "Response should have 'total' field")

        val categories = parsed["categories"]!!.jsonArray
        assertTrue(categories.isNotEmpty(), "Categories array should not be empty")

        val cat = categories.first().jsonObject
        assertTrue(cat.containsKey("name"), "Category should have 'name' field")
        assertTrue(cat.containsKey("entryCount"), "Category should have 'entryCount' field")
        assertEquals(
            TEST_CATEGORY,
            cat["name"]!!.jsonPrimitive.content,
            "Category name should match",
        )
        assertEquals(
            2L,
            cat["entryCount"]!!.jsonPrimitive.long,
            "Category should have 2 entries",
        )
    }

    @Test
    @Order(6)
    fun `facts_list shows facts for category`() {
        val response = cliClient.request("memory_facts_list", mapOf("category" to TEST_CATEGORY))
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected success, got: $response",
        )
        assertTrue(
            response.contains("Kotlin", ignoreCase = true),
            "Expected first fact content in response, got: $response",
        )
        assertTrue(
            response.contains("Micronaut", ignoreCase = true),
            "Expected second fact content in response, got: $response",
        )
    }

    // --- Search ---

    @Test
    @Order(7)
    fun `search executes without error`() {
        val response = cliClient.request("memory_search", mapOf("query" to "Kotlin"))
        // Search uses ONNX embeddings + FTS5 — results depend on model availability
        // Verify the command runs without errors; result content may be empty
        assertFalse(
            response.contains("\"error\"", ignoreCase = true),
            "Expected no error from search, got: $response",
        )
    }

    @Test
    @Order(8)
    fun `search with no matching results`() {
        val response =
            cliClient.request("memory_search", mapOf("query" to "xyznonexistent9876"))
        // Should not error, should return empty or no-results message
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected no error for empty search, got: $response",
        )
    }

    // --- Categories rename ---

    @Test
    @Order(9)
    fun `categories_rename changes category name`() {
        val response =
            cliClient.request(
                "memory_categories_rename",
                mapOf("old_name" to TEST_CATEGORY, "new_name" to RENAMED_CATEGORY),
            )
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected success, got: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categories = db.getMemoryCategories()
            assertTrue(
                categories.any { it.name.equals(RENAMED_CATEGORY, ignoreCase = true) },
                "Expected renamed category, got: ${categories.map { it.name }}",
            )
            assertFalse(
                categories.any { it.name.equals(TEST_CATEGORY, ignoreCase = true) },
                "Old category name should not exist, got: ${categories.map { it.name }}",
            )
        }
    }

    @Test
    @Order(10)
    fun `categories_list after rename shows new name`() {
        val response = cliClient.request("memory_categories_list")
        assertTrue(
            response.contains(RENAMED_CATEGORY, ignoreCase = true),
            "Expected '$RENAMED_CATEGORY' in list, got: $response",
        )
        assertFalse(
            response.contains(TEST_CATEGORY, ignoreCase = true),
            "Old name '$TEST_CATEGORY' should not appear, got: $response",
        )
    }

    // --- Categories merge ---

    @Test
    @Order(11)
    fun `facts_add to another category for merge test`() {
        val response =
            cliClient.request(
                "memory_facts_add",
                mapOf("category" to OTHER_CATEGORY, "content" to "SQLite is the database"),
            )
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected success, got: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val count = db.getMemoryCategoryCount()
            assertEquals(2, count, "Expected 2 categories before merge")
        }
    }

    @Test
    @Order(12)
    fun `categories_merge combines categories`() {
        val response =
            cliClient.request(
                "memory_categories_merge",
                mapOf("sources" to OTHER_CATEGORY, "target" to RENAMED_CATEGORY),
            )
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected success, got: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categories = db.getMemoryCategories()
            assertFalse(
                categories.any { it.name.equals(OTHER_CATEGORY, ignoreCase = true) },
                "Source category should be gone after merge, got: ${categories.map { it.name }}",
            )
            val factCount = db.getMemoryFactCountByCategory(RENAMED_CATEGORY)
            assertEquals(
                EXPECTED_FACTS_AFTER_MERGE,
                factCount,
                "Target category should have $EXPECTED_FACTS_AFTER_MERGE facts after merge",
            )
        }
    }

    // --- Categories delete ---

    @Test
    @Order(13)
    fun `categories_delete removes category and facts`() {
        val response =
            cliClient.request(
                "memory_categories_delete",
                mapOf("name" to RENAMED_CATEGORY, "keep_facts" to "false"),
            )
        assertFalse(
            response.contains("error", ignoreCase = true),
            "Expected success, got: $response",
        )

        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categoryCount = db.getMemoryCategoryCount()
            assertEquals(0, categoryCount, "Expected 0 categories after delete")
            val factCount = db.getMemoryFactCount()
            assertEquals(0, factCount, "Expected 0 facts after delete with keep_facts=false")
        }
    }

    // --- Consolidation ---

    @Test
    @Order(14)
    fun `consolidate too few messages returns message`() {
        // No messages for a far-future date
        val response =
            cliClient.request(
                "memory_consolidate",
                mapOf("date" to "2099-01-01"),
            )
        assertTrue(
            response.contains("Too few messages", ignoreCase = true),
            "Expected too-few-messages response, got: $response",
        )
    }

    @Test
    @Order(15)
    fun `consolidate with messages saves facts`() {
        // Send chat messages so there's conversation history for today
        wireMock.stubChatResponse(
            "I noted that. ${E2eConstants.ASST_MSG_PADDING}",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        repeat(MESSAGES_TO_SEND) { i ->
            chatClient.sendAndReceive(
                "Important fact #$i about the architecture of this project",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }
        chatClient.drainFrames()

        // Stub consolidation LLM response (tool call to memory_save + final text)
        wireMock.reset()
        wireMock.stubConsolidationResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "cons_call_1",
                            name = "memory_save",
                            arguments =
                                """{"content":"Architecture discussion about the project","category":"$CONSOLIDATION_CATEGORY"}""",
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
        // Also stub default chat response so non-consolidation requests don't fail
        wireMock.stubChatResponse(
            "OK",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val today = LocalDate.now().toString()
        val response =
            cliClient.request(
                "memory_consolidate",
                mapOf("date" to today, "force" to "true"),
            )

        // CLI consolidate is synchronous (request-response), but verify DB as well
        assertTrue(
            response.contains("Consolidation complete", ignoreCase = true) ||
                response.contains("facts saved", ignoreCase = true),
            "Expected consolidation success, got: $response",
        )

        // Verify facts were actually saved in the database
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            awaitCondition(
                description = "consolidation facts appear in database",
                timeout = Duration.ofSeconds(CONSOLIDATION_WAIT_TIMEOUT_SECONDS),
            ) {
                db.getMemoryFactsBySourcePrefix(CONSOLIDATION_SOURCE_PREFIX).isNotEmpty()
            }
        }
    }

    @Test
    @Order(16)
    fun `consolidate already done without force`() {
        val today = LocalDate.now().toString()
        val response =
            cliClient.request(
                "memory_consolidate",
                mapOf("date" to today),
            )
        assertTrue(
            response.contains("Already consolidated", ignoreCase = true),
            "Expected already-consolidated message, got: $response",
        )
    }

    @Test
    @Order(17)
    fun `consolidate force reruns consolidation`() {
        // Stub consolidation again for force re-run
        wireMock.reset()
        wireMock.stubConsolidationResponseSequenceRaw(
            listOf(
                WireMockLlmServer.buildToolCallResponseJson(
                    listOf(
                        StubToolCall(
                            id = "cons_force_1",
                            name = "memory_save",
                            arguments =
                                """{"content":"Re-consolidated architecture facts","category":"$CONSOLIDATION_CATEGORY"}""",
                        ),
                    ),
                ),
                WireMockLlmServer.buildChatResponseJson(
                    "Re-consolidation complete. Saved 1 fact.",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                ),
            ),
        )
        wireMock.stubChatResponse(
            "OK",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )

        val today = LocalDate.now().toString()
        val response =
            cliClient.request(
                "memory_consolidate",
                mapOf("date" to today, "force" to "true"),
            )

        assertTrue(
            response.contains("Consolidation complete", ignoreCase = true) ||
                response.contains("facts saved", ignoreCase = true),
            "Expected consolidation success with --force, got: $response",
        )

        // Verify facts exist in DB after force re-run
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            awaitCondition(
                description = "re-consolidation facts appear in database",
                timeout = Duration.ofSeconds(CONSOLIDATION_WAIT_TIMEOUT_SECONDS),
            ) {
                db.getMemoryFactsBySourcePrefix(CONSOLIDATION_SOURCE_PREFIX).isNotEmpty()
            }
        }
    }

    @Test
    @Order(18)
    fun `consolidate with invalid date returns error`() {
        val response =
            cliClient.request(
                "memory_consolidate",
                mapOf("date" to "not-a-date"),
            )
        assertTrue(
            response.contains("error", ignoreCase = true) ||
                response.contains("invalid date", ignoreCase = true),
            "Expected error for invalid date, got: $response",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MAX_TOOL_CALL_ROUNDS = 3
        private const val CONSOLIDATION_MIN_MESSAGES = 1
        private const val CONSOLIDATION_CATEGORY = "daily-summary"
        private const val CONSOLIDATION_SOURCE_PREFIX = "daily-consolidation:"
        private const val CONSOLIDATION_WAIT_TIMEOUT_SECONDS = 60L
        private const val TEST_CATEGORY = "test-tech-stack"
        private const val RENAMED_CATEGORY = "renamed-tech-stack"
        private const val OTHER_CATEGORY = "other-category"
        private const val EXPECTED_FACTS_AFTER_MERGE = 3
        private const val MESSAGES_TO_SEND = 3
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val STUB_PROMPT_TOKENS = 50
        private const val STUB_COMPLETION_TOKENS = 30
    }
}
