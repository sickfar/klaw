package io.github.klaw.cli.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTuiTest {
    @Test
    fun `addMessage stores message in history`() {
        val tui = ChatTui()
        tui.addMessage(ChatTui.Message("user", "Hello"))
        val history = tui.getHistory()
        assertEquals(1, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Hello", history[0].content)
    }

    @Test
    fun `addMessage multiple messages preserves order`() {
        val tui = ChatTui()
        tui.addMessage(ChatTui.Message("user", "Hello"))
        tui.addMessage(ChatTui.Message("assistant", "Hi there"))
        val history = tui.getHistory()
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("assistant", history[1].role)
    }

    @Test
    fun `getHistory with empty history returns empty list`() {
        val tui = ChatTui()
        assertTrue(tui.getHistory().isEmpty())
    }

    @Test
    fun `appendInput adds char to input buffer`() {
        val tui = ChatTui()
        tui.appendInput('H')
        tui.appendInput('i')
        assertEquals("Hi", tui.getInput())
    }

    @Test
    fun `deleteLastInput removes last char`() {
        val tui = ChatTui()
        tui.appendInput('H')
        tui.appendInput('i')
        tui.deleteLastInput()
        assertEquals("H", tui.getInput())
    }

    @Test
    fun `deleteLastInput on empty buffer does not crash`() {
        val tui = ChatTui()
        tui.deleteLastInput()
        assertEquals("", tui.getInput())
    }

    @Test
    fun `submitInput returns current buffer and clears it`() {
        val tui = ChatTui()
        tui.appendInput('H')
        tui.appendInput('i')
        val text = tui.submitInput()
        assertEquals("Hi", text)
        assertEquals("", tui.getInput())
    }

    @Test
    fun `submitInput with empty buffer returns empty string`() {
        val tui = ChatTui()
        val text = tui.submitInput()
        assertEquals("", text)
    }

    @Test
    fun `addMessage does not affect input buffer`() {
        val tui = ChatTui()
        tui.appendInput('X')
        tui.addMessage(ChatTui.Message("user", "Hello"))
        assertEquals("X", tui.getInput())
    }

    @Test
    fun `getHistory returns copy not reference`() {
        val tui = ChatTui()
        tui.addMessage(ChatTui.Message("user", "Hello"))
        val snapshot = tui.getHistory()
        tui.addMessage(ChatTui.Message("assistant", "World"))
        assertEquals(1, snapshot.size, "Snapshot should not change when more messages are added")
        assertEquals(2, tui.getHistory().size)
    }
}
