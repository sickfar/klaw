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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * E2E test verifying that when memory.injectMemoryMap is false (default),
 * the system prompt does NOT contain a Memory Map section,
 * even though categories are still created in the database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemorySummaryDisabledE2eTest {
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
                        memoryInjectSummary = false,
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
    fun `system prompt does not contain memory map when inject summary disabled`() {
        wireMock.stubChatResponse("Got it.")

        client.sendAndReceive("Hello", timeoutMs = RESPONSE_TIMEOUT_MS)

        val messages = wireMock.getLastChatRequestMessages()
        val systemContent =
            messages
                .first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content ?: ""

        assertFalse(
            systemContent.contains("## Memory Map"),
            "System prompt should NOT contain '## Memory Map' when injectMemoryMap=false",
        )
    }

    @Test
    fun `categories still created in database even when inject summary disabled`() {
        DbInspector(File(containers.engineDataPath, "klaw.db")).use { db ->
            val categoryCount = db.getMemoryCategoryCount()
            assertTrue(
                categoryCount >= 2,
                "Categories should be created in DB regardless of injectMemoryMap setting, got $categoryCount",
            )
        }
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val RESET_DELAY_MS = 1_000L
        private const val CONTEXT_BUDGET_TOKENS = 5000

        private val MEMORY_MD_CONTENT =
            """
            |## Projects
            |Klaw is the main project.
            |
            |## Important Dates
            |Birthday on March 15.
            """.trimMargin()
    }
}
