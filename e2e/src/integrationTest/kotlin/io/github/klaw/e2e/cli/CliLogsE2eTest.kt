package io.github.klaw.e2e.cli

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollInterval
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

/**
 * E2E tests for `klaw logs` CLI command options (Issue #32).
 *
 * Tests run `klaw logs` inside a CLI Docker container that shares the gateway's
 * conversations data directory. Messages are sent via WebSocket, gateway writes
 * JSONL files, and the CLI reads them.
 *
 * Tests cover:
 * 1. Basic logs output (reads date-based JSONL files)
 * 2. --json flag (machine-readable JSONL output)
 * 3. --chat filter
 * 4. --no-color (no ANSI escape sequences)
 * 5. --limit (restrict number of messages)
 * 6. --max-bytes (limit bytes read)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CliLogsE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
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
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()
        containers.startCli()

        chatClient = WebSocketChatClient()
        chatClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)

        // Send test messages to generate JSONL data
        for (i in 1..MESSAGE_COUNT) {
            wireMock.stubChatResponse("Response-$i from assistant")
            chatClient.sendAndReceive("TestMessage-$i", timeoutMs = RESPONSE_TIMEOUT_MS)
        }

        // Wait for JSONL files to appear in gateway data dir
        val conversationsDir = File(containers.gatewayDataPath, "conversations")
        await
            .atMost(Duration.ofSeconds(JSONL_WAIT_SECONDS))
            .withPollInterval(Duration.ofMillis(POLL_INTERVAL_MS))
            .until {
                conversationsDir.exists() &&
                    conversationsDir.listFiles()?.any { chatDir ->
                        chatDir.isDirectory && chatDir.listFiles()?.any { it.name.endsWith(".jsonl") } == true
                    } == true
            }
    }

    @AfterAll
    fun stopInfrastructure() {
        chatClient.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `klaw logs shows messages from JSONL files`() {
        val result = containers.execCli("logs")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")

        val stdout = result.stdout
        assertTrue(stdout.contains("TestMessage-1"), "Should contain first user message")
        assertTrue(stdout.contains("Response-1 from assistant"), "Should contain first assistant response")
        assertTrue(stdout.contains("TestMessage-$MESSAGE_COUNT"), "Should contain last user message")
    }

    @Test
    @Order(2)
    fun `klaw logs --json outputs valid JSONL`() {
        val result = containers.execCli("logs", "--json")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")

        val lines =
            result.stdout
                .trim()
                .lines()
                .filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty(), "Should have at least one line of output")

        for (line in lines) {
            val parsed = json.parseToJsonElement(line).jsonObject
            assertTrue(parsed.containsKey("id"), "Each line should have 'id' field: $line")
            assertTrue(parsed.containsKey("ts"), "Each line should have 'ts' field: $line")
            assertTrue(parsed.containsKey("role"), "Each line should have 'role' field: $line")
            assertTrue(parsed.containsKey("content"), "Each line should have 'content' field: $line")

            val role = parsed["role"]!!.jsonPrimitive.content
            assertTrue(role in listOf("user", "assistant"), "Role should be 'user' or 'assistant': $role")
        }
    }

    @Test
    @Order(3)
    fun `klaw logs --chat filters by chat ID`() {
        val result = containers.execCli("logs", "--chat", CHAT_ID)

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("TestMessage-1"), "Should show messages for $CHAT_ID")

        val nonexistentResult = containers.execCli("logs", "--chat", "nonexistent_chat")
        assertEquals(0, nonexistentResult.exitCode, "Should exit 0 even for nonexistent chat")
        assertFalse(
            nonexistentResult.stdout.contains("TestMessage"),
            "Should not show messages for nonexistent chat",
        )
    }

    @Test
    @Order(4)
    fun `klaw logs --no-color has no ANSI escape sequences`() {
        val result = containers.execCli("logs", "--no-color")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")
        assertFalse(
            result.stdout.contains("\u001B["),
            "Should not contain ANSI escape sequences with --no-color",
        )
        assertTrue(result.stdout.contains("TestMessage-1"), "Should still show messages")
    }

    @Test
    @Order(5)
    fun `klaw logs --limit restricts output`() {
        val result = containers.execCli("logs", "--limit", "2")

        assertEquals(0, result.exitCode, "Exit code should be 0, stderr: ${result.stderr}")

        val lines =
            result.stdout
                .trim()
                .lines()
                .filter { it.isNotBlank() }
        assertEquals(2, lines.size, "Should show exactly 2 messages with --limit 2")
    }

    @Test
    @Order(6)
    fun `klaw logs --max-bytes limits bytes read`() {
        val fullResult = containers.execCli("logs", "--no-color")
        val limitedResult = containers.execCli("logs", "--max-bytes", "200", "--no-color")

        assertEquals(0, limitedResult.exitCode, "Exit code should be 0, stderr: ${limitedResult.stderr}")

        assertTrue(
            limitedResult.stdout.length < fullResult.stdout.length,
            "Limited output (${limitedResult.stdout.length}) should be shorter than full output (${fullResult.stdout.length})",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val MESSAGE_COUNT = 5
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val JSONL_WAIT_SECONDS = 15L
        private const val POLL_INTERVAL_MS = 500L
        private const val CHAT_ID = "local_ws_default"
    }
}
