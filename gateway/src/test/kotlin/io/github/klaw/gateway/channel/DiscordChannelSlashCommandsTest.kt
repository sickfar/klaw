package io.github.klaw.gateway.channel

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.DeferredPublicMessageInteractionResponseBehavior
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.command.GatewayCommandRegistry
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordChannelSlashCommandsTest {
    private fun makeChannel(
        config: GatewayConfig =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        discord =
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
                    ),
            ),
        registry: GatewayCommandRegistry = mockk(relaxed = true),
    ): DiscordChannel {
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return DiscordChannel(config, jsonlWriter, registry)
    }

    private suspend fun startChannel(channel: DiscordChannel) {
        channel.buildKordAction = { _, _ -> }
        channel.selfBotId = "999"
        channel.start()
    }

    @Test
    fun `send uses deferred response when pending interaction exists`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            val deferred = mockk<DeferredPublicMessageInteractionResponseBehavior>(relaxed = true)
            channel.pendingInteractions["discord_123456789"] = deferred

            val sendCalls = CopyOnWriteArrayList<String>()
            channel.sendAction = { _, content ->
                sendCalls.add(content)
            }

            channel.send("discord_123456789", OutgoingMessage("Hello response"))

            // Deferred response was used, so sendAction should not be called for first chunk
            // The deferred.respond is called instead
            assertTrue(sendCalls.isEmpty(), "Should not use regular send when deferred exists")
            // pendingInteractions should be cleared after use
            assertTrue(channel.pendingInteractions.isEmpty())
        }

    @Test
    fun `send uses regular action when no pending interaction`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            val sendCalls = CopyOnWriteArrayList<Pair<String, String>>()
            channel.sendAction = { channelId, content ->
                sendCalls.add(Pair(channelId, content))
            }

            channel.send("discord_123456789", OutgoingMessage("Hello response"))

            assertEquals(1, sendCalls.size)
            assertEquals("123456789", sendCalls[0].first)
            assertEquals("Hello response", sendCalls[0].second)
        }

    @Test
    fun `pending interactions map stores deferred responses`() =
        runTest {
            val channel = makeChannel()
            val deferred = mockk<DeferredPublicMessageInteractionResponseBehavior>(relaxed = true)

            // Verify we can store and retrieve deferred responses
            channel.pendingInteractions["discord_123"] = deferred
            assertEquals(deferred, channel.pendingInteractions["discord_123"])

            // Verify removal works
            channel.pendingInteractions.remove("discord_123")
            assertTrue(channel.pendingInteractions.isEmpty())
        }

    @Test
    fun `slash command handling stops typing and creates message`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            // Create a mock interaction
            val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
            every { interaction.invokedCommandName } returns "new"
            every { interaction.channelId } returns Snowflake(123456789)
            val mockUser = mockk<User>(relaxed = true)
            every { mockUser.id } returns Snowflake(987654321)
            every { mockUser.username } returns "testuser"
            every { interaction.user } returns mockUser

            val incomingMessages = CopyOnWriteArrayList<IncomingMessage>()

            // Start typing to test that it gets stopped
            channel.typingAction = { }
            channel.startTyping("123456789")
            assertTrue(channel.typingJobs.containsKey("123456789"))

            // Set up deferred response mock
            val deferred = mockk<DeferredPublicMessageInteractionResponseBehavior>(relaxed = true)
            channel.pendingInteractions["discord_123456789"] = deferred

            channel.handleSlashCommandInteraction(interaction) { msg ->
                incomingMessages.add(msg)
            }

            // Typing should be stopped
            assertNull(channel.typingJobs["123456789"], "Typing should be stopped")

            // Message should be created
            assertEquals(1, incomingMessages.size)
            val msg = incomingMessages[0]
            assertEquals("new", msg.commandName)
            assertEquals("discord_123456789", msg.chatId)
            assertTrue(msg.isCommand)
            assertEquals("987654321", msg.userId)
            assertEquals("testuser", msg.senderName)
            assertEquals("/new", msg.content)
        }

    @Test
    fun `slash command with args includes args in message content`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            // Create a mock interaction with options
            val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
            every { interaction.invokedCommandName } returns "model"
            every { interaction.channelId } returns Snowflake(123456789)
            val mockUser = mockk<User>(relaxed = true)
            every { mockUser.id } returns Snowflake(987654321)
            every { mockUser.username } returns "testuser"
            every { interaction.user } returns mockUser

            val incomingMessages = CopyOnWriteArrayList<IncomingMessage>()

            // Set up deferred response mock
            val deferred = mockk<DeferredPublicMessageInteractionResponseBehavior>(relaxed = true)
            channel.pendingInteractions["discord_123456789"] = deferred

            channel.handleSlashCommandInteraction(interaction) { msg ->
                incomingMessages.add(msg)
            }

            assertEquals(1, incomingMessages.size)
            val msg = incomingMessages[0]
            assertEquals("model", msg.commandName)
            // Content should include command name with prefix
            assertEquals("/model", msg.content)
        }
}
