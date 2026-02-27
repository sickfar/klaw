package io.github.klaw.cli.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleChatConfigTest {
    @Test
    fun `console not configured returns enabled false`() {
        val config =
            parseConsoleChatConfig(
                """
channels:
  telegram:
    token: "abc"
    allowedChatIds: []
                """.trimIndent(),
            )
        assertFalse(config.enabled)
    }

    @Test
    fun `console enabled false returns enabled false`() {
        val config =
            parseConsoleChatConfig(
                """
channels:
  telegram:
    token: "abc"
    allowedChatIds: []
  console:
    enabled: false
    port: 37474
                """.trimIndent(),
            )
        assertFalse(config.enabled)
    }

    @Test
    fun `console enabled true with default port`() {
        val config =
            parseConsoleChatConfig(
                """
channels:
  telegram:
    token: "abc"
    allowedChatIds: []
  console:
    enabled: true
    port: 37474
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(37474, config.port)
    }

    @Test
    fun `console enabled true with custom port`() {
        val config =
            parseConsoleChatConfig(
                """
channels:
  telegram:
    token: "abc"
    allowedChatIds: []
  console:
    enabled: true
    port: 9090
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(9090, config.port)
    }

    @Test
    fun `empty string returns enabled false`() {
        val config = parseConsoleChatConfig("")
        assertFalse(config.enabled)
    }

    @Test
    fun `malformed yaml no console section returns enabled false`() {
        val config =
            parseConsoleChatConfig(
                """
not:
  valid:
    yaml: structure
                """.trimIndent(),
            )
        assertFalse(config.enabled)
    }

    @Test
    fun `wsUrl builds correct url`() {
        val config = ConsoleChatConfig(enabled = true, port = 37474)
        assertEquals("ws://localhost:37474/chat", config.wsUrl)
    }

    @Test
    fun `wsUrl with custom port`() {
        val config = ConsoleChatConfig(enabled = true, port = 9090)
        assertEquals("ws://localhost:9090/chat", config.wsUrl)
    }

    @Test
    fun `console section with port before enabled`() {
        val config =
            parseConsoleChatConfig(
                """
channels:
  console:
    port: 8080
    enabled: true
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(8080, config.port)
    }

    @Test
    fun `non-channel top level section resets channel tracking`() {
        val config =
            parseConsoleChatConfig(
                """
other:
  stuff: value
channels:
  console:
    enabled: true
    port: 5000
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(5000, config.port)
    }
}
