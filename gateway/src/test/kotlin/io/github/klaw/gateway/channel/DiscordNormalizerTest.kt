package io.github.klaw.gateway.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordNormalizerTest {
    @Test
    fun `guild text channel sets chatId and chatType`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 123uL,
                text = "hello",
                chatType = "guild_text",
            )
        assertEquals("discord_123", msg.chatId)
        assertEquals("guild_text", msg.chatType)
        assertEquals("discord", msg.channel)
    }

    @Test
    fun `DM sets chatType to dm and chatTitle null`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 456uL,
                text = "hello",
                chatType = "dm",
            )
        assertEquals("dm", msg.chatType)
        assertNull(msg.chatTitle)
    }

    @Test
    fun `thread sets chatType and chatTitle to thread name`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 789uL,
                text = "hello",
                chatType = "guild_thread",
                chatTitle = "Bug Discussion",
            )
        assertEquals("guild_thread", msg.chatType)
        assertEquals("Bug Discussion", msg.chatTitle)
    }

    @Test
    fun `forum post sets chatType to guild_forum`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 101uL,
                text = "hello",
                chatType = "guild_forum",
            )
        assertEquals("guild_forum", msg.chatType)
    }

    @Test
    fun `command new parsed correctly`() {
        val msg = DiscordNormalizer.normalize(channelId = 1uL, text = "/new")
        assertTrue(msg.isCommand)
        assertEquals("new", msg.commandName)
    }

    @Test
    fun `command new with args parsed correctly`() {
        val msg = DiscordNormalizer.normalize(channelId = 1uL, text = "/new session")
        assertTrue(msg.isCommand)
        assertEquals("new", msg.commandName)
        assertEquals("session", msg.commandArgs)
    }

    @Test
    fun `missing user results in null userId and senderName`() {
        val msg = DiscordNormalizer.normalize(channelId = 1uL, text = "hello")
        assertNull(msg.userId)
        assertNull(msg.senderName)
    }

    @Test
    fun `normal message is not a command`() {
        val msg = DiscordNormalizer.normalize(channelId = 1uL, text = "just a message")
        assertFalse(msg.isCommand)
        assertNull(msg.commandName)
        assertNull(msg.commandArgs)
    }

    @Test
    fun `user with name sets senderName`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 1uL,
                text = "hello",
                userId = 999uL,
                senderName = "Alice",
            )
        assertEquals("999", msg.userId)
        assertEquals("Alice", msg.senderName)
    }

    @Test
    fun `guildId is preserved in IncomingMessage`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 1uL,
                text = "hello",
                guildId = "111222333",
            )
        assertEquals("111222333", msg.guildId)
    }

    @Test
    fun `guildId null works correctly`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 1uL,
                text = "hello",
                guildId = null,
            )
        assertNull(msg.guildId)
    }

    @Test
    fun `platform message ID preserved`() {
        val msg =
            DiscordNormalizer.normalize(
                channelId = 1uL,
                text = "hello",
                platformMessageId = "msg-12345",
            )
        assertEquals("msg-12345", msg.messageId)
    }
}
