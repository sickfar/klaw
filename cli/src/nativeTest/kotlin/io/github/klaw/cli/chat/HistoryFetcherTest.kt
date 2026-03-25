package io.github.klaw.cli.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryFetcherTest {
    @Test
    fun `parseHistoryResponse parses valid JSON array`() {
        val json = """[
            {"role":"user","content":"Hello","timestamp":"2026-03-25T10:00:00Z"},
            {"role":"assistant","content":"Hi there","timestamp":"2026-03-25T10:00:01Z"}
        ]"""
        val messages = parseHistoryResponse(json)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("Hi there", messages[1].content)
    }

    @Test
    fun `parseHistoryResponse returns empty on invalid JSON`() {
        val messages = parseHistoryResponse("not json at all")
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `parseHistoryResponse returns empty on empty array`() {
        val messages = parseHistoryResponse("[]")
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `parseHistoryResponse filters non-displayable roles`() {
        val json = """[
            {"role":"user","content":"Hello","timestamp":"2026-03-25T10:00:00Z"},
            {"role":"system","content":"You are helpful","timestamp":"2026-03-25T10:00:00Z"},
            {"role":"assistant","content":"Hi","timestamp":"2026-03-25T10:00:01Z"}
        ]"""
        val messages = parseHistoryResponse(json)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
    }

    @Test
    fun `parseHistoryResponse handles content with special characters`() {
        val json = """[
            {"role":"user","content":"Hello \"world\"\nNew line","timestamp":"2026-03-25T10:00:00Z"}
        ]"""
        val messages = parseHistoryResponse(json)
        assertEquals(1, messages.size)
        assertEquals("Hello \"world\"\nNew line", messages[0].content)
    }

    @Test
    fun `parseHistoryResponse handles error response gracefully`() {
        val json = """{"error":"missing chat_id"}"""
        val messages = parseHistoryResponse(json)
        assertTrue(messages.isEmpty())
    }
}
