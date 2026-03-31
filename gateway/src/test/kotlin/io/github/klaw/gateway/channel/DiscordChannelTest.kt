package io.github.klaw.gateway.channel

import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.gateway.command.GatewayCommandRegistry
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordChannelTest {
    private val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
    private val commandRegistry = mockk<GatewayCommandRegistry>(relaxed = true)

    private fun makeChannel(
        discordConfig: DiscordConfig? =
            DiscordConfig(
                enabled = true,
                token = "test-token",
                allowedGuilds =
                    listOf(
                        AllowedGuild(
                            guildId = "111222333",
                            allowedChannelIds = emptyList(),
                            allowedUserIds = listOf("100", "200"),
                        ),
                    ),
            ),
    ): DiscordChannel {
        val config =
            GatewayConfig(
                channels = ChannelsConfig(discord = discordConfig),
            )
        return DiscordChannel(config, jsonlWriter, commandRegistry)
    }

    private suspend fun startChannel(
        channel: DiscordChannel,
        selfBotId: String = "999",
    ) {
        channel.buildKordAction = { _, _ -> }
        channel.selfBotId = selfBotId
        channel.start()
    }

    // --- Lifecycle ---

    @Test
    fun `start when discord disabled does nothing`() =
        runTest {
            val channel = makeChannel(discordConfig = DiscordConfig(enabled = false, token = "t"))
            channel.start()
            assertFalse(channel.isAlive())
        }

    @Test
    fun `start when discord config null does nothing`() =
        runTest {
            val channel = makeChannel(discordConfig = null)
            channel.start()
            assertFalse(channel.isAlive())
        }

    @Test
    fun `start when discord token null does nothing`() =
        runTest {
            val channel = makeChannel(discordConfig = DiscordConfig(enabled = true, token = null))
            channel.start()
            assertFalse(channel.isAlive())
        }

    @Test
    fun `start when discord enabled sets alive`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)
            assertTrue(channel.isAlive())
        }

    // --- Guild Allowlist ---

    @Test
    fun `message from allowed guild is forwarded`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var forwarded = false
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "100",
                content = "hello",
                senderName = "TestUser",
            ) { forwarded = true }

            assertTrue(forwarded, "Message from allowed guild/user should be forwarded")
        }

    @Test
    fun `message from disallowed guild is rejected`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var forwarded = false
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "999888777",
                userId = "100",
                content = "hello",
                senderName = "TestUser",
            ) { forwarded = true }

            assertFalse(forwarded, "Message from disallowed guild should be rejected")
        }

    @Test
    fun `message from allowed guild but disallowed channel is rejected`() =
        runTest {
            val channel =
                makeChannel(
                    discordConfig =
                        DiscordConfig(
                            enabled = true,
                            token = "test-token",
                            allowedGuilds =
                                listOf(
                                    AllowedGuild(
                                        guildId = "111222333",
                                        allowedChannelIds = listOf("600"),
                                        allowedUserIds = listOf("100"),
                                    ),
                                ),
                        ),
                )
            startChannel(channel)

            var forwarded = false
            channel.handleIncomingMessage(
                channelId = "700",
                guildId = "111222333",
                userId = "100",
                content = "hello",
                senderName = "TestUser",
            ) { forwarded = true }

            assertFalse(forwarded, "Message from disallowed channel should be rejected")
        }

    @Test
    fun `message from allowed guild with empty allowedChannelIds allows all channels`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var forwarded = false
            channel.handleIncomingMessage(
                channelId = "777",
                guildId = "111222333",
                userId = "100",
                content = "hello",
                senderName = "TestUser",
            ) { forwarded = true }

            assertTrue(forwarded, "Message should be allowed when allowedChannelIds is empty")
        }

    @Test
    fun `message from disallowed user in allowed guild is rejected`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var forwarded = false
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "999888",
                content = "hello",
                senderName = "Stranger",
            ) { forwarded = true }

            assertFalse(forwarded, "Message from disallowed user should be rejected")
        }

    // --- Self-message filter ---

    @Test
    fun `bot own messages are ignored`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel, selfBotId = "100")

            var forwarded = false
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "100",
                content = "hello",
                senderName = "Bot",
            ) { forwarded = true }

            assertFalse(forwarded, "Bot own messages should be ignored")
        }

    // --- Send ---

    @Test
    fun `send delivers message via sendAction`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var sentChannelId: String? = null
            var sentContent: String? = null
            channel.sendAction = { cid, content ->
                sentChannelId = cid
                sentContent = content
            }

            channel.send("discord_12345", OutgoingMessage("hello world"))

            assertEquals("12345", sentChannelId)
            assertEquals("hello world", sentContent)
        }

    @Test
    fun `send splits long messages at 2000 chars`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            val sentChunks = mutableListOf<String>()
            channel.sendAction = { _, content -> sentChunks.add(content) }

            val longMessage = "a".repeat(3500)
            channel.send("discord_12345", OutgoingMessage(longMessage))

            assertTrue(sentChunks.size >= 2, "Long message should be split into multiple chunks")
            sentChunks.forEach { chunk ->
                assertTrue(
                    chunk.length <= DiscordNormalizer.DISCORD_MAX_MESSAGE_LENGTH,
                    "Each chunk must be <= 2000 chars",
                )
            }
        }

    @Test
    fun `send extracts channelId from discord chatId prefix`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var sentChannelId: String? = null
            channel.sendAction = { cid, _ -> sentChannelId = cid }

            channel.send("discord_99887766", OutgoingMessage("test"))

            assertEquals("99887766", sentChannelId)
        }

    // --- Retry & Permanent Errors ---

    @Test
    fun `send retries on transient error`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            val attempts = AtomicInteger(0)
            channel.sendAction = { _, _ ->
                if (attempts.incrementAndGet() == 1) throw IOException("transient")
            }

            channel.send("discord_123", OutgoingMessage("hello"))

            assertEquals(2, attempts.get())
        }

    @Test
    fun `send gives up after max attempts`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            val attempts = AtomicInteger(0)
            channel.sendAction = { _, _ ->
                attempts.incrementAndGet()
                throw IOException("persistent")
            }

            channel.send("discord_123", OutgoingMessage("hello"))

            assertEquals(3, attempts.get())
        }

    // --- Typing ---

    @Test
    fun `typing indicator triggered on message receive`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            startChannel(channel)

            var typingChannelId: String? = null
            channel.typingAction = { cid -> typingChannelId = cid }

            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "100",
                content = "hello",
                senderName = "TestUser",
            ) { }

            advanceTimeBy(1)
            assertEquals("500", typingChannelId)

            channel.stopTyping("500")
        }

    @Test
    fun `typing action called twice after 8s interval`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            startChannel(channel)

            val callCount = AtomicInteger(0)
            channel.typingAction = { callCount.incrementAndGet() }

            channel.startTyping("500")
            advanceTimeBy(1)
            assertEquals(1, callCount.get())

            advanceTimeBy(8_000)
            assertEquals(2, callCount.get())

            channel.stopTyping("500")
        }

    @Test
    fun `typing stops when send is called`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            startChannel(channel)

            channel.typingAction = { }
            channel.sendAction = { _, _ -> }

            channel.startTyping("123")
            assertNotNull(channel.typingJobs["123"])

            channel.send("discord_123", OutgoingMessage("done"))

            assertNull(channel.typingJobs["123"])
        }

    @Test
    fun `all typing jobs cancelled on stop`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            startChannel(channel)

            channel.typingAction = { }

            channel.startTyping("500")
            channel.startTyping("600")
            assertEquals(2, channel.typingJobs.size)

            channel.stop()

            assertTrue(channel.typingJobs.isEmpty())
        }

    // --- Approval ---

    @Test
    fun `approval buttons sent and callback invoked with approve`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var sentApprovalId: String? = null
            channel.sendApprovalAction = { _, _, approvalId ->
                sentApprovalId = approvalId
            }

            var approvalResult: Boolean? = null
            channel.sendApproval(
                "discord_123",
                ApprovalRequestMessage(
                    id = "req-1",
                    chatId = "discord_123",
                    command = "dangerous_cmd",
                    riskScore = 8,
                    timeout = 30,
                ),
            ) { approved -> approvalResult = approved }

            assertEquals("req-1", sentApprovalId)

            channel.handleApprovalResponse("req-1", true)

            assertEquals(true, approvalResult)
        }

    @Test
    fun `approval buttons sent and callback invoked with reject`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            channel.sendApprovalAction = { _, _, _ -> }

            var approvalResult: Boolean? = null
            channel.sendApproval(
                "discord_123",
                ApprovalRequestMessage(
                    id = "req-2",
                    chatId = "discord_123",
                    command = "cmd",
                    riskScore = 5,
                    timeout = 30,
                ),
            ) { approved -> approvalResult = approved }

            channel.handleApprovalResponse("req-2", false)

            assertEquals(false, approvalResult)
        }

    // --- Chat Type ---

    @Test
    fun `thread message has chatType guild_thread`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            channel.typingAction = { }
            startChannel(channel)

            var capturedChatType: String? = null
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "100",
                content = "hello from thread",
                senderName = "TestUser",
                threadType = 11,
            ) { msg -> capturedChatType = msg.chatType }

            assertEquals("guild_thread", capturedChatType)
            channel.stopTyping("500")
        }

    @Test
    fun `forum message has chatType guild_forum`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            channel.typingAction = { }
            startChannel(channel)

            var capturedChatType: String? = null
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "100",
                content = "hello from forum",
                senderName = "TestUser",
                threadType = 15,
            ) { msg -> capturedChatType = msg.chatType }

            assertEquals("guild_forum", capturedChatType)
            channel.stopTyping("500")
        }

    @Test
    fun `non-thread guild message has chatType guild_text`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            channel.typingAction = { }
            startChannel(channel)

            var capturedChatType: String? = null
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "100",
                content = "hello from guild",
                senderName = "TestUser",
            ) { msg -> capturedChatType = msg.chatType }

            assertEquals("guild_text", capturedChatType)
            channel.stopTyping("500")
        }

    @Test
    fun `dm message has chatType dm`() =
        runTest {
            val channel =
                makeChannel(
                    discordConfig =
                        DiscordConfig(
                            enabled = true,
                            token = "test-token",
                            allowedGuilds =
                                listOf(
                                    AllowedGuild(
                                        guildId = "111222333",
                                        allowedChannelIds = emptyList(),
                                        allowedUserIds = listOf("100", "200"),
                                    ),
                                ),
                        ),
                )
            channel.typingScope = this
            channel.typingAction = { }
            startChannel(channel)

            var capturedChatType: String? = null
            channel.handleIncomingMessage(
                channelId = "500",
                guildId = null,
                userId = "100",
                content = "hello dm",
                senderName = "TestUser",
            ) { msg -> capturedChatType = msg.chatType }

            assertEquals("dm", capturedChatType)
            channel.stopTyping("500")
        }

    // --- Approval Edge Cases ---

    @Test
    fun `sendApproval when not alive does not invoke sendApprovalAction`() =
        runTest {
            val channel = makeChannel()
            // Do NOT start the channel — alive stays false

            var sendApprovalCalled = false
            channel.sendApprovalAction = { _, _, _ -> sendApprovalCalled = true }

            var callbackInvoked = false
            channel.sendApproval(
                "discord_123",
                ApprovalRequestMessage(
                    id = "req-dead",
                    chatId = "discord_123",
                    command = "dangerous_cmd",
                    riskScore = 8,
                    timeout = 30,
                ),
            ) { callbackInvoked = true }

            assertFalse(sendApprovalCalled, "sendApprovalAction should not be called when channel is not alive")
            assertFalse(callbackInvoked, "Callback should not be invoked when channel is not alive")
        }

    // --- JSONL Writing ---

    @Test
    fun `inbound message written to JSONL`() =
        runTest {
            val channel = makeChannel()
            channel.typingScope = this
            channel.typingAction = { }
            startChannel(channel)

            channel.handleIncomingMessage(
                channelId = "500",
                guildId = "111222333",
                userId = "100",
                content = "test message",
                senderName = "TestUser",
            ) { }

            coVerify { jsonlWriter.writeInbound(any()) }

            channel.stopTyping("500")
        }

    @Test
    fun `outbound message written to JSONL on send`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            channel.sendAction = { _, _ -> }
            channel.send("discord_123", OutgoingMessage("response text"))

            coVerify { jsonlWriter.writeOutbound("discord_123", "response text") }
        }

    // --- Liveness ---

    @Test
    fun `isAlive returns true after successful send`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)
            channel.sendAction = { _, _ -> }

            channel.send("discord_123", OutgoingMessage("hello"))
            assertTrue(channel.isAlive())
        }

    @Test
    fun `isAlive returns false after send failure`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            channel.sendAction = { _, _ -> }
            channel.send("discord_123", OutgoingMessage("hello"))
            assertTrue(channel.isAlive())

            channel.sendAction = { _, _ -> throw IOException("persistent failure") }
            channel.send("discord_123", OutgoingMessage("goodbye"))
            assertFalse(channel.isAlive())
        }

    @Test
    fun `onBecameAlive fired on start`() =
        runTest {
            val channel = makeChannel()
            var callbackFired = false
            channel.onBecameAlive = { callbackFired = true }

            startChannel(channel)

            assertTrue(callbackFired, "onBecameAlive should fire when channel becomes alive on start")
        }

    @Test
    fun `onBecameAlive not fired when already alive`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            var callbackCount = 0
            channel.onBecameAlive = { callbackCount++ }

            channel.sendAction = { _, _ -> }
            channel.send("discord_123", OutgoingMessage("hello"))
            channel.send("discord_123", OutgoingMessage("world"))

            // Should not fire since channel was already alive from start()
            assertEquals(0, callbackCount)
        }

    @Test
    fun `isAlive returns false after stop`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            assertTrue(channel.isAlive())

            channel.stop()
            assertFalse(channel.isAlive())
        }
}
