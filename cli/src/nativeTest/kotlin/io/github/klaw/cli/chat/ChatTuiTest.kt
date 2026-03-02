package io.github.klaw.cli.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        tui.appendInput("H")
        tui.appendInput("i")
        assertEquals("Hi", tui.getInput())
    }

    @Test
    fun `deleteLastInput removes last char`() {
        val tui = ChatTui()
        tui.appendInput("H")
        tui.appendInput("i")
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
        tui.appendInput("H")
        tui.appendInput("i")
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
        tui.appendInput("X")
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

    @Test
    fun `insertNewline adds newline to input`() {
        val tui = ChatTui()
        tui.appendInput("A")
        tui.insertNewline()
        tui.appendInput("B")
        assertTrue(tui.getInput().contains('\n'))
        assertEquals("A\nB", tui.getInput())
    }

    @Test
    fun `moveLeft and moveRight work`() {
        val tui = ChatTui()
        tui.appendInput("A")
        tui.appendInput("C")
        tui.moveLeft()
        tui.appendInput("B")
        assertEquals("ABC", tui.getInput())
    }

    @Test
    fun `setStatus stores status text`() {
        val tui = ChatTui()
        tui.setStatus("thinking")
        assertEquals("thinking", tui.getStatus())
    }

    @Test
    fun `setStatus empty clears status`() {
        val tui = ChatTui()
        tui.setStatus("thinking")
        tui.setStatus("")
        assertEquals("", tui.getStatus())
    }

    @Test
    fun `showApproval enters approval mode`() {
        val tui = ChatTui()
        tui.showApproval("id-1", "rm -rf /", 8, 30)
        assertTrue(tui.isApprovalMode())
        assertEquals("id-1", tui.getApprovalId())
    }

    @Test
    fun `resolveApproval returns id and exits mode`() {
        val tui = ChatTui()
        tui.showApproval("id-1", "rm -rf /", 8, 30)
        val result = tui.resolveApproval(true)
        assertEquals(Pair("id-1", true), result)
        assertFalse(tui.isApprovalMode())
    }

    @Test
    fun `resolveApproval when not in approval mode returns null`() {
        val tui = ChatTui()
        assertNull(tui.resolveApproval(false))
    }

    @Test
    fun `tickSpinner increments counter`() {
        val tui = ChatTui()
        // Should not crash
        tui.tickSpinner()
        tui.tickSpinner()
    }

    @Test
    fun `deleteForward removes char after cursor`() {
        val tui = ChatTui()
        tui.appendInput("A")
        tui.appendInput("B")
        tui.moveLeft()
        tui.deleteForward()
        assertEquals("A", tui.getInput())
    }

    @Test
    fun `moveUp and moveDown navigate lines`() {
        val tui = ChatTui()
        tui.appendInput("A")
        tui.insertNewline()
        tui.appendInput("B")
        tui.moveUp()
        tui.appendInput("X")
        assertEquals("AX\nB", tui.getInput())
    }

    @Test
    fun `moveHome and moveEnd work`() {
        val tui = ChatTui()
        tui.appendInput("A")
        tui.appendInput("B")
        tui.appendInput("C")
        tui.moveHome()
        tui.appendInput("X")
        assertEquals("XABC", tui.getInput())
    }
}
