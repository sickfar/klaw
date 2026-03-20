package io.github.klaw.gateway.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscordMessageSplitTest {
    private val discordMax = DiscordNormalizer.DISCORD_MAX_MESSAGE_LENGTH

    @Test
    fun `short message under 2000 returns single chunk`() {
        val result = splitMessage("Hello, world!", maxLength = discordMax)
        assertEquals(listOf("Hello, world!"), result)
    }

    @Test
    fun `exactly 2000 chars returns single chunk`() {
        val text = "a".repeat(2000)
        val result = splitMessage(text, maxLength = discordMax)
        assertEquals(1, result.size)
        assertEquals(2000, result[0].length)
    }

    @Test
    fun `2001 chars splits into two chunks`() {
        val text = "a".repeat(2001)
        val result = splitMessage(text, maxLength = discordMax)
        assertEquals(2, result.size)
        assertEquals(2000, result[0].length)
        assertEquals(1, result[1].length)
    }

    @Test
    fun `splits at paragraph boundary within 2000`() {
        val p1 = "a".repeat(1000)
        val p2 = "b".repeat(1000)
        val p3 = "c".repeat(1000)
        val text = "$p1\n\n$p2\n\n$p3"
        // total = 3004 > 2000; first \n\n at 1000, so splits there
        val result = splitMessage(text, maxLength = discordMax)

        assertEquals(3, result.size)
        assertEquals(p1, result[0])
        assertEquals(p2, result[1])
        assertEquals(p3, result[2])
    }

    @Test
    fun `splits at line boundary within 2000`() {
        val line1 = "a".repeat(1000)
        val line2 = "b".repeat(1000)
        val line3 = "c".repeat(1000)
        val text = "$line1\n$line2\n$line3"
        // total = 3002 > 2000; first \n at 1000, so splits there
        val result = splitMessage(text, maxLength = discordMax)

        assertEquals(3, result.size)
        assertEquals(line1, result[0])
        assertEquals(line2, result[1])
        assertEquals(line3, result[2])
    }
}
