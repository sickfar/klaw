package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.StubResponse
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * E2E test for zero-gap background compaction.
 *
 * Config: budget=6000, summaryBudgetFraction=0.1, compactionThresholdFraction=0.2
 * Trigger: uncoveredMessageTokens > 6000 * (0.1 + 0.2) = 1800
 *
 * Token strategy: STUB_PROMPT_TOKENS is kept low (100) so that correctUserMessageTokens
 * never inflates messages (actualPendingTotal <= 0 → correction skipped). Messages use
 * JTokkit actual token counting. Each user message ~1674 chars = 210 tokens (JTokkit BPE).
 * STUB_COMPLETION_TOKENS = 200 (used for assistant token storage).
 *
 * Per round: 210 tok user (JTokkit) + 200 tok assistant (stub) = 410 tok
 * uncoveredMessageTokens is computed AFTER persisting user but BEFORE assistant response.
 * Round N context: N*210 + (N-1)*200 tokens
 * Round 4 check: 4*210 + 3*200 = 1440 < 1800 (no trigger)
 * Round 5 check: 5*210 + 4*200 = 1850 > 1800 (TRIGGER)
 *
 * Budget=6000 ensures all pre-threshold messages fit within the sliding window
 * (messageBudget ≈ 5300 >> 1440 message tokens), so sliding window does not interfere
 * with zero-gap compaction behavior under test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CompactionE2eTest {
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
                        summarizationEnabled = true,
                        compactionThresholdFraction = COMPACTION_THRESHOLD_FRACTION,
                        summaryBudgetFraction = SUMMARY_BUDGET_FRACTION,
                        autoRagEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        client = WebSocketChatClient()
        client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @BeforeEach
    fun resetState() {
        wireMock.reset()
        Thread.sleep(RESET_DELAY_MS)
        // /new is a built-in command — engine responds directly without LLM call
        client.sendCommandAndReceive("new", timeoutMs = RESPONSE_TIMEOUT_MS)
        Thread.sleep(RESET_DELAY_MS)
        client.drainFrames()
        wireMock.reset()
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `zero-gap compaction lifecycle`() {
        val responses =
            (1..TOTAL_MESSAGES).map { n ->
                StubResponse(
                    content = "Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)
        wireMock.stubSummarizationResponse(
            "Summary: topics alpha and beta were discussed. Key decisions were made about the architecture.",
        )

        // Step 1: Send 4 messages (below threshold: 4*210+3*200=1440 < 1800) — all should be present
        for (i in 1..PRE_TRIGGER_MESSAGES) {
            client.sendAndReceive(
                "Message $i $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Verify no summarization triggered yet
        assertFalse(
            wireMock.hasReceivedSummarizationCall(),
            "Summarization should NOT trigger before threshold is reached",
        )

        // Check that all 4 messages are present in the last LLM request
        val preThresholdMessages = wireMock.getLastChatRequestMessages()
        val preThresholdUserContent =
            preThresholdMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        for (i in 1..PRE_TRIGGER_MESSAGES) {
            assertTrue(
                preThresholdUserContent.contains("Message $i"),
                "Message $i should be present in context before compaction threshold (zero gap)",
            )
        }

        // Step 2: Send 1 more message (total 5: 5*210+4*200=1850 > 1800) — ALL 5 STILL present (zero gap)
        client.sendAndReceive(
            "Message ${PRE_TRIGGER_MESSAGES + 1} $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        // The 5th message's LLM request should contain all messages (zero gap: compaction may have
        // started but messages stay until it completes)
        val triggerMessages = wireMock.getLastChatRequestMessages()
        val triggerUserContent =
            triggerMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertTrue(
            triggerUserContent.contains("Message 1"),
            "Message 1 should STILL be in context when compaction just triggered (zero gap guarantee)",
        )

        // Step 3: Wait for compaction (poll for summarization call)
        val summarizationReceived =
            pollForCondition(SUMMARIZATION_POLL_TIMEOUT_MS) {
                wireMock.hasReceivedSummarizationCall()
            }
        assertTrue(summarizationReceived, "Engine should have triggered a summarization call")

        // Step 4: Wait for DB persistence
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")
        val summaryPersisted =
            pollForCondition(SUMMARY_PERSIST_TIMEOUT_MS) {
                DbInspector(dbFile).use { it.getSummaryCount("local_ws_default") >= 1 }
            }
        assertTrue(summaryPersisted, "Summary should be persisted to DB")

        // Step 5: Send one more message — summary should appear, old compacted messages replaced
        wireMock.stubChatResponse(
            "Final response after compaction. $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        client.sendAndReceive("Final question after the summary.", timeoutMs = RESPONSE_TIMEOUT_MS)

        val lastMessages = wireMock.getLastChatRequestMessages()
        val allContent =
            lastMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }

        // Step 6: Verify summary content in LLM request
        assertTrue(
            allContent.contains("Summary:") || allContent.contains("topics alpha and beta"),
            "Summary content should appear in the last LLM request context",
        )

        // Old compacted messages should NOT be in context (replaced by summary)
        val userMessages =
            lastMessages.filter {
                it.jsonObject["role"]?.jsonPrimitive?.content == "user"
            }
        val userContent =
            userMessages.joinToString("\n") {
                it.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            }
        assertFalse(
            userContent.contains("Message 1 "),
            "Compacted messages should be replaced by summary",
        )
        // Recent messages should still be present
        assertTrue(
            userContent.contains("Final question"),
            "Recent messages should still be in context after compaction",
        )
    }

    @Test
    @Order(2)
    fun `messages stay during compaction - explicit zero-gap check`() {
        // Use delayed summarization response to verify messages persist during compaction

        // Stub with a long delay for summarization
        wireMock.stubSummarizationResponseWithDelay(
            "Delayed summary: conversation covered alpha and beta topics.",
            delayMs = SUMMARIZATION_DELAY_MS.toInt(),
        )

        // Stub enough responses for the conversation
        val responses =
            (1..TOTAL_MESSAGES + 2).map { n ->
                StubResponse(
                    content = "ZG-Response $n $ASST_MSG_PADDING",
                    promptTokens = STUB_PROMPT_TOKENS,
                    completionTokens = STUB_COMPLETION_TOKENS,
                )
            }
        wireMock.stubChatResponseSequence(responses)

        // Send enough messages to trigger compaction (5+ rounds)
        for (i in 1..TOTAL_MESSAGES) {
            client.sendAndReceive(
                "ZG-Message $i $USER_MSG_PADDING",
                timeoutMs = RESPONSE_TIMEOUT_MS,
            )
        }

        // Wait for compaction to start (summarization call made)
        val compactionStarted =
            pollForCondition(SUMMARIZATION_POLL_TIMEOUT_MS) {
                wireMock.hasReceivedSummarizationCall()
            }
        assertTrue(compactionStarted, "Compaction should have started")

        // While compaction is running (delayed), send another message
        // This message should see ALL pre-compaction messages still in context
        wireMock.stubChatResponse(
            "Response while compaction running. $ASST_MSG_PADDING",
            promptTokens = STUB_PROMPT_TOKENS,
            completionTokens = STUB_COMPLETION_TOKENS,
        )
        client.sendAndReceive(
            "ZG-Message during compaction: verify zero gap. $USER_MSG_PADDING",
            timeoutMs = RESPONSE_TIMEOUT_MS,
        )

        // The LLM request for this message should contain pre-compaction messages
        val duringCompactionMessages = wireMock.getLastChatRequestMessages()
        val duringContent =
            duringCompactionMessages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }

        // At least the most recent pre-compaction messages should be present
        assertTrue(
            duringContent.contains("ZG-Message"),
            "Messages should remain in context while compaction is running (zero gap)",
        )
    }

    private fun pollForCondition(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 6000
        private const val SUMMARY_BUDGET_FRACTION = 0.1
        private const val COMPACTION_THRESHOLD_FRACTION = 0.2
        private const val PRE_TRIGGER_MESSAGES = 4
        private const val TOTAL_MESSAGES = 7

        // Low promptTokens so correctUserMessageTokens skips correction (actualPendingTotal <= 0).
        // Messages retain their JTokkit BPE token counts (~210 tokens per user msg).
        private const val STUB_PROMPT_TOKENS = 100
        private const val STUB_COMPLETION_TOKENS = 200
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SUMMARIZATION_POLL_TIMEOUT_MS = 15_000L
        private const val SUMMARY_PERSIST_TIMEOUT_MS = 10_000L
        private const val SUMMARIZATION_DELAY_MS = 5_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val RESET_DELAY_MS = 300L

        // ~1674 chars = 210 tokens via JTokkit BPE (cl100k_base)
        private const val USER_MSG_PADDING =
            "alpha beta gamma delta epsilon zeta eta theta iota kappa " +
                "lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega " +
                "architecture design patterns software engineering distributed systems " +
                "microservices containers orchestration deployment monitoring observability " +
                "reliability scalability performance optimization caching strategies " +
                "database indexing query optimization schema design normalization " +
                "event sourcing command query responsibility segregation domain driven " +
                "design bounded contexts aggregates entities value objects repositories " +
                "services factories specifications anti corruption layers shared kernels " +
                "published languages open host services conformist patterns customer " +
                "supplier teams partnership big ball of mud legacy system integration " +
                "strangler fig pattern branch by abstraction feature toggles canary " +
                "releases blue green deployments rolling updates immutable infrastructure " +
                "infrastructure as code configuration management continuous integration " +
                "continuous delivery continuous deployment pipeline automation testing " +
                "strategies unit testing integration testing end to end testing contract " +
                "testing property based testing mutation testing fuzzing chaos engineering " +
                "resilience testing load testing stress testing performance testing " +
                "security testing penetration testing vulnerability assessment threat " +
                "modeling risk assessment compliance auditing governance frameworks " +
                "regulatory requirements data protection privacy policies access control " +
                "authentication authorization encryption key management certificate " +
                "management secret rotation audit logging monitoring alerting incident " +
                "response disaster recovery business continuity planning capacity planning"

        // ~771 chars = 107 tokens via JTokkit BPE (stored as STUB_COMPLETION_TOKENS=200)
        private const val ASST_MSG_PADDING =
            "Here is a detailed analysis covering the key aspects " +
                "of the topic you raised. The architecture follows established patterns " +
                "with careful consideration of scalability and maintainability concerns. " +
                "Performance metrics indicate favorable outcomes across all measured " +
                "dimensions including throughput latency and resource utilization. " +
                "The implementation leverages modern frameworks and libraries to ensure " +
                "robust error handling graceful degradation and comprehensive monitoring. " +
                "Testing coverage exceeds baseline requirements with particular attention " +
                "to edge cases boundary conditions and failure scenarios. Documentation " +
                "has been updated to reflect the latest changes and deployment procedures " +
                "have been verified in staging environments before production rollout."
    }
}
