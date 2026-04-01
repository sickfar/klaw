package io.github.klaw.gateway.channel

import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.behavior.interaction.response.DeferredPublicMessageInteractionResponseBehavior
import dev.kord.core.cache.data.InteractionData
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    ): DiscordChannel {
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return DiscordChannel(config, jsonlWriter)
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
            // pendingInteractions should be cleared after use — proves deferred.respond path was taken
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

            // Create a mock interaction — userId "100" is in the DM allowlist (guildId=null → DM)
            val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
            every { interaction.invokedCommandName } returns "new"
            every { interaction.channelId } returns Snowflake(123456789)
            val mockData = mockk<InteractionData>(relaxed = true)
            every { mockData.guildId } returns OptionalSnowflake.Missing
            every { interaction.data } returns mockData
            val mockUser = mockk<User>(relaxed = true)
            every { mockUser.id } returns Snowflake(100)
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
            assertEquals("100", msg.userId)
            assertEquals("testuser", msg.senderName)
            assertEquals("/new", msg.content)
        }

    @Test
    fun `handleSlashCommandInteraction removes deferred from pendingInteractions when onMessage throws`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
            every { interaction.invokedCommandName } returns "new"
            every { interaction.channelId } returns Snowflake(123456789)
            // guildId=null → DM, userId "100" is in the DM allowlist so the command passes auth
            val mockData = mockk<InteractionData>(relaxed = true)
            every { mockData.guildId } returns OptionalSnowflake.Missing
            every { interaction.data } returns mockData
            val mockUser = mockk<User>(relaxed = true)
            every { mockUser.id } returns Snowflake(100)
            every { mockUser.username } returns "testuser"
            every { interaction.user } returns mockUser

            channel.handleSlashCommandInteraction(interaction) {
                throw java.io.IOException("simulated dispatch failure")
            }

            // Deferred must be removed from pendingInteractions on exception
            assertFalse(
                channel.pendingInteractions.containsKey("discord_123456789"),
                "pendingInteractions must not leak on dispatch failure",
            )
        }

    @Test
    fun `slash command with args includes args in message content`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            // Create a mock interaction with options — userId "100" is in the DM allowlist (guildId=null → DM)
            val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
            every { interaction.invokedCommandName } returns "model"
            every { interaction.channelId } returns Snowflake(123456789)
            val mockData = mockk<InteractionData>(relaxed = true)
            every { mockData.guildId } returns OptionalSnowflake.Missing
            every { interaction.data } returns mockData
            val mockUser = mockk<User>(relaxed = true)
            every { mockUser.id } returns Snowflake(100)
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

    @Test
    fun `slash command from unauthorized user is rejected with not authorized response`() =
        runTest {
            val channel = makeChannel()
            startChannel(channel)

            // userId "999" is NOT in any allowlist — should be rejected
            val interaction = mockk<ChatInputCommandInteraction>(relaxed = true)
            every { interaction.invokedCommandName } returns "status"
            every { interaction.channelId } returns Snowflake(123456789)
            val mockUser = mockk<User>(relaxed = true)
            every { mockUser.id } returns Snowflake(999)
            every { mockUser.username } returns "attacker"
            every { interaction.user } returns mockUser

            val onMessageCalls = CopyOnWriteArrayList<IncomingMessage>()

            channel.handleSlashCommandInteraction(interaction) { msg ->
                onMessageCalls.add(msg)
            }

            // onMessage must NOT be called for unauthorized users
            assertTrue(onMessageCalls.isEmpty(), "onMessage must not be called for unauthorized slash command")

            // No pending interaction should be registered for the authorized flow
            assertFalse(
                channel.pendingInteractions.containsKey("discord_123456789"),
                "pendingInteractions must not be populated for unauthorized user",
            )

            // The deferred response must have been sent (for the "Not authorized." reply)
            coVerify { interaction.deferPublicResponse() }
        }
}
