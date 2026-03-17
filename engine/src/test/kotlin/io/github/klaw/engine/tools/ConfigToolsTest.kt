package io.github.klaw.engine.tools

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConfigToolsTest {
    @TempDir
    lateinit var configDir: File

    private lateinit var shutdownController: ShutdownController
    private lateinit var configTools: ConfigTools

    private val engineJson =
        """
        {
            "providers": {
                "zai": {
                    "type": "openai-compatible",
                    "endpoint": "https://api.zai.com",
                    "apiKey": "real-key-not-env"
                }
            },
            "models": {
                "zai/glm-4-plus": {
                    "maxTokens": 8192,
                    "contextBudget": 32768
                }
            },
            "routing": {
                "default": "zai/glm-4-plus",
                "fallback": [],
                "tasks": {
                    "summarization": "zai/glm-4-plus",
                    "subagent": "zai/glm-4-plus"
                }
            },
            "context": {
                "defaultBudgetTokens": 4096,
                "subagentHistory": 5
            },
            "processing": {
                "debounceMs": 800,
                "maxConcurrentLlm": 3,
                "maxToolCallRounds": 50
            },
            "memory": {
                "embedding": { "type": "onnx", "model": "all-MiniLM-L6-v2" },
                "chunking": { "size": 512, "overlap": 64 },
                "search": { "topK": 10 }
            }
        }
        """.trimIndent()

    private val gatewayJson =
        """
        {
            "channels": {
                "telegram": {
                    "token": "bot-real-token",
                    "allowedChats": []
                }
            }
        }
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        File(configDir, "engine.json").writeText(engineJson)
        File(configDir, "gateway.json").writeText(gatewayJson)

        shutdownController = mockk(relaxed = true)
        configTools = ConfigTools(configDir.absolutePath, shutdownController)
    }

    // ---- config_get ----

    @Test
    fun `config_get engine full config masks sensitive apiKey`() =
        runTest {
            val result = configTools.configGet("engine", null)
            assertTrue(result.contains("***"), "Expected apiKey to be masked: $result")
            assertFalse(result.contains("real-key-not-env"), "Actual key must not appear: $result")
        }

    @Test
    fun `config_get engine full config shows endpoint`() =
        runTest {
            val result = configTools.configGet("engine", null)
            assertTrue(result.contains("https://api.zai.com"), "Endpoint should appear: $result")
        }

    @Test
    fun `config_get engine specific non-sensitive path returns value`() =
        runTest {
            val result = configTools.configGet("engine", "routing.default")
            assertTrue(result.contains("zai/glm-4-plus"), "Expected routing.default value: $result")
        }

    @Test
    fun `config_get engine sensitive path returns masked value`() =
        runTest {
            val result = configTools.configGet("engine", "providers.zai.apiKey")
            assertTrue(result.contains("***"), "Expected masked sensitive value: $result")
            assertFalse(result.contains("real-key-not-env"), "Actual key must not appear: $result")
        }

    @Test
    fun `config_get engine env var in sensitive field is not masked`() =
        runTest {
            File(configDir, "engine.json").writeText(
                engineJson.replace("\"real-key-not-env\"", "\"\${ZAI_API_KEY}\""),
            )
            val result = configTools.configGet("engine", "providers.zai.apiKey")
            assertTrue(result.contains("\${ZAI_API_KEY}"), "Env var reference should not be masked: $result")
        }

    @Test
    fun `config_get engine unknown path returns path not found message`() =
        runTest {
            val result = configTools.configGet("engine", "routing.nonexistent")
            assertTrue(result.contains("not found") || result.contains("Path"), "Expected path-not-found: $result")
        }

    @Test
    fun `config_get gateway full config returns channel info`() =
        runTest {
            val result = configTools.configGet("gateway", null)
            assertTrue(result.contains("channels"), "Expected channels section: $result")
        }

    @Test
    fun `config_get gateway sensitive token is masked`() =
        runTest {
            val result = configTools.configGet("gateway", null)
            assertFalse(result.contains("bot-real-token"), "Token must not appear unmasked: $result")
        }

    // ---- config_set engine ----

    @Test
    fun `config_set engine valid path writes value and schedules shutdown`() =
        runTest {
            val result = configTools.configSet("engine", "routing.default", "zai/glm-4-flash")

            assertTrue(result.contains("restarting") || result.contains("updated"), "Expected restart message: $result")
            val written = File(configDir, "engine.json").readText()
            assertTrue(written.contains("glm-4-flash"), "Expected new value in file: $written")
            verify { shutdownController.requestRestart() }
            verify(exactly = 0) { shutdownController.scheduleShutdown(any()) }
        }

    @Test
    fun `config_set engine invalid value type returns error and does not write`() =
        runTest {
            val originalContent = File(configDir, "engine.json").readText()
            val result = configTools.configSet("engine", "context.defaultBudgetTokens", "not-a-number")

            assertTrue(result.contains("Invalid") || result.contains("invalid"), "Expected error: $result")
            val written = File(configDir, "engine.json").readText()
            assertTrue(written == originalContent, "File must not be modified on type error")
            verify(exactly = 0) { shutdownController.requestRestart() }
        }

    @Test
    fun `config_set engine unknown path returns error and does not write`() =
        runTest {
            val originalContent = File(configDir, "engine.json").readText()
            val result = configTools.configSet("engine", "completely.unknown.path", "value")

            assertTrue(result.contains("Unknown") || result.contains("unknown"), "Expected unknown path error: $result")
            assertTrue(File(configDir, "engine.json").readText() == originalContent, "File must not be modified")
            verify(exactly = 0) { shutdownController.requestRestart() }
        }

    @Test
    fun `config_set engine validation failure returns errors and does not write`() =
        runTest {
            val originalContent = File(configDir, "engine.json").readText()
            // context.defaultBudgetTokens has exclusiveMinimum: 0, so 0 is invalid
            val result = configTools.configSet("engine", "context.defaultBudgetTokens", "0")

            assertTrue(
                result.contains("Validation") || result.contains("validation") || result.contains("failed"),
                "Expected validation error: $result",
            )
            assertTrue(File(configDir, "engine.json").readText() == originalContent, "File must not be modified")
            verify(exactly = 0) { shutdownController.requestRestart() }
        }

    @Test
    fun `config_set engine map section path returns error`() =
        runTest {
            val result = configTools.configSet("engine", "providers", "invalid")
            assertTrue(result.contains("Cannot set") || result.contains("section"), "Expected section error: $result")
            verify(exactly = 0) { shutdownController.requestRestart() }
        }

    // ---- config_set gateway ----

    @Test
    fun `config_set gateway channels path writes value and requests gateway restart`() =
        runTest {
            val result = configTools.configSet("gateway", "channels.telegram.token", "new-bot-token")

            assertTrue(result.contains("restarting") || result.contains("restart"), "Expected restart: $result")
            val written = File(configDir, "gateway.json").readText()
            assertTrue(written.contains("new-bot-token"), "Expected new token in file: $written")
            verify { shutdownController.requestGatewayRestart() }
        }

    @Test
    fun `config_set gateway non-channels path writes value without restart`() =
        runTest {
            File(configDir, "gateway.json").writeText(
                """
                {
                    "channels": {
                        "localWs": {
                            "enabled": false,
                            "port": 37474
                        }
                    }
                }
                """.trimIndent(),
            )
            val result = configTools.configSet("gateway", "channels.localWs.enabled", "true")
            assertTrue(
                result.contains("restarting") || result.contains("restart"),
                "Expected restart for channels path: $result",
            )
            verify { shutdownController.requestGatewayRestart() }
        }

    @Test
    fun `config_set gateway channels console enabled requests gateway restart`() =
        runTest {
            File(configDir, "gateway.json").writeText(
                """
                {
                    "channels": {
                        "localWs": {
                            "enabled": false,
                            "port": 37474
                        }
                    }
                }
                """.trimIndent(),
            )
            val result = configTools.configSet("gateway", "channels.localWs.enabled", "true")

            assertTrue(result.isNotBlank())
            verify { shutdownController.requestGatewayRestart() }
        }
}
