package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigCommandTest {
    private val tmpDir = "/tmp/klaw-config-test-${getpid()}"

    companion object {
        private val MINIMAL_ENGINE_JSON =
            """
            {
              "providers": {"test": {"type": "openai-compatible", "endpoint": "http://localhost:8080/v1"}},
              "models": {"test/test-model": {"maxTokens": 4096}},
              "routing": {"default": "glm/glm-4-plus", "fallback": [], "tasks": {"summarization": "test/test-model", "subagent": "test/test-model"}},
              "memory": {"embedding": {"type": "onnx", "model": "all-MiniLM-L6-v2"}, "chunking": {"size": 512, "overlap": 64}, "search": {"topK": 10}},
              "context": {"defaultBudgetTokens": 4096, "slidingWindow": 10, "subagentHistory": 5},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 2, "maxToolCallRounds": 5}
            }
            """.trimIndent()
    }

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        platform.posix.unlink("$tmpDir/engine.json")
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `config set updates value in engine json`() {
        writeFileText("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
            )
        val result = cli.test("config set default new/model")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        val updated = readFileText("$tmpDir/engine.json")
        assertNotNull(updated)
        assertTrue(updated.contains("new/model"), "Expected updated value in:\n$updated")
    }

    @Test
    fun `config set prints restart warning`() {
        writeFileText("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
            )
        val result = cli.test("config set default new/model")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Restart"), "Expected 'Restart' warning in: ${result.output}")
    }

    @Test
    fun `config set handles missing engine json gracefully`() {
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                configDir = tmpDir,
                modelsDir = "/nonexistent",
            )
        val result = cli.test("config set default new/model")
        assertEquals(0, result.statusCode, "Should not crash on missing file")
        assertTrue(
            result.output.contains("not found") || result.output.contains("not initialized"),
            "Expected helpful message: ${result.output}",
        )
    }
}
