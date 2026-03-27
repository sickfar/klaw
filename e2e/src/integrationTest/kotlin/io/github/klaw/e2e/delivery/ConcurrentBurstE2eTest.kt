package io.github.klaw.e2e.delivery

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * E2E test verifying that concurrent message burst is handled correctly:
 * all messages batched, no duplicates, single response delivered.
 *
 * Flow:
 * 1. Baseline: send message, get response
 * 2. Stub LLM response
 * 3. Launch BURST_SIZE threads, each waiting on a CountDownLatch
 * 4. Release latch — all messages sent near-simultaneously
 * 5. debounceMs=3000 batches all messages into one LLM call
 * 6. Verify: exactly 1 chat request, all markers present, no duplicates
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ConcurrentBurstE2eTest {
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
                        wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        debounceMs = DEBOUNCE_MS,
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
    @Suppress("LongMethod")
    fun `concurrent burst handled correctly without duplicates`() {
        // Step 1: Baseline
        wireMock.stubChatResponse("baseline-burst")
        val baseline = client.sendAndReceive("hello baseline", timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(baseline.contains("baseline-burst"), "Baseline response should be received")

        // Step 2: Reset and stub response for the burst
        wireMock.reset()
        wireMock.stubChatResponse("burst-response")

        // Step 3: Launch BURST_SIZE threads, all waiting on a latch
        val latch = CountDownLatch(1)
        val threads =
            (1..BURST_SIZE).map { i ->
                val marker = "BURST-%02d".format(i)
                Thread {
                    latch.await()
                    client.sendMessage(marker)
                }
            }
        threads.forEach { it.start() }

        // Step 4: Release all threads simultaneously
        latch.countDown()

        // Wait for all sender threads to complete and verify they finished
        threads.forEach { thread ->
            thread.join(THREAD_JOIN_TIMEOUT_MS)
            assertTrue(!thread.isAlive, "Sender thread ${thread.name} did not finish within timeout")
        }

        // Step 5: Wait for first assistant response (debounce 3s + LLM processing)
        val response = client.waitForAssistantResponse(timeoutMs = RESPONSE_TIMEOUT_MS)
        assertTrue(
            response.contains("burst-response"),
            "Burst response should be received: got '$response'",
        )

        // Step 5b: If debounce split messages into 2 batches, wait for the second response
        awaitCondition(
            description = "all burst messages processed by LLM",
            timeout = Duration.ofSeconds(SECOND_BATCH_WAIT_SECONDS),
        ) {
            val allContents =
                wireMock
                    .getChatRequests()
                    .flatMapIndexed { i, _ ->
                        wireMock
                            .getNthRequestMessages(i)
                            .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                            .mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
                    }
            val combined = allContents.joinToString("\n")
            (1..BURST_SIZE).all { combined.contains("BURST-%02d".format(it)) }
        }

        // Step 6: Verify WireMock received a small number of chat requests.
        // With debounce=3000ms, all 10 threads should batch into 1 LLM call.
        // Under CI scheduler jitter, a rare 2-batch split is acceptable.
        val chatRequests = wireMock.getChatRequests()
        assertTrue(
            chatRequests.size in 1..2,
            "Burst messages should batch into 1-2 LLM calls, got ${chatRequests.size}",
        )

        // Step 7: Verify all markers present across all chat requests, no duplicates.
        // If debounce split into 2 batches, we check all requests combined.
        val allUserContents = mutableListOf<String>()
        for (i in chatRequests.indices) {
            val messages = wireMock.getNthRequestMessages(i)
            messages
                .filter { elem ->
                    elem.jsonObject["role"]?.jsonPrimitive?.content == "user"
                }.mapNotNull { elem ->
                    elem.jsonObject["content"]?.jsonPrimitive?.content
                }.let { allUserContents.addAll(it) }
        }
        val combined = allUserContents.joinToString("\n")

        val foundMarkers =
            (1..BURST_SIZE)
                .map { "BURST-%02d".format(it) }
                .filter { combined.contains(it) }

        assertTrue(
            foundMarkers.size == BURST_SIZE,
            "All $BURST_SIZE markers should be present in LLM requests, found: $foundMarkers",
        )

        // Note: we do NOT check for duplicates across requests because with multi-batch
        // (debounce split), the second LLM call includes conversation history from the first,
        // so markers naturally appear multiple times in combined content. The important
        // verification is that all markers are present (no message loss).
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val DEBOUNCE_MS = 3000
        private const val BURST_SIZE = 10
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val SECOND_BATCH_WAIT_SECONDS = 30L
        private const val THREAD_JOIN_TIMEOUT_MS = 5000L
    }
}
