package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
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
 * E2E test verifying that when memory.injectMemoryMap is enabled,
 * the system prompt includes a "Memory Map" section with categories
 * built from the database (initially populated from MEMORY.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemorySummaryE2eTest {
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
                        contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                        memoryInjectSummary = true,
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
    @Order(1)
    fun `memory map populated from MEMORY_MD categories on startup`() {
        wireMock.stubChatResponse("Got it.")

        client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)

        val messages = wireMock.getLastChatRequestMessages()
        val systemContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            systemContent.contains("## Memory Map"),
            "System prompt should contain '## Memory Map' when injectMemoryMap=true",
        )
        assertTrue(
            systemContent.contains("Projects"),
            "Memory map should contain 'Projects' category from MEMORY.md",
        )
        assertTrue(
            systemContent.contains("Important Dates"),
            "Memory map should contain 'Important Dates' category from MEMORY.md",
        )
        assertTrue(
            systemContent.contains("User Preferences"),
            "Memory map should contain 'User Preferences' category from MEMORY.md",
        )
    }

    @Test
    @Order(2)
    fun `memory map shows entry counts`() {
        wireMock.stubChatResponse("Got it.")

        client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)

        val messages = wireMock.getLastChatRequestMessages()
        val systemContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertTrue(
            systemContent.contains("entries)"),
            "Memory map should show entry counts",
        )
    }

    @Test
    @Order(3)
    fun `categories created in database from MEMORY_MD`() {
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categories = db.getMemoryCategories()
            assertTrue(
                categories.size >= EXPECTED_CATEGORY_COUNT,
                "Should have at least $EXPECTED_CATEGORY_COUNT categories from MEMORY.md, got ${categories.size}",
            )
            assertTrue(
                categories.any { it.name == "Projects" },
                "Should have 'Projects' category",
            )
        }
    }

    @Test
    @Order(4)
    fun `facts stored as individual lines in database`() {
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val factCount = db.getMemoryFactCount()
            assertTrue(
                factCount >= EXPECTED_TOTAL_FACTS,
                "Should have at least $EXPECTED_TOTAL_FACTS facts (lines from MEMORY.md), got $factCount",
            )
            val projectFacts = db.getMemoryFactCountByCategory("Projects")
            assertTrue(
                projectFacts >= 2,
                "Projects category should have at least 2 facts, got $projectFacts",
            )
        }
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val EXPECTED_CATEGORY_COUNT = 3
        private const val EXPECTED_TOTAL_FACTS = 6

        private val MEMORY_MD_CONTENT =
            """
            |## Projects
            |Klaw is the main project.
            |It has two processes: Gateway and Engine.
            |
            |## Important Dates
            |Birthday on March 15.
            |Anniversary on June 20.
            |
            |## User Preferences
            |Prefers concise responses.
            |Uses dark mode.
            """.trimMargin()
    }
}
