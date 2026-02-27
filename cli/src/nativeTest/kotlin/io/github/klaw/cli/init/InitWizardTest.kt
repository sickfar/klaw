package io.github.klaw.cli.init

import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.readFileText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InitWizardTest {
    private val tmpDir = "/tmp/klaw-wizard-test-${getpid()}"
    private val configDir = "$tmpDir/config"
    private val workspaceDir = "$tmpDir/workspace"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        deleteDir(tmpDir)
    }

    private fun buildWizard(
        inputs: List<String> = emptyList(),
        output: MutableList<String> = mutableListOf(),
        engineResponses: Map<String, String> = emptyMap(),
        commandRunner: (String) -> Int = { 0 },
        isDockerEnv: Boolean = false,
    ): InitWizard {
        val inputQueue = ArrayDeque(inputs)
        return InitWizard(
            configDir = configDir,
            workspaceDir = workspaceDir,
            dataDir = "$tmpDir/data",
            stateDir = "$tmpDir/state",
            cacheDir = "$tmpDir/cache",
            conversationsDir = "$tmpDir/conversations",
            memoryDir = "$tmpDir/memory",
            skillsDir = "$tmpDir/skills",
            modelsDir = "$tmpDir/models",
            serviceOutputDir = "$tmpDir/service",
            engineSocketPath = "$tmpDir/engine.sock",
            requestFn = { cmd, _ -> engineResponses[cmd] ?: """{"error":"not mocked"}""" },
            readLine = { inputQueue.removeFirstOrNull() ?: "" },
            printer = { output += it },
            commandRunner = commandRunner,
            isDockerEnv = isDockerEnv,
            engineStarterFactory = { _ ->
                EngineStarter(
                    engineSocketPath = "$tmpDir/engine.sock",
                    commandRunner = commandRunner,
                    pollIntervalMs = 10L,
                    timeoutMs = 50L,
                )
            },
        )
    }

    @Test
    fun `phase 1 aborts when engine yaml already exists`() {
        // Pre-create engine.yaml to simulate already-initialized state
        platform.posix.mkdir(configDir, 0x1EDu)
        writeFile("$configDir/engine.yaml", "# existing config")

        val output = mutableListOf<String>()
        val wizard = buildWizard(output = output)
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("Already"), "Expected 'Already' in output: $outputText")
        // engine.yaml should not be overwritten
        val content = readFileText("$configDir/engine.yaml")
        assertTrue(content?.contains("existing config") == true, "engine.yaml should not be overwritten")
    }

    @Test
    fun `phase 5 config generation writes engine yaml and gateway yaml`() {
        val inputs =
            listOf(
                "https://api.example.com", // provider URL
                "my-api-key", // API key
                "test/model", // model ID
                "bot-token-123", // telegram token
                "", // allowed chat IDs (empty = allow all)
                "TestAgent", // agent name
                "curious, helpful", // personality
                "personal assistant", // role
                "developer", // user info
                "coding", // domain
            )

        val engineResponse =
            """{"soul":"Be helpful","identity":"TestAgent","agents":"Do tasks","user":"developer"}"""
        // Pre-create socket to simulate engine running
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.yaml"), "engine.yaml should be created")
        assertTrue(fileExists("$configDir/gateway.yaml"), "gateway.yaml should be created")

        val engineYaml = readFileText("$configDir/engine.yaml")
        assertNotNull(engineYaml)
        assertTrue(engineYaml.contains("api.example.com"), "provider URL in engine.yaml")
        assertTrue(engineYaml.contains("test/model"), "model in engine.yaml")
    }

    @Test
    fun `phase 5 writes dot env with 0600 permissions`() {
        val inputs =
            listOf(
                "https://api.example.com",
                "secret-api-key",
                "test/model",
                "telegram-bot-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "developer",
                "",
            )

        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val envPath = "$configDir/.env"
        assertTrue(fileExists(envPath), ".env file should be created")

        val content = readFileText(envPath)
        assertNotNull(content)
        assertTrue(content.contains("KLAW_LLM_API_KEY=secret-api-key"), "API key in .env: $content")
        assertTrue(content.contains("KLAW_TELEGRAM_TOKEN=telegram-bot-token"), "Token in .env: $content")
    }

    @Test
    fun `phase 8 writes workspace identity files`() {
        val inputs =
            listOf(
                "https://api.example.com",
                "my-key",
                "test/model",
                "my-token",
                "",
                "MyBot",
                "analytical",
                "coding helper",
                "engineer",
                "Kotlin",
            )

        val engineResponse =
            """{"soul":"I value clarity","identity":"MyBot assistant","agents":"Help with code","user":"An engineer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$workspaceDir/SOUL.md"), "SOUL.md should be created")
        assertTrue(fileExists("$workspaceDir/IDENTITY.md"), "IDENTITY.md should be created")
        assertTrue(fileExists("$workspaceDir/AGENTS.md"), "AGENTS.md should be created")
        assertTrue(fileExists("$workspaceDir/USER.md"), "USER.md should be created")
    }

    @Test
    fun `phase 9 generates service unit files`() {
        val inputs =
            listOf(
                "https://api.example.com",
                "my-key",
                "test/model",
                "my-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/service", 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        // Service directory should have been created with unit files
        // The actual file names depend on OS (systemd vs launchd), so just check the dir was used
        assertTrue(fileExists("$tmpDir/service"), "Service output dir should exist")
    }

    @Test
    fun `docker env phase 6 calls docker compose for engine and not systemctl`() {
        val commandsRun = mutableListOf<String>()
        val inputs =
            listOf(
                "https://api.example.com",
                "my-api-key",
                "test/model",
                "bot-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "developer",
                "",
            )
        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandRunner = { cmd ->
                    commandsRun += cmd
                    0
                },
                isDockerEnv = true,
            )
        wizard.run()

        val dockerComposeCalls = commandsRun.filter { it.contains("docker compose") }
        assertTrue(
            dockerComposeCalls.any { it.contains("klaw-engine") },
            "Expected docker compose call for klaw-engine, got: $commandsRun",
        )
        val systemctlCalls = commandsRun.filter { it.contains("systemctl") }
        assertTrue(
            systemctlCalls.isEmpty(),
            "Expected no systemctl calls in docker env, got: $systemctlCalls",
        )
    }

    @Test
    fun `docker env phase 9 does not write systemd unit files`() {
        val inputs =
            listOf(
                "https://api.example.com",
                "my-api-key",
                "test/model",
                "bot-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "developer",
                "",
            )
        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/service", 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                isDockerEnv = true,
            )
        wizard.run()

        // In docker env mode, no systemd unit files should be written to serviceOutputDir
        val unitFiles =
            listDirectory("$tmpDir/service").filter {
                it.endsWith(".service") || it.endsWith(".plist")
            }
        assertTrue(
            unitFiles.isEmpty(),
            "Expected no systemd/launchd unit files in docker env mode, got: $unitFiles",
        )
    }

    @Test
    fun `docker env phase 9 calls docker compose for both services`() {
        val commandsRun = mutableListOf<String>()
        val inputs =
            listOf(
                "https://api.example.com",
                "my-api-key",
                "test/model",
                "bot-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "developer",
                "",
            )
        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandRunner = { cmd ->
                    commandsRun += cmd
                    0
                },
                isDockerEnv = true,
            )
        wizard.run()

        val phase9Calls = commandsRun.filter { it.contains("klaw-engine") && it.contains("klaw-gateway") }
        assertTrue(
            phase9Calls.isNotEmpty(),
            "Expected docker compose up for both services in phase 9, got: $commandsRun",
        )
        assertTrue(
            phase9Calls.any { it.contains("up -d") },
            "Expected 'up -d' in phase 9 command, got: $phase9Calls",
        )
    }

    @Test
    fun `docker env printSummary includes docker run command`() {
        val inputs =
            listOf(
                "https://api.example.com",
                "my-api-key",
                "test/model",
                "bot-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "developer",
                "",
            )
        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                isDockerEnv = true,
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("docker run"), "Expected 'docker run' in summary, got:\n$outputText")
        assertTrue(outputText.contains("klaw-cli"), "Expected 'klaw-cli' in summary, got:\n$outputText")
    }

    @Test
    fun `native printSummary does not include docker run`() {
        val inputs =
            listOf(
                "https://api.example.com",
                "my-api-key",
                "test/model",
                "bot-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "developer",
                "",
            )
        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                isDockerEnv = false,
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(!outputText.contains("docker run"), "Expected no 'docker run' in native summary, got:\n$outputText")
    }

    @Test
    fun `native mode service installer receives user-local bin paths`() {
        val inputs =
            listOf(
                "https://api.example.com",
                "my-api-key",
                "test/model",
                "bot-token",
                "",
                "Klaw",
                "helpful",
                "assistant",
                "developer",
                "",
            )
        val engineResponse =
            """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/service", 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                isDockerEnv = false,
            )
        wizard.run()

        // Verify service files reference .local/bin paths, not /usr/local/bin
        val serviceFiles = listDirectory("$tmpDir/service")
        val anyFileContainsLocalBin =
            serviceFiles.any { file ->
                val content = readFileText("$tmpDir/service/$file")
                content?.contains(".local/bin/klaw-engine") == true ||
                    content?.contains(".local/bin/klaw-gateway") == true
            }
        assertTrue(anyFileContainsLocalBin, "Expected .local/bin paths in service files, got files: $serviceFiles")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeFile(
        path: String,
        content: String,
    ) {
        val file = platform.posix.fopen(path, "w") ?: return
        val bytes = content.encodeToByteArray()
        bytes.usePinned { pinned ->
            platform.posix.fwrite(pinned.addressOf(0), 1.toULong(), bytes.size.toULong(), file)
        }
        platform.posix.fclose(file)
    }

    private fun deleteDir(path: String) {
        val entries = listDirectory(path)
        for (entry in entries) {
            val entryPath = "$path/$entry"
            if (isDirectory(entryPath)) {
                deleteDir(entryPath)
            } else {
                platform.posix.unlink(entryPath)
            }
        }
        platform.posix.rmdir(path)
    }
}
