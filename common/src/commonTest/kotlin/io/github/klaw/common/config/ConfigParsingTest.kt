package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigParsingTest {
    private val gatewayJson =
        """
{
  "channels": {
    "telegram": {
      "token": "test_bot_token_123",
      "allowedChats": [{"chatId": "123456", "allowedUserIds": ["user1"]}, {"chatId": "789012"}]
    },
    "discord": {
      "enabled": false,
      "token": "discord_bot_token"
    }
  }
}
        """.trimIndent()

    private val engineJson =
        """
{
  "providers": {
    "glm": {
      "type": "openai-compatible",
      "endpoint": "https://open.bigmodel.cn/api/paas/v4",
      "apiKey": "glm_key"
    },
    "deepseek": {
      "type": "openai-compatible",
      "endpoint": "https://api.deepseek.com/v1",
      "apiKey": "deepseek_key"
    },
    "ollama": {
      "type": "openai-compatible",
      "endpoint": "http://localhost:11434/v1"
    },
    "anthropic": {
      "type": "anthropic",
      "endpoint": "https://api.anthropic.com/v1",
      "apiKey": "anthropic_key"
    }
  },
  "models": {
    "glm/glm-4-plus": {},
    "glm/glm-5": {},
    "deepseek/deepseek-chat": {},
    "deepseek/deepseek-reasoner": {},
    "ollama/qwen3:8b": {},
    "anthropic/claude-sonnet-4-20250514": {}
  },
  "routing": {
    "default": "glm/glm-5",
    "fallback": ["deepseek/deepseek-chat", "ollama/qwen3:8b"],
    "tasks": {
      "summarization": "ollama/qwen3:8b",
      "subagent": "glm/glm-4-plus"
    }
  },
  "memory": {
    "embedding": {
      "type": "onnx",
      "model": "all-MiniLM-L6-v2"
    },
    "chunking": {
      "size": 400,
      "overlap": 80
    },
    "search": {
      "topK": 10
    }
  },
  "context": {
    "tokenBudget": 8000,
    "subagentHistory": 5
  },
  "processing": {
    "debounceMs": 1500,
    "maxConcurrentLlm": 2,
    "maxToolCallRounds": 10
  },
  "httpRetry": {
    "maxRetries": 2,
    "requestTimeoutMs": 60000,
    "initialBackoffMs": 1000,
    "backoffMultiplier": 2.0
  },
  "logging": {
    "subagentConversations": true
  },
  "agents": {
    "default": {
      "workspace": "/home/user/workspace"
    }
  },
  "codeExecution": {
    "dockerImage": "klaw-sandbox:latest",
    "timeout": 30,
    "allowNetwork": true,
    "maxMemory": "256m",
    "maxCpus": "1.0",
    "keepAlive": true,
    "keepAliveIdleTimeoutMin": 10,
    "keepAliveMaxExecutions": 50
  },
  "hostExecution": {
    "enabled": true,
    "allowList": ["df -h", "free -m", "systemctl status *"],
    "notifyList": ["systemctl restart klaw-*"],
    "preValidation": {
      "enabled": true,
      "model": "anthropic/claude-haiku",
      "riskThreshold": 5,
      "timeoutMs": 5000
    },
    "askTimeoutMin": 5
  },
  "files": {
    "maxFileSizeBytes": 1048576
  },
  "commands": [
    {"name": "new", "description": "New session"},
    {"name": "model", "description": "Show/change model"},
    {"name": "models", "description": "List models"},
    {"name": "memory", "description": "Show core memory"},
    {"name": "status", "description": "Agent status"},
    {"name": "help", "description": "List commands"}
  ]
}
        """.trimIndent()

    @Test
    fun `parse gateway json - telegram token`() {
        val config = parseGatewayConfig(gatewayJson)
        assertEquals("test_bot_token_123", config.channels.telegram?.token)
    }

    @Test
    fun `parse gateway json - telegram allowedChats`() {
        val config = parseGatewayConfig(gatewayJson)
        val chats = config.channels.telegram?.allowedChats
        assertEquals(2, chats?.size)
        assertEquals("123456", chats?.get(0)?.chatId)
        assertEquals(listOf("user1"), chats?.get(0)?.allowedUserIds)
        assertEquals("789012", chats?.get(1)?.chatId)
        assertEquals(emptyList(), chats?.get(1)?.allowedUserIds)
    }

    @Test
    fun `parse gateway json - discord enabled false`() {
        val config = parseGatewayConfig(gatewayJson)
        val discord = assertNotNull(config.channels.discord)
        assertFalse(discord.enabled)
    }

    @Test
    fun `parse engine json - routing default`() {
        val config = parseEngineConfig(engineJson)
        assertEquals("glm/glm-5", config.routing.default)
    }

    @Test
    fun `parse engine json - 4 providers`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(4, config.providers.size)
        assertNotNull(config.providers["glm"])
        assertNotNull(config.providers["deepseek"])
        assertNotNull(config.providers["ollama"])
        assertNotNull(config.providers["anthropic"])
    }

    @Test
    fun `parse engine json - 6 models`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(6, config.models.size)
    }

    @Test
    fun `parse engine json - memory chunking size 400`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(400, config.memory.chunking.size)
        assertEquals(80, config.memory.chunking.overlap)
    }

    @Test
    fun `parse engine json - processing maxToolCallRounds 10`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(10, config.processing.maxToolCallRounds)
    }

    @Test
    fun `parse engine json - HttpRetryConfig backoffMultiplier 2 0`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(2.0, config.httpRetry.backoffMultiplier)
    }

    @Test
    fun `parse engine json - codeExecution dockerImage`() {
        val config = parseEngineConfig(engineJson)
        assertEquals("klaw-sandbox:latest", config.codeExecution.dockerImage)
        assertTrue(config.codeExecution.noPrivileged)
    }

    @Test
    fun `parse engine json - files maxFileSizeBytes 1048576`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(1048576L, config.files.maxFileSizeBytes)
    }

    @Test
    fun `parse engine json - 6 commands`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(6, config.commands.size)
        assertEquals("new", config.commands[0].name)
        assertEquals("help", config.commands[5].name)
    }

    @Test
    fun `GatewayConfig with localWs section enabled=true and port=9090 parses correctly`() {
        val json =
            """
            {
              "channels": {
                "telegram": {
                  "token": "bot-token",
                  "allowedChats": []
                },
                "localWs": {
                  "enabled": true,
                  "port": 9090
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        val console = assertNotNull(config.channels.localWs)
        assertTrue(console.enabled)
        assertEquals(9090, console.port)
    }

    @Test
    fun `GatewayConfig with localWs section enabled=false parses correctly`() {
        val json =
            """
            {
              "channels": {
                "telegram": {
                  "token": "bot-token",
                  "allowedChats": []
                },
                "localWs": {
                  "enabled": false,
                  "port": 37474
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        val console = assertNotNull(config.channels.localWs)
        assertFalse(console.enabled)
        assertEquals(37474, console.port)
    }

    @Test
    fun `GatewayConfig without localWs section uses null default`() {
        val json =
            """
            {
              "channels": {
                "telegram": {
                  "token": "bot-token",
                  "allowedChats": []
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        assertNull(config.channels.localWs)
    }

    @Test
    fun `parse engine json - optional fields absent from JSON parse to null or default`() {
        val minimalJson =
            """
{
  "providers": {
    "glm": {
      "type": "openai-compatible",
      "endpoint": "https://example.com"
    }
  },
  "models": {},
  "routing": {
    "default": "glm/glm-5",
    "fallback": [],
    "tasks": {
      "summarization": "glm/glm-5",
      "subagent": "glm/glm-5"
    }
  },
  "memory": {
    "embedding": {
      "type": "onnx",
      "model": "all-MiniLM-L6-v2"
    },
    "chunking": {
      "size": 400,
      "overlap": 80
    },
    "search": {
      "topK": 10
    }
  },
  "context": {
    "tokenBudget": 8000,
    "subagentHistory": 5
  },
  "processing": {
    "debounceMs": 1500,
    "maxConcurrentLlm": 2,
    "maxToolCallRounds": 10
  },
  "httpRetry": {
    "maxRetries": 2,
    "requestTimeoutMs": 60000,
    "initialBackoffMs": 1000,
    "backoffMultiplier": 2.0
  },
  "logging": {
    "subagentConversations": false
  },
  "codeExecution": {
    "dockerImage": "img",
    "timeout": 30,
    "allowNetwork": false,
    "maxMemory": "128m",
    "maxCpus": "0.5",
    "keepAlive": false,
    "keepAliveIdleTimeoutMin": 5,
    "keepAliveMaxExecutions": 10
  },
  "files": {
    "maxFileSizeBytes": 1048576
  },
  "agents": {
    "default": {"workspace": "/tmp/test"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(minimalJson)
        assertNull(config.providers["glm"]?.apiKey)
    }

    @Test
    fun `engine config round-trip encode and parse`() {
        val config = parseEngineConfig(engineJson)
        val encoded = encodeEngineConfig(config)
        val reparsed = parseEngineConfig(encoded)
        assertEquals(config, reparsed)
    }

    @Test
    fun `gateway config round-trip encode and parse`() {
        val config = parseGatewayConfig(gatewayJson)
        val encoded = encodeGatewayConfig(config)
        val reparsed = parseGatewayConfig(encoded)
        assertEquals(config, reparsed)
    }

    @Test
    fun `parse engine json - hostExecution enabled`() {
        val config = parseEngineConfig(engineJson)
        assertTrue(config.hostExecution.enabled)
    }

    @Test
    fun `parse engine json - hostExecution allowList`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(listOf("df -h", "free -m", "systemctl status *"), config.hostExecution.allowList)
    }

    @Test
    fun `parse engine json - hostExecution notifyList`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(listOf("systemctl restart klaw-*"), config.hostExecution.notifyList)
    }

    @Test
    fun `parse engine json - hostExecution preValidation`() {
        val config = parseEngineConfig(engineJson)
        assertTrue(config.hostExecution.preValidation.enabled)
        assertEquals("anthropic/claude-haiku", config.hostExecution.preValidation.model)
        assertEquals(5, config.hostExecution.preValidation.riskThreshold)
        assertEquals(5000L, config.hostExecution.preValidation.timeoutMs)
    }

    @Test
    fun `parse engine json - hostExecution askTimeoutMin`() {
        val config = parseEngineConfig(engineJson)
        assertEquals(5, config.hostExecution.askTimeoutMin)
    }

    @Test
    fun `hostExecution defaults when absent from json`() {
        val minimalJson =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
  "agents": {"default": {"workspace": "/tmp/test"}}
}
            """.trimIndent()
        val config = parseEngineConfig(minimalJson)
        assertFalse(config.hostExecution.enabled)
        assertTrue(config.hostExecution.allowList.isEmpty())
        assertTrue(config.hostExecution.notifyList.isEmpty())
        assertTrue(config.hostExecution.preValidation.enabled)
        assertEquals("", config.hostExecution.preValidation.model)
        assertEquals(5, config.hostExecution.preValidation.riskThreshold)
        assertEquals(60000L, config.hostExecution.preValidation.timeoutMs)
        assertEquals(0, config.hostExecution.askTimeoutMin)
    }

    @Test
    fun `parse engine json - ignores unknown keys`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
  "agents": {"default": {"workspace": "/tmp/test"}},
  "unknownField": "should be ignored"
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals("a/b", config.routing.default)
    }

    @Test
    fun parseMcpConfig_fullConfig() {
        val json =
            """
{
  "servers": {
    "home-assistant": {
      "transport": "http",
      "url": "http://ha-mcp:8080/mcp",
      "apiKey": "${'$'}{HA_MCP_API_KEY}"
    },
    "filesystem": {
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]
    },
    "disabled-server": {
      "enabled": false,
      "transport": "http",
      "url": "http://unused:8080/mcp"
    }
  }
}
            """.trimIndent()
        val config = parseMcpConfig(json)
        assertEquals(3, config.servers.size)

        val ha = config.servers["home-assistant"]!!
        assertTrue(ha.enabled)
        assertEquals("http", ha.transport)
        assertEquals("http://ha-mcp:8080/mcp", ha.url)
        assertEquals("\${HA_MCP_API_KEY}", ha.apiKey)
        assertNull(ha.command)

        val fs = config.servers["filesystem"]!!
        assertTrue(fs.enabled)
        assertEquals("stdio", fs.transport)
        assertEquals("npx", fs.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"), fs.args)
        assertNull(fs.url)
        assertNull(fs.apiKey)

        val disabled = config.servers["disabled-server"]!!
        assertFalse(disabled.enabled)
    }

    @Test
    fun parseMcpConfig_emptyServers() {
        val config = parseMcpConfig("""{"servers": {}}""")
        assertTrue(config.servers.isEmpty())
    }

    @Test
    fun parseMcpConfig_defaults() {
        val json =
            """
{
  "servers": {
    "test": {
      "transport": "stdio",
      "command": "echo"
    }
  }
}
            """.trimIndent()
        val config = parseMcpConfig(json)
        val server = config.servers["test"]!!
        assertTrue(server.enabled)
        assertEquals(30_000, server.timeoutMs)
        assertEquals(5_000, server.reconnectDelayMs)
        assertEquals(0, server.maxReconnectAttempts)
        assertTrue(server.args.isEmpty())
        assertTrue(server.env.isEmpty())
    }

    @Test
    fun parseMcpConfig_withEnvVars() {
        val json =
            """
{
  "servers": {
    "test": {
      "transport": "stdio",
      "command": "node",
      "env": {"API_KEY": "secret", "DEBUG": "true"}
    }
  }
}
            """.trimIndent()
        val config = parseMcpConfig(json)
        val server = config.servers["test"]!!
        assertEquals(mapOf("API_KEY" to "secret", "DEBUG" to "true"), server.env)
    }

    @Test
    fun parseMcpConfig_ignoresUnknownKeys() {
        val json =
            """
{
  "unknownField": true,
  "servers": {
    "test": {
      "transport": "http",
      "url": "http://localhost:8080",
      "futureField": 42
    }
  }
}
            """.trimIndent()
        val config = parseMcpConfig(json)
        assertEquals(1, config.servers.size)
    }

    @Test
    fun mcpConfigRoundTrip() {
        val config =
            McpConfig(
                servers =
                    mapOf(
                        "test" to
                            McpServerConfig(
                                transport = "http",
                                url = "http://localhost:8080",
                            ),
                    ),
            )
        val json = encodeMcpConfig(config)
        val parsed = parseMcpConfig(json)
        assertEquals(config.servers.size, parsed.servers.size)
        assertEquals("http", parsed.servers["test"]!!.transport)
        assertEquals("http://localhost:8080", parsed.servers["test"]!!.url)
    }

    @Test
    fun parseMcpConfig_invalidServerNameWithDoubleUnderscore() {
        val json = """{"servers": {"bad__name": {"transport": "http", "url": "http://localhost"}}}"""
        assertFailsWith<IllegalArgumentException> { parseMcpConfig(json) }
    }

    @Test
    fun parseMcpConfig_invalidServerNameStartsWithDash() {
        val json = """{"servers": {"-bad": {"transport": "http", "url": "http://localhost"}}}"""
        assertFailsWith<IllegalArgumentException> { parseMcpConfig(json) }
    }

    @Test
    fun `parse engine json - database config with all fields present`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
  "agents": {"default": {"workspace": "/tmp/test"}},
  "database": {
    "busyTimeoutMs": 10000,
    "integrityCheckOnStartup": false,
    "backupEnabled": false,
    "backupInterval": "PT12H",
    "backupMaxCount": 5
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(10000, config.database.busyTimeoutMs)
        assertFalse(config.database.integrityCheckOnStartup)
        assertFalse(config.database.backupEnabled)
        assertEquals("PT12H", config.database.backupInterval)
        assertEquals(5, config.database.backupMaxCount)
    }

    @Test
    fun `parse engine json - database config absent uses defaults`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
  "agents": {"default": {"workspace": "/tmp/test"}}
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(5000, config.database.busyTimeoutMs)
        assertTrue(config.database.integrityCheckOnStartup)
        assertTrue(config.database.backupEnabled)
        assertEquals("PT24H", config.database.backupInterval)
        assertEquals(3, config.database.backupMaxCount)
    }

    @Test
    fun `database config - busyTimeoutMs zero fails validation`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(busyTimeoutMs = 0)
        }
    }

    @Test
    fun `database config - busyTimeoutMs negative fails validation`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(busyTimeoutMs = -1)
        }
    }

    @Test
    fun `database config - backupMaxCount zero fails validation`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(backupMaxCount = 0)
        }
    }

    @Test
    fun `database config - backupMaxCount negative fails validation`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(backupMaxCount = -1)
        }
    }

    @Test
    fun `parse gateway json - discord with allowedGuilds and apiBaseUrl`() {
        val json =
            """
            {
              "channels": {
                "discord": {
                  "enabled": true,
                  "token": "discord_token",
                  "allowedGuilds": [
                    {
                      "guildId": "guild_001",
                      "allowedChannelIds": ["ch_1", "ch_2"],
                      "allowedUserIds": ["user_a", "user_b"]
                    },
                    {
                      "guildId": "guild_002",
                      "allowedChannelIds": [],
                      "allowedUserIds": ["user_c"]
                    }
                  ],
                  "apiBaseUrl": "https://discord.example.com/api"
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        val discord = assertNotNull(config.channels.discord)
        assertTrue(discord.enabled)
        assertEquals(2, discord.allowedGuilds.size)

        val guild1 = discord.allowedGuilds[0]
        assertEquals("guild_001", guild1.guildId)
        assertEquals(listOf("ch_1", "ch_2"), guild1.allowedChannelIds)
        assertEquals(listOf("user_a", "user_b"), guild1.allowedUserIds)

        val guild2 = discord.allowedGuilds[1]
        assertEquals("guild_002", guild2.guildId)
        assertTrue(guild2.allowedChannelIds.isEmpty())
        assertEquals(listOf("user_c"), guild2.allowedUserIds)

        assertEquals("https://discord.example.com/api", discord.apiBaseUrl)
    }

    @Test
    fun `parse gateway json - discord with empty allowedGuilds defaults`() {
        val json =
            """
            {
              "channels": {
                "discord": {
                  "enabled": true,
                  "token": "discord_token"
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        val discord = assertNotNull(config.channels.discord)
        assertTrue(discord.enabled)
        assertTrue(discord.allowedGuilds.isEmpty())
        assertNull(discord.apiBaseUrl)
    }

    @Test
    fun `parse gateway json - discord enabled false by default`() {
        val json =
            """
            {
              "channels": {
                "discord": {
                  "token": "discord_token"
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        val discord = assertNotNull(config.channels.discord)
        assertFalse(discord.enabled)
    }

    @Test
    fun `parse gateway json - discord allowedGuild with defaults for channelIds and userIds`() {
        val json =
            """
            {
              "channels": {
                "discord": {
                  "enabled": true,
                  "token": "t",
                  "allowedGuilds": [{"guildId": "g1"}]
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        val guild = config.channels.discord!!.allowedGuilds[0]
        assertEquals("g1", guild.guildId)
        assertTrue(guild.allowedChannelIds.isEmpty())
        assertTrue(guild.allowedUserIds.isEmpty())
    }

    @Test
    fun `backward compat - existing config without discord section still parses`() {
        val json =
            """
            {
              "channels": {
                "telegram": {
                  "token": "bot_token",
                  "allowedChats": [{"chatId": "123"}]
                }
              }
            }
            """.trimIndent()
        val config = parseGatewayConfig(json)
        assertNull(config.channels.discord)
        assertNotNull(config.channels.telegram)
    }

    @Test
    fun engineConfigParsesWorkspaceField() {
        // Insert workspace field into existing fixture
        val withWorkspace = engineJson.replaceFirst("{", """{"workspace": "/home/user/my-workspace",""")
        val config = parseEngineConfig(withWorkspace)
        assertEquals("/home/user/my-workspace", config.workspace)
    }

    @Test
    fun engineConfigWorkspaceDefaultsToNull() {
        val config = parseEngineConfig(engineJson)
        assertNull(config.workspace)
    }

    @Test
    fun tokenBudgetDefaultsToNull() {
        // Remove tokenBudget from the context section to test default
        val withoutBudget =
            engineJson.replace(
                Regex(""""tokenBudget"\s*:\s*\d+\s*,?\s*"""),
                "",
            )
        val config = parseEngineConfig(withoutBudget)
        assertNull(config.context.tokenBudget, "tokenBudget should default to null when not specified")
    }

    // --- Multi-agent config tests ---

    @Test
    fun `multi-agent minimal config - single default agent parses`() {
        val json =
            """
{
  "providers": {"glm": {"type": "openai-compatible", "endpoint": "https://example.com"}},
  "models": {},
  "routing": {"default": "glm/glm-5"},
  "agents": {
    "default": {
      "workspace": "/home/user/workspace"
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(1, config.effectiveAgents.size)
        assertNotNull(config.effectiveAgents["default"])
        assertEquals("/home/user/workspace", config.effectiveAgents["default"]!!.workspace)
    }

    @Test
    fun `multi-agent config - agent enabled defaults to true`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "myAgent": {
      "workspace": "/tmp/agent"
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertTrue(config.effectiveAgents["myAgent"]!!.enabled)
    }

    @Test
    fun `multi-agent config - disabled agent is in effectiveAgents but enabled=false`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "active": {"workspace": "/tmp/active"},
    "disabled": {"workspace": "/tmp/disabled", "enabled": false}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(2, config.effectiveAgents.size)
        assertTrue(config.effectiveAgents["active"]!!.enabled)
        assertFalse(config.effectiveAgents["disabled"]!!.enabled)
    }

    @Test
    fun `multi-agent config - _defaults key is excluded from effectiveAgents`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "_defaults": {
      "workspace": ""
    },
    "main": {
      "workspace": "/tmp/main"
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertFalse(config.effectiveAgents.containsKey("_defaults"))
        assertEquals(1, config.effectiveAgents.size)
        assertNotNull(config.effectiveAgents["main"])
    }

    @Test
    fun `multi-agent config - _defaults is parsed into agentDefaults`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "_defaults": {
      "workspace": ""
    },
    "main": {
      "workspace": "/tmp/main"
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertNotNull(config.agentDefaults)
    }

    @Test
    fun `multi-agent config - agentDefaults is null when no _defaults key`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertNull(config.agentDefaults)
    }

    @Test
    fun `multi-agent config - per-agent routing override`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {
      "workspace": "/tmp/main",
      "routing": {
        "default": "custom/model",
        "tasks": {"summarization": "sum/model", "subagent": "sub/model"}
      }
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertEquals("custom/model", agent.routing?.default)
        assertEquals("sum/model", agent.routing?.tasks?.summarization)
        assertEquals("sub/model", agent.routing?.tasks?.subagent)
    }

    @Test
    fun `multi-agent config - per-agent processing override`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {
      "workspace": "/tmp/main",
      "processing": {
        "slidingWindow": 20,
        "temperature": 0.8,
        "maxOutputTokens": 4096
      }
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertEquals(20, agent.processing?.slidingWindow)
        assertEquals(0.8, agent.processing?.temperature)
        assertEquals(4096, agent.processing?.maxOutputTokens)
    }

    @Test
    fun `multi-agent config - per-agent memory override`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {
      "workspace": "/tmp/main",
      "memory": {
        "autoRag": {"enabled": false}
      }
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertFalse(agent.memory?.autoRag?.enabled ?: true)
    }

    @Test
    fun `multi-agent config - per-agent heartbeat override`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {
      "workspace": "/tmp/main",
      "heartbeat": {
        "enabled": true,
        "interval": "PT30M",
        "model": "glm/glm-5",
        "channel": "telegram:123"
      }
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertTrue(agent.heartbeat?.enabled ?: false)
        assertEquals("PT30M", agent.heartbeat?.interval)
        assertEquals("glm/glm-5", agent.heartbeat?.model)
        assertEquals("telegram:123", agent.heartbeat?.channel)
    }

    @Test
    fun `multi-agent config - agent limits`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {
      "workspace": "/tmp/main",
      "limits": {
        "maxConcurrentRequests": 5,
        "maxMessagesPerMinute": 30
      }
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertEquals(5, agent.limits?.maxConcurrentRequests)
        assertEquals(30, agent.limits?.maxMessagesPerMinute)
    }

    @Test
    fun `multi-agent config - agent limits default to zero meaning unlimited`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertEquals(0, agent.limits?.maxConcurrentRequests ?: 0)
        assertEquals(0, agent.limits?.maxMessagesPerMinute ?: 0)
    }

    @Test
    fun `multi-agent config - vision override`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {
      "workspace": "/tmp/main",
      "vision": {
        "enabled": true,
        "model": "glm/glm-4v"
      }
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertTrue(agent.vision?.enabled ?: false)
        assertEquals("glm/glm-4v", agent.vision?.model)
    }

    @Test
    fun `multi-agent config - agent tools config`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {
      "workspace": "/tmp/main",
      "tools": {
        "sandbox": {
          "dockerImage": "custom-sandbox:latest",
          "timeout": 60
        },
        "hostExec": {
          "enabled": true,
          "allowList": ["ls", "pwd"]
        }
      }
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        val agent = config.effectiveAgents["main"]!!
        assertEquals("custom-sandbox:latest", agent.tools?.sandbox?.dockerImage)
        assertEquals(60, agent.tools?.sandbox?.timeout)
        assertTrue(agent.tools?.hostExec?.enabled ?: false)
        assertEquals(listOf("ls", "pwd"), agent.tools?.hostExec?.allowList)
    }

    @Test
    fun `multi-agent config - no agents field fails validation`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"}
}
            """.trimIndent()
        assertFailsWith<IllegalArgumentException> { parseEngineConfig(json) }
    }

    @Test
    fun `multi-agent config - empty agents fails validation`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {}
}
            """.trimIndent()
        assertFailsWith<IllegalArgumentException> { parseEngineConfig(json) }
    }

    @Test
    fun `multi-agent config - agent with blank workspace fails validation`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": ""}
  }
}
            """.trimIndent()
        assertFailsWith<IllegalArgumentException> { parseEngineConfig(json) }
    }

    @Test
    fun `multi-agent config - _defaults with blank workspace is allowed`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "_defaults": {"workspace": ""},
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertNotNull(config.agentDefaults)
        assertEquals(1, config.effectiveAgents.size)
    }

    @Test
    fun `multi-agent config - processing defaults when not specified`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        // These should parse without error (defaults applied)
        assertEquals(800L, config.processing.debounceMs)
        assertEquals(3, config.processing.maxConcurrentLlm)
        assertEquals(50, config.processing.maxToolCallRounds)
    }

    @Test
    fun `multi-agent config - routing tasks defaults when not specified`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals("", config.routing.tasks.summarization)
        assertEquals("", config.routing.tasks.subagent)
    }

    @Test
    fun `multi-agent config - search topK defaults to 10`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(10, config.memory.search.topK)
    }

    @Test
    fun `multi-agent config - context subagentHistory defaults to 10`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(10, config.context.subagentHistory)
    }

    @Test
    fun `multi-agent config - heartbeat interval defaults to PT1H`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals("PT1H", config.heartbeat.interval)
    }

    @Test
    fun `multi-agent config - chunking defaults changed to 512 and 64`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b"},
  "agents": {
    "main": {"workspace": "/tmp/main"}
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals(512, config.memory.chunking.size)
        assertEquals(64, config.memory.chunking.overlap)
    }

    @Test
    fun `multi-agent config - full config with per-agent overrides and _defaults`() {
        val json =
            """
{
  "providers": {"glm": {"type": "openai-compatible", "endpoint": "https://example.com", "apiKey": "key"}},
  "models": {},
  "routing": {"default": "glm/glm-5", "fallback": [], "tasks": {"summarization": "glm/glm-5", "subagent": "glm/glm-5"}},
  "agents": {
    "_defaults": {
      "workspace": "",
      "processing": {"slidingWindow": 10}
    },
    "alice": {
      "workspace": "/home/alice",
      "routing": {"default": "glm/glm-4-plus"},
      "limits": {"maxConcurrentRequests": 2, "maxMessagesPerMinute": 10}
    },
    "bob": {
      "workspace": "/home/bob",
      "enabled": false
    }
  }
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertNotNull(config.agentDefaults)
        assertEquals(10, config.agentDefaults!!.processing?.slidingWindow)
        assertEquals(2, config.effectiveAgents.size)
        assertEquals("/home/alice", config.effectiveAgents["alice"]!!.workspace)
        assertEquals("glm/glm-4-plus", config.effectiveAgents["alice"]!!.routing?.default)
        assertEquals(2, config.effectiveAgents["alice"]!!.limits?.maxConcurrentRequests)
        assertFalse(config.effectiveAgents["bob"]!!.enabled)
    }
}
