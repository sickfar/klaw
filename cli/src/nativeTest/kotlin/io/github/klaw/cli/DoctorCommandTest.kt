package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import io.github.klaw.common.config.klawJson
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            "$tmpDir/mcp.json",
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
        engineRunning: Boolean = false,
        modelsDir: String = "$tmpDir/models",
        workspace: String = workspaceDir,
        commandOutput: (String) -> String? = { null },
    ) = KlawCli(
        requestFn = { _, _ -> "{}" },
        conversationsDir = "/nonexistent",
        engineChecker = { engineRunning },
        configDir = configDir,
        modelsDir = modelsDir,
        workspaceDir = workspace,
        logDir = "/nonexistent/logs",
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
    fun `doctor reports engine stopped when not responding`() {
        val result = cli(engineRunning = false).test("doctor")
        assertContains(result.output, "stopped")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports engine running when port responsive`() {
        val result = cli(engineRunning = true).test("doctor")
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
        mkdir("$tmpDir/models", 0x1EDu)
        writeFile("$tmpDir/models/embedding.onnx", "")
        val result = cli(engineRunning = true).test("doctor")
        assertContains(result.output, "\u2713")
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
              "context": {"tokenBudget": 100, "subagentHistory": 3},
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
              "context": {"tokenBudget": 100, "subagentHistory": 3},
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
              "context": {"tokenBudget": 100, "subagentHistory": 3},
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

    // --- Docker socket mount ---

    @Test
    fun `doctor reports docker socket mounted when volume present`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile(
            "$tmpDir/docker-compose.json",
            """{"services": {"engine": {"image": "test:latest", "volumes": ["/var/run/docker.sock:/var/run/docker.sock"]}}}""",
        )
        val result = cli().test("doctor")
        assertContains(result.output, "\u2713 Docker socket: mounted")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor reports docker socket not mounted when volume missing`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", MINIMAL_COMPOSE_JSON)
        val result = cli().test("doctor")
        assertContains(result.output, "\u2717 Docker socket: not mounted in engine service")
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
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
}
            """.trimIndent()

        private val MINIMAL_COMPOSE_JSON =
            """{"services": {"engine": {"image": "test:latest"}}}"""
    }

    @Test
    fun `doctor skips mcp when file missing`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result = cli().test("doctor")
        // mcp.json not mentioned when missing (optional)
        assertTrue(!result.output.contains("mcp.json"))
    }

    @Test
    fun `doctor reports valid mcp config`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile(
            "$tmpDir/mcp.json",
            """{"servers": {"test": {"transport": "http", "url": "http://localhost:8080"}}}""",
        )
        val result = cli().test("doctor")
        assertContains(result.output, "mcp.json: valid")
        assertContains(result.output, "test: http")
    }

    @Test
    fun `doctor reports mcp parse error`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile("$tmpDir/mcp.json", "{invalid json")
        val result = cli().test("doctor")
        assertContains(result.output, "mcp.json: parse error")
    }

    @Test
    fun `doctor reports disabled mcp server`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile(
            "$tmpDir/mcp.json",
            """{"servers": {"off": {"enabled": false, "transport": "http", "url": "http://x"}}}""",
        )
        val result = cli().test("doctor")
        assertContains(result.output, "off: disabled")
    }

    @Test
    fun `doctor reports missing command for stdio`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile(
            "$tmpDir/mcp.json",
            """{"servers": {"bad": {"transport": "stdio"}}}""",
        )
        val result = cli().test("doctor")
        assertContains(result.output, "bad: stdio transport requires 'command'")
    }

    @Test
    fun `doctor reports missing url for http`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile(
            "$tmpDir/mcp.json",
            """{"servers": {"bad": {"transport": "http"}}}""",
        )
        val result = cli().test("doctor")
        assertContains(result.output, "bad: http transport requires 'url'")
    }

    @Test
    fun `doctor reports unknown transport`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        writeFile(
            "$tmpDir/mcp.json",
            """{"servers": {"bad": {"transport": "websocket"}}}""",
        )
        val result = cli().test("doctor")
        assertContains(result.output, "bad: unknown transport 'websocket'")
    }

    // --- Skills validation ---

    @Test
    fun `doctor runs skills validation when engine is running`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val calledCommands = mutableListOf<String>()
        val result =
            KlawCli(
                requestFn = { cmd, _ ->
                    calledCommands += cmd
                    """{"valid": true}"""
                },
                conversationsDir = "/nonexistent",
                engineChecker = { true },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor")
        assertTrue(
            calledCommands.contains("skills_validate"),
            "Expected skills_validate to be called, got: $calledCommands",
        )
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor skips skills validation when engine is not running`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result =
            KlawCli(
                requestFn = { _, _ ->
                    throw io.github.klaw.cli.socket
                        .EngineNotRunningException()
                },
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor")
        // Should not crash, just skip
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor dump-schema engine does not run skills validation`() {
        val calledCommands = mutableListOf<String>()
        val result =
            KlawCli(
                requestFn = { cmd, _ ->
                    calledCommands += cmd
                    "{}"
                },
                conversationsDir = "/nonexistent",
                engineChecker = { true },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor --dump-schema engine")
        assertTrue(!calledCommands.contains("skills_validate"), "skills_validate should NOT be called for dump-schema")
        assertEquals(0, result.statusCode)
    }

    // --- JSON output ---

    @Test
    fun `doctor --json outputs valid JSON with checks array`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result = cli().test("doctor --json")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        assertTrue(parsed.containsKey("checks"))
        assertTrue(parsed["checks"]?.jsonArray != null)
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor --json reports ok status for valid config`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result = cli().test("doctor --json")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        val checks = parsed["checks"]?.jsonArray ?: error("no checks")
        val gatewayCheck = checks.first { it.jsonObject["name"]?.jsonPrimitive?.content == "gateway.json" }
        assertEquals("ok", gatewayCheck.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `doctor --json reports fail status for missing config`() {
        val result = cli().test("doctor --json")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        val checks = parsed["checks"]?.jsonArray ?: error("no checks")
        val gatewayCheck = checks.first { it.jsonObject["name"]?.jsonPrimitive?.content == "gateway.json" }
        assertEquals("fail", gatewayCheck.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `doctor --json includes deploy mode`() {
        val result = cli().test("doctor --json")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        assertEquals("native", parsed["deployMode"]?.jsonPrimitive?.content)
    }

    // --- Non-interactive ---

    @Test
    fun `doctor --non-interactive runs without error`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result = cli().test("doctor --non-interactive")
        assertContains(result.output, "Deploy mode")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor --non-interactive --json works together`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result = cli().test("doctor --non-interactive --json")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        assertTrue(parsed.containsKey("checks"))
        assertEquals(0, result.statusCode)
    }

    // --- Deep probe ---

    @Test
    fun `doctor --deep calls doctor_deep engine command`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val calledCommands = mutableListOf<String>()
        val result =
            KlawCli(
                requestFn = { cmd, _ ->
                    calledCommands += cmd
                    if (cmd == "doctor_deep") {
                        """{"embedding":{"status":"ok","type":"onnx"},"database":{"status":"ok"},"providers":[],"mcpServers":[]}"""
                    } else {
                        """{"valid": true}"""
                    }
                },
                conversationsDir = "/nonexistent",
                engineChecker = { true },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor --deep")
        assertTrue(
            calledCommands.contains("doctor_deep"),
            "Expected doctor_deep to be called, got: $calledCommands",
        )
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor --deep shows deep results in text mode`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result =
            KlawCli(
                requestFn = { cmd, _ ->
                    if (cmd == "doctor_deep") {
                        """{"embedding":{"status":"ok","type":"onnx"},"database":{"status":"ok"},"providers":[],"mcpServers":[]}"""
                    } else {
                        """{"valid": true}"""
                    }
                },
                conversationsDir = "/nonexistent",
                engineChecker = { true },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor --deep")
        assertContains(result.output, "Deep Probe")
        assertContains(result.output, "embedding")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor --deep --json includes deep in json output`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result =
            KlawCli(
                requestFn = { cmd, _ ->
                    if (cmd == "doctor_deep") {
                        """{"embedding":{"status":"ok","type":"onnx"},"database":{"status":"ok"},"providers":[],"mcpServers":[]}"""
                    } else {
                        """{"valid": true}"""
                    }
                },
                conversationsDir = "/nonexistent",
                engineChecker = { true },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor --deep --json")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        assertTrue(parsed.containsKey("deep"))
        val deep = parsed["deep"]?.jsonObject
        assertEquals(
            "ok",
            deep
                ?.get("embedding")
                ?.jsonObject
                ?.get("status")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor --deep skips when engine not running`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result =
            KlawCli(
                requestFn = { _, _ ->
                    throw io.github.klaw.cli.socket
                        .EngineNotRunningException()
                },
                conversationsDir = "/nonexistent",
                engineChecker = { false },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor --deep")
        // Should not crash, deep section skipped
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor --json with docker mode includes container checks`() {
        writeFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        writeFile("$tmpDir/docker-compose.json", MINIMAL_COMPOSE_JSON)
        val result = cli(commandOutput = { "" }).test("doctor --json")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        val checks = parsed["checks"]?.jsonArray ?: error("no checks")
        val containerNames = checks.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue(containerNames.any { it?.startsWith("Container") == true })
    }

    // --- Flag combinations ---

    @Test
    fun `doctor --deep --json --non-interactive all flags combined`() {
        writeFile("$tmpDir/gateway.json", """{"channels": {}}""")
        writeFile("$tmpDir/engine.json", MINIMAL_ENGINE_JSON)
        val result =
            KlawCli(
                requestFn = { cmd, _ ->
                    if (cmd == "doctor_deep") {
                        """{"embedding":{"status":"ok"},"database":{"status":"ok"},"providers":[],"mcpServers":[]}"""
                    } else {
                        """{"valid": true}"""
                    }
                },
                conversationsDir = "/nonexistent",
                engineChecker = { true },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor --deep --json --non-interactive")
        val parsed = klawJson.parseToJsonElement(result.output.trim()).jsonObject
        assertTrue(parsed.containsKey("deep"))
        assertTrue(parsed.containsKey("checks"))
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor --dump-schema ignores --json and --deep`() {
        val calledCommands = mutableListOf<String>()
        val result =
            KlawCli(
                requestFn = { cmd, _ ->
                    calledCommands += cmd
                    "{}"
                },
                conversationsDir = "/nonexistent",
                engineChecker = { true },
                configDir = tmpDir,
                modelsDir = "$tmpDir/models",
                workspaceDir = workspaceDir,
                logDir = "/nonexistent/logs",
                doctorCommandOutput = { null },
            ).test("doctor --dump-schema engine --json --deep")
        // dump-schema returns schema JSON, not report JSON
        assertContains(result.output, "\$schema")
        assertTrue(!calledCommands.contains("doctor_deep"), "doctor_deep should not be called with dump-schema")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `doctor fix subcommand still works with new flags on parent`() {
        val result = cli().test("doctor fix")
        // fix should run its own logic
        assertContains(result.output, "Skipped")
        assertEquals(0, result.statusCode)
    }
}
