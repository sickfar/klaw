package io.github.klaw.gateway.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramNormalizerTest {
    @Test
    fun `userId extracted as string`() {
        val msg = TelegramNormalizer.normalize(chatId = 123L, text = "hello", userId = 456L)
        assertEquals("456", msg.userId)
    }

    @Test
    fun `null userId preserved`() {
        val msg = TelegramNormalizer.normalize(chatId = 123L, text = "hello", userId = null)
        assertNull(msg.userId)
    }

    @Test
    fun `chatId prefixed with telegram_`() {
        val msg = TelegramNormalizer.normalize(chatId = 789L, text = "hello")
        assertEquals("telegram_789", msg.chatId)
    }

    @Test
    fun `channel is telegram`() {
        val msg = TelegramNormalizer.normalize(chatId = 1L, text = "hello")
        assertEquals("telegram", msg.channel)
    }

    @Test
    fun `command parsed from text`() {
        val msg = TelegramNormalizer.normalize(chatId = 1L, text = "/start")
        assertTrue(msg.isCommand)
        assertEquals("start", msg.commandName)
    }

    @Test
    fun `regular text not parsed as command`() {
        val msg = TelegramNormalizer.normalize(chatId = 1L, text = "just a message")
        assertFalse(msg.isCommand)
        assertNull(msg.commandName)
    }

    @Test
    fun `command with args parsed`() {
        val msg = TelegramNormalizer.normalize(chatId = 1L, text = "/help topic")
        assertTrue(msg.isCommand)
        assertEquals("help", msg.commandName)
        assertEquals("topic", msg.commandArgs)
    }
}
