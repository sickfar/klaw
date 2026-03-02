package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.mkdir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DoctorFixCommandTest {
    private val tmpDir = "/tmp/klaw-doctor-fix-test-${platform.posix.getpid()}"
    private val workspaceDir = "$tmpDir/workspace"
    private val dataDir = "$tmpDir/data"
    private val stateDir = "$tmpDir/state"
    private val cacheDir = "$tmpDir/cache"
    private val conversationsDir = "$tmpDir/conversations"
    private val memoryDir = "$tmpDir/memory"
    private val skillsDir = "$tmpDir/skills"
    private val modelsDir = "$tmpDir/models"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun teardown() {
        // Clean up recursively
        io.github.klaw.cli.util
            .deleteRecursively(tmpDir)
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

    private fun readFile(path: String): String? =
        io.github.klaw.cli.util
            .readFileText(path)

    private fun cli(
        engineRunning: Boolean = false,
        commandRunner: (String) -> Int = { 0 },
    ) = KlawCli(
        requestFn = { _, _ -> "{}" },
        conversationsDir = conversationsDir,
        engineChecker = { engineRunning },
        configDir = tmpDir,
        modelsDir = modelsDir,
        workspaceDir = workspaceDir,
        logDir = "/nonexistent/logs",
        commandRunner = commandRunner,
    )

    @Test
    fun `fix creates missing workspace directories`() {
        val result = cli().test("doctor fix")
        assertContains(result.output, "Fixed: created directory")
        assertEquals(0, result.statusCode)
        assertTrue(
            io.github.klaw.cli.util
                .isDirectory(workspaceDir),
        )
    }

    @Test
    fun `fix reports directories already exist`() {
        // Create all directories first
        listOf(tmpDir, dataDir, stateDir, cacheDir, workspaceDir, conversationsDir, memoryDir, skillsDir, modelsDir)
            .forEach { mkdir(it, 0x1EDu) }
        val result = cli().test("doctor fix")
        assertContains(result.output, "Skipped: all directories already exist")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `fix sanitizes config with unknown keys`() {
        writeFile(
            "$tmpDir/engine.json",
            """
            {
              "providers": {"p": {"type": "openai-compatible", "endpoint": "http://localhost"}},
              "models": {},
              "routing": {"default": "p/m", "fallback": [], "tasks": {"summarization": "p/m", "subagent": "p/m"}},
              "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
              "context": {"defaultBudgetTokens": 100, "slidingWindow": 5, "subagentHistory": 3},
              "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1},
              "unknownField": "value"
            }
            """.trimIndent(),
        )
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        val result = cli().test("doctor fix")
        assertContains(result.output, "removed .unknownField from engine.json")
        val content = readFile("$tmpDir/engine.json")!!
        assertTrue("unknownField" !in content, "Unknown field should be removed from file")
    }

    @Test
    fun `fix reports no unknown keys when config is clean`() {
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        val result = cli().test("doctor fix")
        assertContains(result.output, "Skipped: engine.json has no unknown keys")
        assertContains(result.output, "Skipped: gateway.json has no unknown keys")
    }

    @Test
    fun `fix reports missing config when file does not exist`() {
        val result = cli().test("doctor fix")
        assertContains(result.output, "Skipped: engine.json does not exist")
        assertContains(result.output, "Skipped: gateway.json does not exist")
    }

    @Test
    fun `fix starts stopped engine`() {
        var startCalled = false
        val result =
            cli(
                engineRunning = false,
                commandRunner = { cmd ->
                    if ("engine start" in cmd) startCalled = true
                    0
                },
            ).test("doctor fix")
        assertContains(result.output, "Starting engine")
        assertTrue(startCalled, "Should have called engine start")
    }

    @Test
    fun `fix reports engine already running`() {
        val result = cli(engineRunning = true).test("doctor fix")
        assertContains(result.output, "Skipped: engine already running")
    }

    @Test
    fun `fix adds docker socket mount when missing`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile(
            "$tmpDir/docker-compose.json",
            """{"services": {"engine": {"image": "test:latest"}}}""",
        )
        val result = cli().test("doctor fix")
        assertContains(result.output, "Fixed: added docker.sock mount")
        val content = readFile("$tmpDir/docker-compose.json")!!
        assertContains(content, "docker.sock")
    }

    @Test
    fun `fix adds engine bind env when missing`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile(
            "$tmpDir/docker-compose.json",
            """{"services": {"engine": {"image": "test:latest", "volumes": ["/var/run/docker.sock:/var/run/docker.sock"]}}}""",
        )
        val result = cli().test("doctor fix")
        assertContains(result.output, "Fixed: added KLAW_ENGINE_BIND")
        val content = readFile("$tmpDir/docker-compose.json")!!
        assertContains(content, "0.0.0.0")
    }

    @Test
    fun `fix adds gateway engine host when missing`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile(
            "$tmpDir/docker-compose.json",
            """{"services": {"engine": {"image": "test:latest", "volumes": ["/var/run/docker.sock:/var/run/docker.sock"], "environment": {"KLAW_ENGINE_BIND": "0.0.0.0"}}, "gateway": {"image": "test:latest"}}}""",
        )
        val result = cli().test("doctor fix")
        assertContains(result.output, "Fixed: added KLAW_ENGINE_HOST")
    }

    @Test
    fun `fix adds port mapping when missing`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile(
            "$tmpDir/docker-compose.json",
            """{"services": {"engine": {"image": "test:latest", "volumes": ["/var/run/docker.sock:/var/run/docker.sock"], "environment": {"KLAW_ENGINE_BIND": "0.0.0.0"}}, "gateway": {"image": "test:latest", "environment": {"KLAW_ENGINE_HOST": "engine"}}}}""",
        )
        val result = cli().test("doctor fix")
        assertContains(result.output, "Fixed: added 127.0.0.1:7470:7470 port mapping")
        val content = readFile("$tmpDir/docker-compose.json")!!
        assertContains(content, "7470:7470")
    }

    @Test
    fun `fix skips docker checks in native mode`() {
        val result = cli().test("doctor fix")
        // No docker-compose.json related output
        assertTrue(!result.output.contains("docker-compose.json"))
    }

    @Test
    fun `doctor subcommand still works`() {
        val result = cli().test("doctor")
        assertContains(result.output, "Deploy mode")
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
    }
}
