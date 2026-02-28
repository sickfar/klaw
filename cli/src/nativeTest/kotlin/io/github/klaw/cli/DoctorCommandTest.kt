package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rmdir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DoctorCommandTest {
    private val tmpDir = "/tmp/klaw-doctor-test-${platform.posix.getpid()}"
    private val workspaceDir = "$tmpDir/workspace"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun teardown() {
        listOf(
            "$tmpDir/gateway.json",
            "$tmpDir/engine.json",
            "$tmpDir/engine.sock",
            "$tmpDir/deploy.conf",
            "$tmpDir/docker-compose.json",
            "$tmpDir/models/test.onnx",
            "$tmpDir/models",
            workspaceDir,
        ).forEach { path ->
            remove(path)
            rmdir(path)
        }
        rmdir(tmpDir)
    }

    private fun writeFile(
        path: String,
        content: String,
    ) {
        val file = platform.posix.fopen(path, "w")
        if (file != null) {
            platform.posix.fputs(content, file)
            platform.posix.fclose(file)
        }
    }

    private fun cli(
        configDir: String = tmpDir,
        engineSocketPath: String = "$tmpDir/engine.sock",
        modelsDir: String = "$tmpDir/models",
        workspace: String = workspaceDir,
        commandOutput: (String) -> String? = { null },
    ) = KlawCli(
        requestFn = { _, _ -> "{}" },
        conversationsDir = "/nonexistent",
        engineSocketPath = engineSocketPath,
        configDir = configDir,
        modelsDir = modelsDir,
        workspaceDir = workspace,
        doctorCommandOutput = commandOutput,
    )

    @Test
    fun `doctor reports missing gateway json`() {
        val result = cli().test("doctor")
        assertContains(result.output, "gateway.json")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports missing engine json`() {
        val result = cli().test("doctor")
        assertContains(result.output, "engine.json")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports engine stopped when no socket`() {
        val result = cli().test("doctor")
        assertContains(result.output, "stopped")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports engine running when socket exists`() {
        writeFile("$tmpDir/engine.sock", "")
        val result = cli().test("doctor")
        assertContains(result.output, "running")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports missing ONNX model`() {
        val result = cli().test("doctor")
        assertContains(result.output, "onnx")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports all OK when setup is correct`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile("$tmpDir/engine.sock", "")
        mkdir("$tmpDir/models", 0x1EDu)
        writeFile("$tmpDir/models/embedding.onnx", "")
        val result = cli().test("doctor")
        assertContains(result.output, "✓")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports valid config when JSON is correct`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result = cli().test("doctor")
        assertContains(result.output, "gateway.json: valid")
        assertContains(result.output, "engine.json: valid")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports parse error for invalid JSON`() {
        writeFile("$tmpDir/gateway.json", "not valid json")
        writeFile("$tmpDir/engine.json", "also not json")
        val result = cli().test("doctor")
        assertContains(result.output, "gateway.json: parse error")
        assertContains(result.output, "engine.json: parse error")
        assertEquals(0, result.statusCode)
    }

    // --- Deploy mode ---

    @Test
    fun `doctor shows deploy mode native when no deploy conf`() {
        val result = cli().test("doctor")
        assertContains(result.output, "native")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor shows deploy mode hybrid`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("doctor")
        assertContains(result.output, "hybrid")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor shows deploy mode docker`() {
        writeFile("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val result = cli().test("doctor")
        assertContains(result.output, "docker")
        assertEquals(0, result.statusCode)
    }

    // --- Workspace ---

    @Test
    fun `doctor reports workspace found`() {
        mkdir(workspaceDir, 0x1EDu)
        val result = cli().test("doctor")
        assertContains(result.output, "Workspace")
        assertContains(result.output, "found")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports workspace missing`() {
        val result = cli().test("doctor")
        assertContains(result.output, "Workspace")
        assertContains(result.output, "missing")
        assertEquals(0, result.statusCode)
    }

    // --- Docker compose file (hybrid/docker only) ---

    @Test
    fun `doctor reports compose file valid in hybrid mode`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", MINIMAL_COMPOSE_JSON)
        val result = cli().test("doctor")
        assertContains(result.output, "docker-compose.json: valid")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports compose file missing in hybrid mode`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val result = cli().test("doctor")
        assertContains(result.output, "docker-compose.json")
        assertContains(result.output, "missing")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor skips docker checks in native mode`() {
        val result = cli().test("doctor")
        // Should NOT contain docker-compose.json check or container status
        assertTrue(!result.output.contains("docker-compose.json"))
        assertTrue(!result.output.contains("Container"))
        assertEquals(0, result.statusCode)
    }

    // --- Container status (hybrid/docker only) ---

    @Test
    fun `doctor reports containers running in hybrid mode`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", MINIMAL_COMPOSE_JSON)
        val result =
            cli(commandOutput = { "engine\ngateway" }).test("doctor")
        assertContains(result.output, "engine")
        assertContains(result.output, "running")
        assertContains(result.output, "gateway")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports containers stopped in hybrid mode`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", MINIMAL_COMPOSE_JSON)
        val result = cli(commandOutput = { "" }).test("doctor")
        assertContains(result.output, "engine")
        assertContains(result.output, "stopped")
        assertContains(result.output, "gateway")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports docker unavailable when command fails`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", MINIMAL_COMPOSE_JSON)
        val result = cli(commandOutput = { null }).test("doctor")
        assertContains(result.output, "Docker")
        assertContains(result.output, "unavailable")
        assertEquals(0, result.statusCode)
    }

    // --- Schema validation ---

    @Test
    fun `doctor reports schema error for missing required field`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        // engine.json missing "routing" required field
        writeFile(
            "$tmpDir/engine.json",
            """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """.trimIndent(),
        )
        val result = cli().test("doctor")
        assertContains(result.output, ".routing")
        assertContains(result.output, "engine.json: invalid")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports schema error for wrong type`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        // debounceMs as string instead of integer
        writeFile(
            "$tmpDir/engine.json",
            """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
              "processing": {"debounceMs": "bad", "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """.trimIndent(),
        )
        val result = cli().test("doctor")
        assertContains(result.output, ".processing.debounceMs")
        assertContains(result.output, "engine.json: invalid")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports schema error for constraint violation`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        // chunking size = -1 violates exclusiveMinimum: 0
        writeFile(
            "$tmpDir/engine.json",
            """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": -1, "overlap": 0}, "search": {"topK": 5}},
              "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
            }
            """.trimIndent(),
        )
        val result = cli().test("doctor")
        assertContains(result.output, ".memory.chunking.size")
        assertContains(result.output, "engine.json: invalid")
        assertEquals(0, result.statusCode)
    }

    // --- dump-schema ---

    @Test
    fun `doctor dump-schema engine outputs valid JSON with schema key`() {
        val result = cli().test("doctor --dump-schema engine")
        assertContains(result.output, "\$schema")
        assertContains(result.output, "draft-07")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor dump-schema gateway outputs valid JSON with schema key`() {
        val result = cli().test("doctor --dump-schema gateway")
        assertContains(result.output, "\$schema")
        assertContains(result.output, "draft-07")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor dump-schema compose outputs valid JSON with schema key`() {
        val result = cli().test("doctor --dump-schema compose")
        assertContains(result.output, "\$schema")
        assertContains(result.output, "draft-07")
        assertEquals(0, result.statusCode)
    }

    // --- Compose schema validation ---

    @Test
    fun `doctor reports compose parse error for invalid JSON`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", "not valid json")
        val result = cli().test("doctor")
        assertContains(result.output, "docker-compose.json: parse error")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports compose schema error for missing services`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", """{"volumes": {}}""")
        val result = cli().test("doctor")
        assertContains(result.output, "docker-compose.json: invalid")
        assertContains(result.output, ".services")
        assertEquals(0, result.statusCode)
    }

    companion object {
        private val MINIMAL_ENGINE_JSON =
            """
{
  "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
  "models": {},
  "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
}
            """.trimIndent()

        private val MINIMAL_COMPOSE_JSON =
            """{"services": {"engine": {"image": "test:latest"}}}"""
    }
}
