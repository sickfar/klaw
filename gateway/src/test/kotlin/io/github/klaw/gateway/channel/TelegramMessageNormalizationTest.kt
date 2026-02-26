package io.github.klaw.gateway.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TelegramMessageNormalizationTest {
    @Test
    fun `regular text message normalized to IncomingMessage`() {
        val msg =
            TelegramNormalizer.normalize(
                chatId = 123456L,
                text = "Hello, world!",
                ts = Instant.parse("2026-02-24T10:00:00Z"),
                messageId = "fixed-id",
            )
        assertEquals("fixed-id", msg.id)
        assertEquals("telegram", msg.channel)
        assertEquals("telegram_123456", msg.chatId)
        assertEquals("Hello, world!", msg.content)
        assertFalse(msg.isCommand)
        assertNull(msg.commandName)
        assertNull(msg.commandArgs)
    }

    @Test
    fun `bot_command entity detected as isCommand=true`() {
        val msg = TelegramNormalizer.normalize(123456L, "/start", messageId = "id1")
        assertTrue(msg.isCommand)
    }

    @Test
    fun `slash_new command parsed correctly`() {
        val msg = TelegramNormalizer.normalize(123456L, "/new", messageId = "id1")
        assertTrue(msg.isCommand)
        assertEquals("new", msg.commandName)
        assertNull(msg.commandArgs)
    }

    @Test
    fun `slash_model with args parsed correctly`() {
        val msg = TelegramNormalizer.normalize(123456L, "/model gpt-4", messageId = "id1")
        assertTrue(msg.isCommand)
        assertEquals("model", msg.commandName)
        assertEquals("gpt-4", msg.commandArgs)
    }

    @Test
    fun `chatId formatted as telegram_{platformId}`() {
        val msg = TelegramNormalizer.normalize(987654321L, "hello", messageId = "id1")
        assertEquals("telegram_987654321", msg.chatId)
    }

    @Test
    fun `message with forwarded content handled as regular text`() {
        val msg = TelegramNormalizer.normalize(123456L, "Forwarded: some content", messageId = "id1")
        assertFalse(msg.isCommand)
        assertEquals("Forwarded: some content", msg.content)
    }

    @Test
    fun `command with multiple word args parsed correctly`() {
        val msg = TelegramNormalizer.normalize(123456L, "/model deepseek r1 distill", messageId = "id1")
        assertEquals("model", msg.commandName)
        assertEquals("deepseek r1 distill", msg.commandArgs)
    }
}
