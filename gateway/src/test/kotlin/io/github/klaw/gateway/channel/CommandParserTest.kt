package io.github.klaw.gateway.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandParserTest {
    @Test
    fun `slash new parsed as command`() {
        val result = CommandParser.parse("/new")
        assertTrue(result.isCommand)
        assertEquals("new", result.commandName)
        assertNull(result.commandArgs)
    }

    @Test
    fun `slash model with args parsed correctly`() {
        val result = CommandParser.parse("/model gpt-4")
        assertTrue(result.isCommand)
        assertEquals("model", result.commandName)
        assertEquals("gpt-4", result.commandArgs)
    }

    @Test
    fun `regular text is not a command`() {
        val result = CommandParser.parse("hello world")
        assertFalse(result.isCommand)
        assertNull(result.commandName)
        assertNull(result.commandArgs)
    }

    @Test
    fun `slash alone is command with null name`() {
        val result = CommandParser.parse("/")
        assertTrue(result.isCommand)
        assertNull(result.commandName)
        assertNull(result.commandArgs)
    }

    @Test
    fun `command with at-bot suffix strips bot name`() {
        val result = CommandParser.parse("/new@KlawBot")
        assertTrue(result.isCommand)
        assertEquals("new", result.commandName)
        assertNull(result.commandArgs)
    }

    @Test
    fun `command with at-bot suffix and args parsed correctly`() {
        val result = CommandParser.parse("/model@Bot gpt-4")
        assertTrue(result.isCommand)
        assertEquals("model", result.commandName)
        assertEquals("gpt-4", result.commandArgs)
    }
}
