package io.github.klaw.cli.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class InputBufferTest {
    @Test
    fun emptyBufferDefaults() {
        val buf = InputBuffer()
        assertTrue(buf.isEmpty())
        assertEquals("", buf.getText())
        assertEquals(0, buf.getCursor())
        assertEquals(listOf(""), buf.getLines())
        assertEquals(Pair(0, 0), buf.getCursorRowCol())
    }

    @Test
    fun insertAtEnd() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.insert('c')
        assertEquals("abc", buf.getText())
        assertEquals(3, buf.getCursor())
    }

    @Test
    fun insertAtStart() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.moveLeft()
        buf.moveLeft()
        buf.insert('x')
        assertEquals("xab", buf.getText())
        assertEquals(1, buf.getCursor())
    }

    @Test
    fun insertAtMiddle() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('c')
        buf.moveLeft()
        buf.insert('b')
        assertEquals("abc", buf.getText())
        assertEquals(2, buf.getCursor())
    }

    @Test
    fun deleteBackAtZeroIsNoOp() {
        val buf = InputBuffer()
        buf.deleteBack()
        assertEquals("", buf.getText())
        assertEquals(0, buf.getCursor())
    }

    @Test
    fun deleteBackRemovesBeforeCursor() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.insert('c')
        buf.deleteBack()
        assertEquals("ab", buf.getText())
        assertEquals(2, buf.getCursor())
    }

    @Test
    fun deleteBackInMiddle() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.insert('c')
        buf.moveLeft()
        buf.deleteBack()
        assertEquals("ac", buf.getText())
        assertEquals(1, buf.getCursor())
    }

    @Test
    fun deleteForwardAtEndIsNoOp() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.deleteForward()
        assertEquals("a", buf.getText())
        assertEquals(1, buf.getCursor())
    }

    @Test
    fun deleteForwardRemovesAtCursor() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.moveLeft()
        buf.moveLeft()
        buf.deleteForward()
        assertEquals("b", buf.getText())
        assertEquals(0, buf.getCursor())
    }

    @Test
    fun moveLeftAtZeroIsNoOp() {
        val buf = InputBuffer()
        buf.moveLeft()
        assertEquals(0, buf.getCursor())
    }

    @Test
    fun moveRightAtEndIsNoOp() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.moveRight()
        assertEquals(1, buf.getCursor())
    }

    @Test
    fun moveLeftAndRight() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.moveLeft()
        assertEquals(1, buf.getCursor())
        buf.moveRight()
        assertEquals(2, buf.getCursor())
    }

    @Test
    fun insertNewline() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insertNewline()
        buf.insert('b')
        assertEquals("a\nb", buf.getText())
        assertEquals(listOf("a", "b"), buf.getLines())
    }

    @Test
    fun moveHomeAndEnd() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.insertNewline()
        buf.insert('c')
        buf.insert('d')
        // cursor at end of line 2
        buf.moveHome()
        assertEquals(Pair(1, 0), buf.getCursorRowCol())
        buf.moveEnd()
        assertEquals(Pair(1, 2), buf.getCursorRowCol())
    }

    @Test
    fun moveHomeOnFirstLine() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.moveHome()
        assertEquals(0, buf.getCursor())
        buf.moveEnd()
        assertEquals(2, buf.getCursor())
    }

    @Test
    fun moveUpDownBasic() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.insertNewline()
        buf.insert('c')
        // row=1, col=1
        buf.moveUp()
        assertEquals(Pair(0, 1), buf.getCursorRowCol())
        buf.moveDown()
        assertEquals(Pair(1, 1), buf.getCursorRowCol())
    }

    @Test
    fun moveUpAtFirstLineIsNoOp() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.moveUp()
        assertEquals(Pair(0, 1), buf.getCursorRowCol())
    }

    @Test
    fun moveDownAtLastLineIsNoOp() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.moveDown()
        assertEquals(Pair(0, 1), buf.getCursorRowCol())
    }

    @Test
    fun moveUpClampsColumn() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insertNewline()
        buf.insert('b')
        buf.insert('c')
        buf.insert('d')
        // row=1, col=3; line 0 has length 1
        buf.moveUp()
        assertEquals(Pair(0, 1), buf.getCursorRowCol())
    }

    @Test
    fun moveDownClampsColumn() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insert('b')
        buf.insert('c')
        buf.insertNewline()
        buf.insert('d')
        // cursor at row=1, col=1; moveUp clamps to min(1, 3)=1
        buf.moveUp()
        assertEquals(Pair(0, 1), buf.getCursorRowCol())
        // now move cursor to end of line 0
        buf.moveEnd()
        assertEquals(Pair(0, 3), buf.getCursorRowCol())
        buf.moveDown()
        // line 1 has length 1, clamp to 1
        assertEquals(Pair(1, 1), buf.getCursorRowCol())
    }

    @Test
    fun submitClearsAndReturns() {
        val buf = InputBuffer()
        buf.insert('h')
        buf.insert('i')
        val text = buf.submit()
        assertEquals("hi", text)
        assertTrue(buf.isEmpty())
        assertEquals(0, buf.getCursor())
        assertEquals("", buf.getText())
    }

    @Test
    fun getCursorRowColMultiline() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insertNewline()
        buf.insert('b')
        buf.insertNewline()
        buf.insert('c')
        assertEquals(Pair(2, 1), buf.getCursorRowCol())
    }

    @Test
    fun getLinesEmptyBuffer() {
        val buf = InputBuffer()
        assertEquals(listOf(""), buf.getLines())
    }

    @Test
    fun insertTextAtEnd() {
        val buf = InputBuffer()
        buf.insertText("hello")
        assertEquals("hello", buf.getText())
        assertEquals(5, buf.getCursor())
    }

    @Test
    fun insertTextAtMiddle() {
        val buf = InputBuffer()
        buf.insertText("ac")
        buf.moveLeft()
        buf.insertText("b")
        assertEquals("abc", buf.getText())
        assertEquals(2, buf.getCursor())
    }

    @Test
    fun insertTextMultiChar() {
        val buf = InputBuffer()
        buf.insertText("你好")
        assertEquals("你好", buf.getText())
        assertEquals(2, buf.getCursor())
    }

    @Test
    fun getLinesTrailingNewline() {
        val buf = InputBuffer()
        buf.insert('a')
        buf.insertNewline()
        assertEquals(listOf("a", ""), buf.getLines())
    }
}
