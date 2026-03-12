package io.github.klaw.gateway.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TelegramMessageSplitTest {
    @Test
    fun `short message returns single chunk`() {
        val result = splitMessage("Hello, world!")
        assertEquals(listOf("Hello, world!"), result)
    }

    @Test
    fun `exactly maxLength returns single chunk`() {
        val text = "a".repeat(4096)
        val result = splitMessage(text, maxLength = 4096)
        assertEquals(1, result.size)
        assertEquals(4096, result[0].length)
    }

    @Test
    fun `empty message returns single empty chunk`() {
        val result = splitMessage("")
        assertEquals(listOf(""), result)
    }

    @Test
    fun `splits at paragraph boundary within limit`() {
        val paragraph1 = "a".repeat(2000)
        val paragraph2 = "b".repeat(2000)
        val paragraph3 = "c".repeat(2000)
        val text = "$paragraph1\n\n$paragraph2\n\n$paragraph3"
        val result = splitMessage(text, maxLength = 4096)

        assertEquals(2, result.size)
        assertEquals("$paragraph1\n\n$paragraph2", result[0])
        assertEquals(paragraph3, result[1])
    }

    @Test
    fun `splits at line boundary when no paragraph breaks`() {
        val line1 = "a".repeat(2000)
        val line2 = "b".repeat(2000)
        val line3 = "c".repeat(2000)
        val text = "$line1\n$line2\n$line3"
        val result = splitMessage(text, maxLength = 4096)

        assertEquals(2, result.size)
        assertEquals("$line1\n$line2", result[0])
        assertEquals(line3, result[1])
    }

    @Test
    fun `hard splits when no newlines present`() {
        val text = "a".repeat(10000)
        val result = splitMessage(text, maxLength = 4096)

        assertEquals(3, result.size)
        assertEquals(4096, result[0].length)
        assertEquals(4096, result[1].length)
        assertEquals(10000 - 4096 - 4096, result[2].length)
    }

    @Test
    fun `multiple chunks sent in correct order`() {
        val p1 = "a".repeat(3000)
        val p2 = "b".repeat(3000)
        val p3 = "c".repeat(3000)
        val text = "$p1\n\n$p2\n\n$p3"
        val result = splitMessage(text, maxLength = 4096)

        // p1 (3000) + \n\n + p2 (3000) = 6002 > 4096 → split before p2
        assertEquals(3, result.size)
        assertEquals(p1, result[0])
        assertEquals(p2, result[1])
        assertEquals(p3, result[2])
    }

    @Test
    fun `paragraph split preferred over line split`() {
        // Two paragraphs, first fits within limit
        val p1 = "line1\nline2\nline3"
        val p2 = "b".repeat(4090)
        val text = "$p1\n\n$p2"
        val result = splitMessage(text, maxLength = 4096)

        assertEquals(2, result.size)
        assertEquals(p1, result[0])
        assertEquals(p2, result[1])
    }

    @Test
    fun `trailing whitespace from split boundaries is trimmed`() {
        val p1 = "a".repeat(2000)
        val p2 = "b".repeat(2000)
        val text = "$p1\n\n$p2"
        val result = splitMessage(text, maxLength = 4096)

        // Should fit in one chunk: 2000 + 2 + 2000 = 4002 <= 4096
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }
}
