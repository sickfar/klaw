package io.github.klaw.gateway.socket

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DeliveryConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramChannelConfig
import io.github.klaw.common.config.WebSocketChannelConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.channel.PermanentDeliveryError
import io.github.klaw.gateway.channel.PermanentErrorReason
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.LocalDate

class GatewayOutboundHandlerBufferTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeAllowlistService(allowedChats: List<AllowedChat>): InboundAllowlistService {
        val config =
            GatewayConfig(
                ChannelsConfig(
                    telegram =
                        mapOf(
                            "default" to
                                TelegramChannelConfig(agentId = "default", token = "tok", allowedChats = allowedChats),
                        ),
                ),
            )
        return InboundAllowlistService(config)
    }

    private fun makeChannel(
        channelName: String = "telegram",
        alive: Boolean = true,
    ): Channel {
        val channel = mockk<Channel>(relaxed = true)
        every { channel.name } returns channelName
        every { channel.isAlive() } returns alive
        every { channel.onBecameAlive = any() } answers {
            // Store the callback for later use in tests
        }
        every { channel.onBecameAlive } returns null
        return channel
    }

    private fun makeHandler(
        channels: List<Channel>,
        allowedChats: List<AllowedChat> = listOf(AllowedChat("telegram_123"), AllowedChat("telegram_456")),
    ): GatewayOutboundHandler =
        GatewayOutboundHandler(
            channels = channels,
            allowlistService = makeAllowlistService(allowedChats),
            jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
            applicationContext = mockk<ApplicationContext>(relaxed = true),
        )

    private fun outboundMsg(
        channel: String = "telegram",
        chatId: String = "telegram_123",
        content: String = "response",
    ) = OutboundSocketMessage(
        channel = channel,
        chatId = chatId,
        content = content,
        replyTo = null,
    )

    @Test
    fun `message buffered when channel not alive`() =
        runBlocking {
            val channel = makeChannel(alive = false)
            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg())

            coVerify(exactly = 0) { channel.send(any(), any()) }
        }

    @Test
    fun `message sent immediately when channel alive`() =
        runBlocking {
            val channel = makeChannel(alive = true)
            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg())

            coVerify(exactly = 1) { channel.send("telegram_123", OutgoingMessage("response", null)) }
        }

    @Test
    fun `buffered messages drained when channel becomes alive`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }

            val handler = makeHandler(listOf(channel))

            // Send while not alive - should buffer
            handler.handleOutbound(outboundMsg(content = "msg1"))
            handler.handleOutbound(outboundMsg(content = "msg2"))

            coVerify(exactly = 0) { channel.send(any(), any()) }

            // Simulate channel becoming alive
            alive = true
            onBecameAliveCallback?.invoke()

            coVerify(exactly = 1) { channel.send("telegram_123", OutgoingMessage("msg1", null)) }
            coVerify(exactly = 1) { channel.send("telegram_123", OutgoingMessage("msg2", null)) }
        }

    @Test
    fun `buffer respects max size oldest dropped`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }

            val handler = makeHandler(listOf(channel))

            // Fill buffer beyond max size
            repeat(GatewayOutboundHandler.MAX_CHANNEL_BUFFER_SIZE + 10) { i ->
                handler.handleOutbound(outboundMsg(content = "msg$i"))
            }

            // Simulate channel becoming alive
            alive = true
            onBecameAliveCallback?.invoke()

            // Should have drained exactly MAX_CHANNEL_BUFFER_SIZE messages (oldest dropped)
            coVerify(exactly = GatewayOutboundHandler.MAX_CHANNEL_BUFFER_SIZE) { channel.send(any(), any()) }

            // Verify the oldest messages (0..9) were dropped - first delivered should be msg10
            coVerify { channel.send("telegram_123", OutgoingMessage("msg10", null)) }
        }

    @Test
    fun `drain delivers messages in order`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val sentMessages = mutableListOf<String>()
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                sentMessages.add(secondArg<OutgoingMessage>().content)
            }

            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(content = "first"))
            handler.handleOutbound(outboundMsg(content = "second"))
            handler.handleOutbound(outboundMsg(content = "third"))

            alive = true
            onBecameAliveCallback?.invoke()

            assertEquals(listOf("first", "second", "third"), sentMessages)
        }

    @Test
    fun `JSONL persistence happens even when channel not alive`() =
        runBlocking {
            val channel = makeChannel(alive = false)
            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(chatId = "telegram_123", content = "buffered message"))

            val today = LocalDate.now().toString()
            val file = File(tempDir, "default/telegram_123/$today.jsonl")
            assertTrue(file.exists(), "JSONL file should be written even when channel not alive")
            val line = file.readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertEquals("assistant", json["role"]?.jsonPrimitive?.content)
        }

    @Test
    fun `multiple channels buffer independently`() =
        runBlocking {
            var tgAlive = false
            var wsAlive = false
            var tgCallback: (suspend () -> Unit)? = null
            var wsCallback: (suspend () -> Unit)? = null

            val tgChannel = mockk<Channel>(relaxed = true)
            every { tgChannel.name } returns "telegram"
            every { tgChannel.isAlive() } answers { tgAlive }
            every { tgChannel.onBecameAlive = any() } answers { tgCallback = firstArg() }

            val wsChannel = mockk<Channel>(relaxed = true)
            every { wsChannel.name } returns "local_ws"
            every { wsChannel.isAlive() } answers { wsAlive }
            every { wsChannel.onBecameAlive = any() } answers { wsCallback = firstArg() }

            val config =
                GatewayConfig(
                    ChannelsConfig(
                        telegram =
                            mapOf(
                                "default" to
                                    TelegramChannelConfig(
                                        agentId = "default",
                                        token = "tok",
                                        allowedChats = listOf(AllowedChat("telegram_123")),
                                    ),
                            ),
                        websocket = mapOf("default" to WebSocketChannelConfig(agentId = "default")),
                    ),
                )
            val handler =
                GatewayOutboundHandler(
                    channels = listOf(tgChannel, wsChannel),
                    allowlistService = InboundAllowlistService(config),
                    jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
                    applicationContext = mockk<ApplicationContext>(relaxed = true),
                )

            // Buffer messages for both channels
            handler.handleOutbound(
                OutboundSocketMessage(
                    channel = "telegram",
                    chatId = "telegram_123",
                    content = "tg_msg",
                    replyTo = null,
                ),
            )
            handler.handleOutbound(
                OutboundSocketMessage(
                    channel = "local_ws",
                    chatId = "local_ws_default",
                    content = "ws_msg",
                    replyTo = null,
                ),
            )

            // Only telegram becomes alive
            tgAlive = true
            tgCallback?.invoke()

            coVerify(exactly = 1) { tgChannel.send("telegram_123", OutgoingMessage("tg_msg", null)) }
            coVerify(exactly = 0) { wsChannel.send(any(), any()) }

            // Now ws becomes alive
            wsAlive = true
            wsCallback?.invoke()

            coVerify(exactly = 1) { wsChannel.send("local_ws_default", OutgoingMessage("ws_msg", null)) }
        }

    @Test
    fun `drain stops and re-buffers remaining on send failure`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            var sendCount = 0
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                sendCount++
                if (sendCount == 2) throw IOException("send failed")
            }

            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(content = "msg1"))
            handler.handleOutbound(outboundMsg(content = "msg2"))
            handler.handleOutbound(outboundMsg(content = "msg3"))

            alive = true
            onBecameAliveCallback?.invoke()

            // msg1 sent ok, msg2 failed, msg3 still buffered
            // After drain stops, msg2 and msg3 should be re-buffered
            assertEquals(2, sendCount)

            // Now simulate channel alive again with no failure
            sendCount = 0
            coEvery { channel.send(any(), any()) } answers { sendCount++ }
            onBecameAliveCallback?.invoke()

            // msg2 and msg3 should be re-sent
            assertEquals(2, sendCount)
        }

    private fun approvalMsg(
        id: String = "req-1",
        chatId: String = "telegram_123",
        command: String = "rm -rf /tmp/test",
        riskScore: Int = 8,
        timeout: Int = 5,
    ) = ApprovalRequestMessage(
        id = id,
        chatId = chatId,
        command = command,
        riskScore = riskScore,
        timeout = timeout,
    )

    private fun makeHandlerWithDrainBudget(
        channels: List<Channel>,
        channelDrainBudgetSeconds: Int,
        allowedChats: List<AllowedChat> = listOf(AllowedChat("telegram_123"), AllowedChat("telegram_456")),
    ): GatewayOutboundHandler {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram =
                            mapOf(
                                "default" to
                                    TelegramChannelConfig(
                                        agentId = "default",
                                        token = "tok",
                                        allowedChats = allowedChats,
                                    ),
                            ),
                    ),
                delivery = DeliveryConfig(channelDrainBudgetSeconds = channelDrainBudgetSeconds),
            )
        return GatewayOutboundHandler(
            channels = channels,
            allowlistService = makeAllowlistService(allowedChats),
            jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
            applicationContext = mockk<ApplicationContext>(relaxed = true),
            gatewayConfig = config,
        )
    }

    @Test
    fun `drain stops and re-buffers remaining when channel drain budget exceeded`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val sentMessages = mutableListOf<String>()
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                Thread.sleep(600) // Simulate slow channel: each send takes 600ms
                sentMessages.add(secondArg<OutgoingMessage>().content)
            }

            // Budget = 1 second. With 600ms per send:
            //   msg1: elapsed=0ms < 1000ms → send (600ms elapsed after)
            //   msg2: elapsed=600ms < 1000ms → send (1200ms elapsed after)
            //   msg3: elapsed=1200ms > 1000ms → re-buffer
            val handler = makeHandlerWithDrainBudget(listOf(channel), channelDrainBudgetSeconds = 1)

            handler.handleOutbound(outboundMsg(content = "msg1"))
            handler.handleOutbound(outboundMsg(content = "msg2"))
            handler.handleOutbound(outboundMsg(content = "msg3"))

            alive = true
            onBecameAliveCallback?.invoke()

            // First two messages should be sent, third re-buffered
            assertEquals(listOf("msg1", "msg2"), sentMessages)

            // Second drain should deliver msg3
            sentMessages.clear()
            coEvery { channel.send(any(), any()) } answers {
                sentMessages.add(secondArg<OutgoingMessage>().content)
            }
            onBecameAliveCallback?.invoke()
            assertEquals(listOf("msg3"), sentMessages)
        }

    private fun makeHandlerWithCallback(
        channels: List<Channel>,
        allowedChats: List<AllowedChat> = listOf(AllowedChat("telegram_123"), AllowedChat("telegram_456")),
        callback: (suspend (SocketMessage) -> Unit)? = null,
    ): GatewayOutboundHandler =
        GatewayOutboundHandler(
            channels = channels,
            allowlistService = makeAllowlistService(allowedChats),
            jsonlWriter = ConversationJsonlWriter(tempDir.absolutePath),
            applicationContext = mockk<ApplicationContext>(relaxed = true),
            approvalCallback = callback,
        )

    @Test
    fun `approval request buffered when channel not alive`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } returns false
            every { channel.onBecameAlive = any() } answers {}

            val handler = makeHandlerWithCallback(listOf(channel))

            handler.handleApprovalRequest(approvalMsg())

            coVerify(exactly = 0) { channel.sendApproval(any(), any(), any()) }
        }

    @Test
    fun `approval not buffered when channel is alive`() =
        runBlocking {
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } returns true
            every { channel.onBecameAlive = any() } answers {}

            val handler = makeHandlerWithCallback(listOf(channel))

            handler.handleApprovalRequest(approvalMsg())

            coVerify(exactly = 1) { channel.sendApproval(eq("telegram_123"), any(), any()) }
        }

    @Test
    fun `buffered approvals drained when channel becomes alive`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }

            val responses = mutableListOf<SocketMessage>()
            val handler = makeHandlerWithCallback(listOf(channel), callback = { responses.add(it) })

            // Buffer two approvals while channel is down
            handler.handleApprovalRequest(approvalMsg(id = "req-1"))
            handler.handleApprovalRequest(approvalMsg(id = "req-2"))

            coVerify(exactly = 0) { channel.sendApproval(any(), any(), any()) }

            // Capture sendApproval callbacks to simulate user response
            val callbackSlots = mutableListOf<suspend (Boolean) -> Unit>()
            coEvery { channel.sendApproval(any(), any(), capture(slot<suspend (Boolean) -> Unit>())) } answers {
                callbackSlots.add(thirdArg())
            }

            // Simulate channel becoming alive
            alive = true
            onBecameAliveCallback?.invoke()

            // Both approvals should have been sent
            coVerify(exactly = 2) { channel.sendApproval(any(), any(), any()) }
        }

    @Test
    fun `buffered approvals delivered in order`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val sentIds = mutableListOf<String>()
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.sendApproval(any(), any(), any()) } answers {
                sentIds.add(secondArg<ApprovalRequestMessage>().id)
            }

            val handler = makeHandlerWithCallback(listOf(channel))

            handler.handleApprovalRequest(approvalMsg(id = "first"))
            handler.handleApprovalRequest(approvalMsg(id = "second"))
            handler.handleApprovalRequest(approvalMsg(id = "third"))

            alive = true
            onBecameAliveCallback?.invoke()

            assertEquals(listOf("first", "second", "third"), sentIds)
        }

    @Test
    fun `approvals drained after regular messages`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val sendOrder = mutableListOf<String>()
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                sendOrder.add("msg:${secondArg<OutgoingMessage>().content}")
            }
            coEvery { channel.sendApproval(any(), any(), any()) } answers {
                sendOrder.add("approval:${secondArg<ApprovalRequestMessage>().id}")
            }

            val handler = makeHandlerWithCallback(listOf(channel))

            // Buffer a regular message and an approval while channel is down
            handler.handleOutbound(outboundMsg(content = "hello"))
            handler.handleApprovalRequest(approvalMsg(id = "req-1"))

            alive = true
            onBecameAliveCallback?.invoke()

            // Regular messages should be drained before approvals
            assertEquals(listOf("msg:hello", "approval:req-1"), sendOrder)
        }

    @Test
    fun `permanent error on send drops message without re-buffering`() =
        runBlocking {
            var alive = true
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            val sentMessages = mutableListOf<String>()
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                throw PermanentDeliveryError(PermanentErrorReason.BOT_BLOCKED)
            }

            val handler = makeHandler(listOf(channel))

            // Send fails with permanent error — should NOT be re-buffered
            handler.handleOutbound(outboundMsg(content = "doomed"))

            // Simulate channel alive again — should have nothing to drain
            coEvery { channel.send(any(), any()) } answers {
                sentMessages.add(secondArg<OutgoingMessage>().content)
            }
            onBecameAliveCallback?.invoke()

            assertTrue(sentMessages.isEmpty(), "Permanently failed message should not be re-buffered")
        }

    @Test
    fun `permanent error during drain skips message and continues with remaining`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            var sendCount = 0
            val sentMessages = mutableListOf<String>()
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                sendCount++
                val content = secondArg<OutgoingMessage>().content
                if (content == "msg2") {
                    throw PermanentDeliveryError(PermanentErrorReason.CHAT_NOT_FOUND)
                }
                sentMessages.add(content)
            }

            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(content = "msg1"))
            handler.handleOutbound(outboundMsg(content = "msg2"))
            handler.handleOutbound(outboundMsg(content = "msg3"))

            alive = true
            onBecameAliveCallback?.invoke()

            // msg1 sent ok, msg2 skipped (permanent error), msg3 sent ok
            assertEquals(listOf("msg1", "msg3"), sentMessages)
        }

    @Test
    fun `transient IOException still re-buffers on drain`() =
        runBlocking {
            var alive = false
            var onBecameAliveCallback: (suspend () -> Unit)? = null
            var sendCount = 0
            val channel = mockk<Channel>(relaxed = true)
            every { channel.name } returns "telegram"
            every { channel.isAlive() } answers { alive }
            every { channel.onBecameAlive = any() } answers {
                onBecameAliveCallback = firstArg()
            }
            coEvery { channel.send(any(), any()) } answers {
                sendCount++
                if (sendCount == 2) throw IOException("transient failure")
            }

            val handler = makeHandler(listOf(channel))

            handler.handleOutbound(outboundMsg(content = "msg1"))
            handler.handleOutbound(outboundMsg(content = "msg2"))
            handler.handleOutbound(outboundMsg(content = "msg3"))

            alive = true
            onBecameAliveCallback?.invoke()

            // msg1 sent, msg2 failed with transient error — msg2+msg3 re-buffered
            assertEquals(2, sendCount)

            // Re-drain should send msg2+msg3
            sendCount = 0
            coEvery { channel.send(any(), any()) } answers { sendCount++ }
            onBecameAliveCallback?.invoke()

            assertEquals(2, sendCount)
        }
}
