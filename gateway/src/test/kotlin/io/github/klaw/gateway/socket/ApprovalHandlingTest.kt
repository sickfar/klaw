package io.github.klaw.gateway.socket

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.ApprovalResponseMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.micronaut.context.ApplicationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel

class ApprovalHandlingTest {
    @TempDir
    lateinit var tempDir: File

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private fun makeAllowlistService(allowedChats: List<AllowedChat>): InboundAllowlistService {
        val config =
            GatewayConfig(
                ChannelsConfig(TelegramConfig("tok", allowedChats)),
            )
        return InboundAllowlistService(config)
    }

    @Test
    fun `approval request dispatched to channel sendApproval`() =
        runBlocking {
            val telegramChannel = mockk<Channel>(relaxed = true)
            every { telegramChannel.name } returns "telegram"
            every { telegramChannel.isAlive() } returns true
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(telegramChannel),
                    allowlistService = makeAllowlistService(listOf(AllowedChat("telegram_123"))),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )

            val request =
                ApprovalRequestMessage(
                    id = "req-1",
                    chatId = "telegram_123",
                    command = "rm -rf /tmp/test",
                    riskScore = 8,
                    timeout = 5,
                )
            handler.handleApprovalRequest(request)

            coVerify(exactly = 1) { telegramChannel.sendApproval(eq("telegram_123"), eq(request), any()) }
        }

    @Test
    fun `approval request blocked when chatId not in whitelist`() =
        runBlocking {
            val telegramChannel = mockk<Channel>(relaxed = true)
            every { telegramChannel.name } returns "telegram"
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(telegramChannel),
                    allowlistService = makeAllowlistService(emptyList()),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )

            val request =
                ApprovalRequestMessage(
                    id = "req-1",
                    chatId = "telegram_999",
                    command = "rm -rf /",
                    riskScore = 10,
                    timeout = 5,
                )
            handler.handleApprovalRequest(request)

            coVerify(exactly = 0) { telegramChannel.sendApproval(any(), any(), any()) }
        }

    @Test
    fun `approval response sent back to engine via callback`() =
        runBlocking {
            val sentResponses = mutableListOf<SocketMessage>()
            val telegramChannel = mockk<Channel>(relaxed = true)
            every { telegramChannel.name } returns "telegram"
            every { telegramChannel.isAlive() } returns true

            val callbackSlot = slot<suspend (Boolean) -> Unit>()
            coEvery { telegramChannel.sendApproval(any(), any(), capture(callbackSlot)) } returns Unit

            val handler =
                GatewayOutboundHandler(
                    channels = listOf(telegramChannel),
                    allowlistService = makeAllowlistService(listOf(AllowedChat("telegram_123"))),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                    approvalCallback = { msg -> sentResponses.add(msg) },
                )

            val request =
                ApprovalRequestMessage(
                    id = "req-1",
                    chatId = "telegram_123",
                    command = "apt upgrade",
                    riskScore = 7,
                    timeout = 5,
                )
            handler.handleApprovalRequest(request)

            // Simulate user clicking "approve"
            callbackSlot.captured.invoke(true)

            assertEquals(1, sentResponses.size)
            val response = sentResponses[0] as ApprovalResponseMessage
            assertEquals("req-1", response.id)
            assertTrue(response.approved)
        }

    @Test
    fun `approval rejection sent back to engine via callback`() =
        runBlocking {
            val sentResponses = mutableListOf<SocketMessage>()
            val telegramChannel = mockk<Channel>(relaxed = true)
            every { telegramChannel.name } returns "telegram"
            every { telegramChannel.isAlive() } returns true

            val callbackSlot = slot<suspend (Boolean) -> Unit>()
            coEvery { telegramChannel.sendApproval(any(), any(), capture(callbackSlot)) } returns Unit

            val handler =
                GatewayOutboundHandler(
                    channels = listOf(telegramChannel),
                    allowlistService = makeAllowlistService(listOf(AllowedChat("telegram_123"))),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                    approvalCallback = { msg -> sentResponses.add(msg) },
                )

            val request =
                ApprovalRequestMessage(
                    id = "req-2",
                    chatId = "telegram_123",
                    command = "rm -rf /",
                    riskScore = 10,
                    timeout = 5,
                )
            handler.handleApprovalRequest(request)

            // Simulate user clicking "reject"
            callbackSlot.captured.invoke(false)

            assertEquals(1, sentResponses.size)
            val response = sentResponses[0] as ApprovalResponseMessage
            assertEquals("req-2", response.id)
            assertFalse(response.approved)
        }

    @Test
    fun `handleIncomingLine dispatches ApprovalRequestMessage to handler`() {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val serverPort = (server.localAddress as InetSocketAddress).port

        val handledRequests = mutableListOf<ApprovalRequestMessage>()
        val handler =
            object : OutboundMessageHandler {
                override suspend fun handleOutbound(message: OutboundSocketMessage) = Unit

                override suspend fun handleShutdown() = Unit

                override suspend fun handleRestartRequest() = Unit

                override suspend fun handleApprovalRequest(message: ApprovalRequestMessage) {
                    handledRequests.add(message)
                }
            }

        val bufferPath = File(tempDir, "buffer.jsonl").absolutePath
        val buffer = GatewayBuffer(bufferPath)
        val client =
            EngineSocketClient(
                host = "127.0.0.1",
                port = serverPort,
                buffer = buffer,
                outboundHandler = handler,
            )

        try {
            client.start()
            Thread.sleep(300)

            // Accept connection and send an ApprovalRequestMessage
            val conn = server.accept()
            val writer = java.io.PrintWriter(Channels.newOutputStream(conn), true)
            val approvalJson =
                json.encodeToString<SocketMessage>(
                    ApprovalRequestMessage(
                        id = "req-42",
                        chatId = "telegram_123",
                        command = "systemctl restart nginx",
                        riskScore = 6,
                        timeout = 5,
                    ),
                )
            writer.println(approvalJson)

            Thread.sleep(300)

            assertEquals(1, handledRequests.size)
            assertEquals("req-42", handledRequests[0].id)
            assertEquals("systemctl restart nginx", handledRequests[0].command)
        } finally {
            client.stop()
            server.close()
        }
    }
}
