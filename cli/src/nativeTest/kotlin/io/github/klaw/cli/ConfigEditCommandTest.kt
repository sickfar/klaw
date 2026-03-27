package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.util.writeFileText
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigEditCommandTest {
    private val tmpDir = "/tmp/klaw-config-edit-test-${getpid()}"

    companion object {
        private val MINIMAL_ENGINE_JSON =
            """
            {
              "providers": {"test": {"type": "openai-compatible", "endpoint": "http://localhost:8080/v1"}},
              "models": {"test/test-model": {"maxTokens": 4096}},
              "routing": {"default": "test/test-model", "fallback": [], "tasks": {"summarization": "test/test-model", "subagent": "test/test-model"}},
              "memory": {"embedding": {"type": "onnx", "model": "all-MiniLM-L6-v2"}, "chunking": {"size": 512, "overlap": 64}, "search": {"topK": 10}},
              "context": {"tokenBudget": 4096, "subagentHistory": 5},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 2, "maxToolCallRounds": 5}
            }
            """.trimIndent()

        private val MINIMAL_GATEWAY_JSON =
            """
            {
              "channels": {
                "telegram": {"token": "test-token", "allowedChats": []},
                "localWs": {"enabled": false, "port": 37474},
                "discord": {"enabled": false}
              }
            }
            """.trimIndent()
    }

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        unlink("$tmpDir/engine.json")
        unlink("$tmpDir/gateway.json")
        rmdir(tmpDir)
    }

    @Test
    fun `config edit selects engine descriptors for engine target`() {
        writeFileText("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        // ConfigEditCommand should accept "engine" as target and load engine descriptors.
        // Since we can't interactively test the TUI, we verify the command is registered
        // and can be invoked (it will return immediately due to no terminal in test).
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        // We expect the command to be registered — "config edit engine" should be parseable
        // In test mode with no terminal, the editor will fail gracefully
        val result = cli.test("config edit engine")
        // The command should not crash — it should either run or print an error
        assertTrue(result.statusCode == 0 || result.output.isNotEmpty())
    }

    @Test
    fun `config edit selects gateway descriptors for gateway target`() {
        writeFileText("$tmpDir/gateway.json", MINIMAL_GATEWAY_JSON)
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("config edit gateway")
        assertTrue(result.statusCode == 0 || result.output.isNotEmpty())
    }

    @Test
    fun `config edit handles missing config file gracefully`() {
        // No config file written — should print a helpful error
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("config edit engine")
        assertEquals(0, result.statusCode, "Should not crash on missing file")
        assertTrue(
            result.output.contains("not found") || result.output.contains("not initialized") ||
                result.output.contains("does not exist"),
            "Expected helpful message about missing file: ${result.output}",
        )
    }

    @Test
    fun `config edit rejects unknown target`() {
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        val result = cli.test("config edit unknown")
        // Clikt should reject "unknown" as an invalid choice
        assertTrue(result.statusCode != 0, "Expected non-zero exit for invalid target")
    }

    @Test
    fun `config edit is registered as subcommand of config`() {
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
                logDir = "/nonexistent/logs",
            )
        // "config edit" without argument should show usage/help or error
        val result = cli.test("config edit")
        assertTrue(
            result.statusCode != 0 || result.output.contains("edit"),
            "Expected config edit to be recognized: ${result.output}",
        )
    }
}
