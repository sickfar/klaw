package io.github.klaw.cli.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class KeyParserTest {
    @Test
    fun printableAscii() {
        val parser = KeyParser()
        assertEquals(ChatEvent.KeyPressed("a"), parser.feed('a'.code))
        assertEquals(ChatEvent.KeyPressed("Z"), parser.feed('Z'.code))
        assertEquals(ChatEvent.KeyPressed(" "), parser.feed(32))
    }

    @Test
    fun enterKey() {
        val parser = KeyParser()
        assertEquals(ChatEvent.Enter, parser.feed(13))
        assertEquals(ChatEvent.Enter, parser.feed(10))
    }

    @Test
    fun backspaceKey() {
        val parser = KeyParser()
        assertEquals(ChatEvent.Backspace, parser.feed(127))
    }

    @Test
    fun ctrlCQuit() {
        val parser = KeyParser()
        assertEquals(ChatEvent.Quit, parser.feed(3))
    }

    @Test
    fun arrowUp() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertEquals(ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.UP), parser.feed('A'.code))
    }

    @Test
    fun arrowDown() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertEquals(ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.DOWN), parser.feed('B'.code))
    }

    @Test
    fun arrowRight() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertEquals(ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.RIGHT), parser.feed('C'.code))
    }

    @Test
    fun arrowLeft() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertEquals(ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.LEFT), parser.feed('D'.code))
    }

    @Test
    fun deleteKey() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertNull(parser.feed('3'.code))
        assertEquals(ChatEvent.Delete, parser.feed('~'.code))
    }

    @Test
    fun homeKey() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertEquals(ChatEvent.Home, parser.feed('H'.code))
    }

    @Test
    fun endKey() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertEquals(ChatEvent.End, parser.feed('F'.code))
    }

    @Test
    fun altEnterNewLine() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertEquals(ChatEvent.NewLine, parser.feed(13))
    }

    @Test
    fun altEnterWithLf() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertEquals(ChatEvent.NewLine, parser.feed(10))
    }

    @Test
    fun unknownEscapeSequenceConsumedSilently() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertNull(parser.feed('X'.code))
        // parser should be back in normal state
        assertEquals(ChatEvent.KeyPressed("a"), parser.feed('a'.code))
    }

    @Test
    fun unknownAfterEscResets() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed('x'.code))
        assertEquals(ChatEvent.KeyPressed("a"), parser.feed('a'.code))
    }

    @Test
    fun unknownAfterDeletePrefix() {
        val parser = KeyParser()
        assertNull(parser.feed(0x1B))
        assertNull(parser.feed(0x5B))
        assertNull(parser.feed('3'.code))
        assertNull(parser.feed('x'.code))
        assertEquals(ChatEvent.KeyPressed("a"), parser.feed('a'.code))
    }

    @Test
    fun controlCharsIgnored() {
        val parser = KeyParser()
        assertNull(parser.feed(1)) // Ctrl+A
        assertNull(parser.feed(0)) // NUL
    }

    @Test
    fun utf8TwoByte() {
        // é = C3 A9
        val parser = KeyParser()
        assertNull(parser.feed(0xC3))
        assertEquals(ChatEvent.KeyPressed("é"), parser.feed(0xA9))
    }

    @Test
    fun utf8ThreeByte() {
        // 你 = E4 BD A0
        val parser = KeyParser()
        assertNull(parser.feed(0xE4))
        assertNull(parser.feed(0xBD))
        assertEquals(ChatEvent.KeyPressed("你"), parser.feed(0xA0))
    }

    @Test
    fun utf8FourByte() {
        // 😀 = F0 9F 98 80
        val parser = KeyParser()
        assertNull(parser.feed(0xF0))
        assertNull(parser.feed(0x9F))
        assertNull(parser.feed(0x98))
        assertEquals(ChatEvent.KeyPressed("😀"), parser.feed(0x80))
    }

    @Test
    fun strayContinuationByteIgnored() {
        val parser = KeyParser()
        assertNull(parser.feed(0x80))
        assertEquals(ChatEvent.KeyPressed("a"), parser.feed('a'.code))
    }

    @Test
    fun utf8ThenAscii() {
        val parser = KeyParser()
        assertNull(parser.feed(0xC3))
        assertEquals(ChatEvent.KeyPressed("é"), parser.feed(0xA9))
        assertEquals(ChatEvent.KeyPressed("a"), parser.feed('a'.code))
    }

    @Test
    fun utf8InvalidContinuationResets() {
        // Start 2-byte sequence but feed non-continuation byte
        val parser = KeyParser()
        assertNull(parser.feed(0xC3))
        // Feed ASCII 'a' instead of continuation — should reset and re-feed
        assertEquals(ChatEvent.KeyPressed("a"), parser.feed('a'.code))
    }
}
