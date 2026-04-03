package io.github.klaw.cli.chat

import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleChatConfigTest {
    private fun parseConsoleConfig(
        json: String,
        dotenvContent: String? = null,
    ): ConsoleChatConfig =
        try {
            val config = parseGatewayConfig(json)
            val ws =
                config.channels.websocket.values
                    .firstOrNull()
            if (ws != null) {
                val rawToken = config.webui.apiToken
                val resolvedToken = resolveFromDotenv(rawToken, dotenvContent)
                ConsoleChatConfig(enabled = true, port = ws.port, apiToken = resolvedToken)
            } else {
                ConsoleChatConfig(enabled = false)
            }
        } catch (_: Exception) {
            ConsoleChatConfig(enabled = false)
        }

    @Test
    fun `websocket not configured returns enabled false`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "default": {
        "agentId": "default",
        "token": "abc",
        "allowedChats": []
      }
    }
  }
}
                """.trimIndent(),
            )
        assertFalse(config.enabled)
    }

    @Test
    fun `websocket absent returns enabled false`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "default": {
        "agentId": "default",
        "token": "abc",
        "allowedChats": []
      }
    }
  }
}
                """.trimIndent(),
            )
        assertFalse(config.enabled)
    }

    @Test
    fun `websocket configured with default port`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "default": {
        "agentId": "default",
        "token": "abc",
        "allowedChats": []
      }
    },
    "websocket": {
      "default": {
        "agentId": "default",
        "port": 37474
      }
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(37474, config.port)
    }

    @Test
    fun `websocket configured with custom port`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "telegram": {
      "default": {
        "agentId": "default",
        "token": "abc",
        "allowedChats": []
      }
    },
    "websocket": {
      "default": {
        "agentId": "default",
        "port": 9090
      }
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
        assertEquals("ws://localhost:37474/ws/chat", config.wsUrl)
    }

    @Test
    fun `wsUrl with custom port`() {
        val config = ConsoleChatConfig(enabled = true, port = 9090)
        assertEquals("ws://localhost:9090/ws/chat", config.wsUrl)
    }

    @Test
    fun `websocket section only`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "websocket": {
      "default": {
        "agentId": "default",
        "port": 8080
      }
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(8080, config.port)
    }

    @Test
    fun `channels with only websocket section`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "websocket": {
      "default": {
        "agentId": "default",
        "port": 5000
      }
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals(5000, config.port)
    }

    @Test
    fun `wsUrl with apiToken appends query parameter`() {
        val config = ConsoleChatConfig(enabled = true, port = 37474, apiToken = "secret")
        assertEquals("ws://localhost:37474/ws/chat?token=secret", config.wsUrl)
    }

    @Test
    fun `wsUrl with blank apiToken has no query parameter`() {
        val config = ConsoleChatConfig(enabled = true, port = 37474)
        assertEquals("ws://localhost:37474/ws/chat", config.wsUrl)
    }

    @Test
    fun `parseConsoleConfig reads literal apiToken from gateway json`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "websocket": {
      "default": {
        "agentId": "default",
        "port": 37474
      }
    }
  },
  "webui": {
    "apiToken": "my-token"
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals("my-token", config.apiToken)
    }

    @Test
    fun `parseConsoleConfig with no webui section defaults to empty apiToken`() {
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "websocket": {
      "default": {
        "agentId": "default",
        "port": 37474
      }
    }
  }
}
                """.trimIndent(),
            )
        assertTrue(config.enabled)
        assertEquals("", config.apiToken)
    }

    @Test
    fun `resolveFromDotenv resolves env var pattern`() {
        val dotenv = "WEBUI_API_TOKEN=resolved-value"
        val result = resolveFromDotenv("\${WEBUI_API_TOKEN}", dotenv)
        assertEquals("resolved-value", result)
    }

    @Test
    fun `resolveFromDotenv returns empty for missing var`() {
        val dotenv = "OTHER_VAR=something"
        val result = resolveFromDotenv("\${MISSING_VAR}", dotenv)
        assertEquals("", result)
    }

    @Test
    fun `resolveFromDotenv returns literal for non-pattern`() {
        val dotenv = "SOME_VAR=value"
        val result = resolveFromDotenv("my-literal", dotenv)
        assertEquals("my-literal", result)
    }

    @Test
    fun `resolveFromDotenv with null dotenv returns raw value`() {
        val result = resolveFromDotenv("\${WEBUI_API_TOKEN}", null)
        assertEquals("\${WEBUI_API_TOKEN}", result)
    }

    @Test
    fun `resolveFromDotenv skips comments and blank lines`() {
        val dotenv =
            """
            # this is a comment

            WEBUI_API_TOKEN=found-it
            """.trimIndent()
        val result = resolveFromDotenv("\${WEBUI_API_TOKEN}", dotenv)
        assertEquals("found-it", result)
    }

    @Test
    fun `resolveFromDotenv handles value containing equals sign`() {
        val dotenv = "MY_TOKEN=abc=def=="
        val result = resolveFromDotenv("\${MY_TOKEN}", dotenv)
        assertEquals("abc=def==", result)
    }

    @Test
    fun `resolveFromDotenv strips double quotes from value`() {
        val dotenv = "MY_TOKEN=\"my-secret\""
        val result = resolveFromDotenv("\${MY_TOKEN}", dotenv)
        assertEquals("my-secret", result)
    }

    @Test
    fun `resolveFromDotenv strips single quotes from value`() {
        val dotenv = "MY_TOKEN='my-secret'"
        val result = resolveFromDotenv("\${MY_TOKEN}", dotenv)
        assertEquals("my-secret", result)
    }

    @Test
    fun `httpBaseUrl returns correct URL with default port`() {
        val config = ConsoleChatConfig(enabled = true, port = 37474)
        assertEquals("http://localhost:37474", config.httpBaseUrl)
    }

    @Test
    fun `httpBaseUrl returns correct URL with custom port`() {
        val config = ConsoleChatConfig(enabled = true, port = 9090)
        assertEquals("http://localhost:9090", config.httpBaseUrl)
    }

    @Test
    fun `parseConsoleConfig resolves env var token from dotenv`() {
        val dotenv = "WEBUI_API_TOKEN=resolved-secret"
        val config =
            parseConsoleConfig(
                """
{
  "channels": {
    "websocket": {
      "default": {
        "agentId": "default",
        "port": 37474
      }
    }
  },
  "webui": {
    "apiToken": "${'$'}{WEBUI_API_TOKEN}"
  }
}
                """.trimIndent(),
                dotenvContent = dotenv,
            )
        assertTrue(config.enabled)
        assertEquals("resolved-secret", config.apiToken)
    }
}
