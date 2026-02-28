package io.github.klaw.cli.chat

import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleChatConfigTest {
    private fun parseConsoleConfig(json: String): ConsoleChatConfig =
        try {
            val config = parseGatewayConfig(json)
            val console = config.channels.console
            if (console != null) {
                ConsoleChatConfig(enabled = console.enabled, port = console.port)
            } else {
                ConsoleChatConfig(enabled = false)
            }
        } catch (_: Exception) {
            ConsoleChatConfig(enabled = false)
        }

    @Test
    fun `console not configured returns enabled false`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "token": "abc",
      "allowedChatIds": []
    }
  }
}
                """.trimIndent(),
            )
        assertFalse(config.enabled)
    }

    @Test
    fun `console enabled false returns enabled false`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "token": "abc",
      "allowedChatIds": []
    },
    "console": {
      "enabled": false,
      "port": 37474
    }
  }
}
                """.trimIndent(),
            )
        assertFalse(config.enabled)
    }

    @Test
    fun `console enabled true with default port`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "token": "abc",
      "allowedChatIds": []
    },
    "console": {
      "enabled": true,
      "port": 37474
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(37474, config.port)
    }

    @Test
    fun `console enabled true with custom port`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "token": "abc",
      "allowedChatIds": []
    },
    "console": {
      "enabled": true,
      "port": 9090
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(9090, config.port)
    }

    @Test
    fun `empty string returns enabled false`() {
        val config = parseConsoleConfig("")
        assertFalse(config.enabled)
    }

    @Test
    fun `malformed json returns enabled false`() {
        val config = parseConsoleConfig("not valid json")
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
            parseConsoleConfig(
                """
{
  "channels": {
    "console": {
      "port": 8080,
      "enabled": true
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(8080, config.port)
    }

    @Test
    fun `channels with only console section`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "console": {
      "enabled": true,
      "port": 5000
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(5000, config.port)
    }
}
