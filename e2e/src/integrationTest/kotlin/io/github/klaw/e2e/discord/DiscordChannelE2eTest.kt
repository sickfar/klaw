package io.github.klaw.e2e.discord

import io.github.klaw.e2e.context.awaitCondition
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DiscordAuthor
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.MockDiscordServer
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

private const val AWAIT_TIMEOUT_SECONDS = 30L

/**
 * E2E tests for Discord channel integration.
 *
 * These tests are in the RED phase: they will FAIL because DiscordChannel
 * is not yet implemented in the gateway module. The test infrastructure
 * (MockDiscordServer, MockDiscordGateway) is verified by unit tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DiscordChannelE2eTest {
    private val wireMock = WireMockLlmServer()
    private val discordMock = MockDiscordServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun setup() {
        wireMock.start()
        wireMock.stubChatResponse("Hello from the bot!")

        discordMock.start()

        val workspace = WorkspaceGenerator.createWorkspace()

        val engineJson =
            ConfigGenerator.engineJson(
                wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                contextBudgetTokens = CONTEXT_BUDGET_TOKENS,
            )
        val gatewayJson =
            ConfigGenerator.gatewayJson(
                discordEnabled = true,
                discordToken = "OTk5ODg4Nzc3NjY2NTU1.fake.token",
                discordApiBaseUrl = discordMock.restBaseUrl,
                discordAllowedGuilds =
                    listOf(
                        Triple(
                            MockDiscordServer.TEST_GUILD_ID,
                            emptyList(),
                            listOf(MockDiscordServer.TEST_USER_ID),
                        ),
                    ),
            )

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson = engineJson,
                gatewayJson = gatewayJson,
                workspaceDir = workspace,
                additionalHostPorts = listOf(discordMock.restPort, discordMock.gatewayPort),
            )
        containers.start()

        awaitCondition(
            description = "Discord bot should connect to mock gateway",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            discordMock.hasConnectedClient
        }
    }

    @AfterAll
    fun teardown() {
        containers.stop()
        discordMock.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `message from discord guild channel reaches engine with correct sender metadata`() {
        val initialRequestCount = wireMock.getChatRequests().size

        discordMock.injectMessage(
            guildId = MockDiscordServer.TEST_GUILD_ID,
            channelId = MockDiscordServer.TEST_CHANNEL_ID,
            userId = MockDiscordServer.TEST_USER_ID,
            username = MockDiscordServer.TEST_USERNAME,
            content = "Hello from Discord!",
        )

        awaitCondition(
            description = "LLM should be called after Discord message",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            wireMock.getChatRequests().size > initialRequestCount
        }

        val messages = wireMock.getLastChatRequestMessages()
        assertTrue(messages.isNotEmpty(), "LLM should have been called with messages")

        // Verify sender metadata in system prompt
        val systemContent =
            messages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }

        assertTrue(
            systemContent.contains("\"platform\":\"discord\""),
            "System prompt should contain platform=discord, got: ${systemContent.take(500)}",
        )
        assertTrue(
            systemContent.contains("\"name\":\"${MockDiscordServer.TEST_USERNAME}\""),
            "System prompt should contain sender name",
        )
    }

    @Test
    @Order(2)
    fun `engine response is sent back to discord channel via REST`() {
        discordMock.reset()
        val initialSentCount = discordMock.getSentMessageCount()

        discordMock.injectMessage(
            guildId = MockDiscordServer.TEST_GUILD_ID,
            channelId = MockDiscordServer.TEST_CHANNEL_ID,
            userId = MockDiscordServer.TEST_USER_ID,
            username = MockDiscordServer.TEST_USERNAME,
            content = "Say hello",
        )

        awaitCondition(
            description = "Bot should respond via Discord REST API",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            discordMock.getSentMessageCount() > initialSentCount
        }

        val sent = discordMock.getSentMessages()
        assertTrue(sent.isNotEmpty(), "Bot should have sent a response via Discord REST API")
    }

    @Test
    @Order(3)
    fun `thread message has correct chatId and chatType guild_thread`() {
        discordMock.reset()
        wireMock.reset()
        wireMock.stubChatResponse("Thread reply")

        discordMock.injectThreadMessage(
            threadId = MockDiscordServer.TEST_THREAD_ID,
            parentId = MockDiscordServer.TEST_CHANNEL_ID,
            guildId = MockDiscordServer.TEST_GUILD_ID,
            author = DiscordAuthor(MockDiscordServer.TEST_USER_ID, MockDiscordServer.TEST_USERNAME),
            content = "Thread message",
        )

        awaitCondition(
            description = "LLM should be called for thread message",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            wireMock.getChatRequests().isNotEmpty()
        }

        val messages = wireMock.getLastChatRequestMessages()
        assertTrue(messages.isNotEmpty(), "LLM should have been called for thread message")

        // Verify thread chat_type in sender metadata
        val systemContent =
            messages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertTrue(
            systemContent.contains("\"chat_type\":\"guild_thread\""),
            "System prompt should contain chat_type=guild_thread",
        )
    }

    @Test
    @Order(4)
    fun `dm message has chat_type dm`() {
        discordMock.reset()
        wireMock.reset()
        wireMock.stubChatResponse("DM reply")

        discordMock.injectDm(
            dmChannelId = MockDiscordServer.TEST_DM_CHANNEL_ID,
            userId = MockDiscordServer.TEST_USER_ID,
            username = MockDiscordServer.TEST_USERNAME,
            content = "DM message",
        )

        awaitCondition(
            description = "LLM should be called for DM message",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            wireMock.getChatRequests().isNotEmpty()
        }

        val messages = wireMock.getLastChatRequestMessages()
        assertTrue(messages.isNotEmpty(), "LLM should have been called for DM")

        // Verify DM chat_type in sender metadata
        val systemContent =
            messages
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
        assertTrue(
            systemContent.contains("\"chat_type\":\"dm\""),
            "System prompt should contain chat_type=dm",
        )
    }

    @Test
    @Order(5)
    fun `long response is split at 2000 chars`() {
        val longResponse = "A".repeat(DISCORD_MESSAGE_LIMIT + SPLIT_OVERFLOW)
        wireMock.reset()
        wireMock.stubChatResponse(longResponse)
        discordMock.reset()

        discordMock.injectMessage(
            guildId = MockDiscordServer.TEST_GUILD_ID,
            channelId = MockDiscordServer.TEST_CHANNEL_ID,
            userId = MockDiscordServer.TEST_USER_ID,
            username = MockDiscordServer.TEST_USERNAME,
            content = "Give long response",
        )

        awaitCondition(
            description = "Long response should result in multiple Discord messages",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            discordMock.getSentMessageCount() >= 2
        }

        val sent = discordMock.getSentMessages()
        assertTrue(sent.size >= 2, "Long response should be split into multiple messages")
    }

    @Test
    @Order(6)
    fun `message from disallowed guild is rejected`() {
        discordMock.reset()
        wireMock.reset()
        wireMock.stubChatResponse("Should not appear")
        val initialRequestCount = wireMock.getChatRequests().size

        discordMock.injectMessage(
            guildId = "999999999999999",
            channelId = "888888888888888",
            userId = MockDiscordServer.TEST_USER_ID,
            username = MockDiscordServer.TEST_USERNAME,
            content = "Should be rejected",
        )

        // Send a valid message afterwards to prove the system is still working
        discordMock.injectMessage(
            guildId = MockDiscordServer.TEST_GUILD_ID,
            channelId = MockDiscordServer.TEST_CHANNEL_ID,
            userId = MockDiscordServer.TEST_USER_ID,
            username = MockDiscordServer.TEST_USERNAME,
            content = "Valid followup",
        )

        awaitCondition(
            description = "Valid message should be processed",
            timeout = Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS),
        ) {
            wireMock.getChatRequests().size > initialRequestCount
        }

        // Verify no response was sent to the disallowed guild's channel
        val sent = discordMock.getSentMessages()
        val sentToDisallowed = sent.filter { it.contains("888888888888888") }
        assertTrue(sentToDisallowed.isEmpty(), "No response should be sent to disallowed guild")
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val DISCORD_MESSAGE_LIMIT = 2000
        private const val SPLIT_OVERFLOW = 1000
    }
}
