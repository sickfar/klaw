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

    @Suppress("LongParameterList")
    private fun buildWizard(
        inputs: List<String> = emptyList(),
        output: MutableList<String> = mutableListOf(),
        engineResponses: Map<String, String> = emptyMap(),
        commandRunner: (String) -> Int = { 0 },
        commandOutput: (String) -> String? = { null },
        radioSelector: (List<String>, String) -> Int? = { _, _ -> 0 },
        modeSelector: (List<String>, String) -> Int? = { _, _ -> 0 },
        isDockerEnv: Boolean = false,
        readLineOverride: (() -> String?)? = null,
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
            readLine = readLineOverride ?: { inputQueue.removeFirstOrNull() },
            printer = { output += it },
            commandRunner = commandRunner,
            commandOutput = commandOutput,
            radioSelector = radioSelector,
            modeSelector = modeSelector,
            isDockerEnv = isDockerEnv,
            engineStarterFactory = { _, _ ->
                EngineStarter(
                    engineSocketPath = "$tmpDir/engine.sock",
                    commandRunner = commandRunner,
                    pollIntervalMs = 10L,
                    timeoutMs = 50L,
                )
            },
        )
    }

    // --- Phase 1: pre-check ---

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

    // --- Exit / EOF handling ---

    @Test
    fun `null readline at first prompt exits wizard early and writes no files`() {
        platform.posix.mkdir(configDir, 0x1EDu)
        val wizard = buildWizard(readLineOverride = { null })
        wizard.run()

        assertTrue(!fileExists("$configDir/engine.yaml"), "engine.yaml should not be created on EOF")
        assertTrue(!fileExists("$configDir/gateway.yaml"), "gateway.yaml should not be created on EOF")
    }

    @Test
    fun `esc input at api key prompt exits wizard early and writes no files`() {
        platform.posix.mkdir(configDir, 0x1EDu)
        val wizard = buildWizard(readLineOverride = { "\u001B" })
        wizard.run()

        assertTrue(!fileExists("$configDir/engine.yaml"), "engine.yaml should not be created on ESC")
        assertTrue(!fileExists("$configDir/gateway.yaml"), "gateway.yaml should not be created on ESC")
    }

    @Test
    fun `null readline at telegram prompt exits wizard early and writes no config files`() {
        // 2 inputs consumed by Phase 4 (key, model), then null at telegram prompt
        val inputQueue = ArrayDeque(listOf("key", "test/model"))
        platform.posix.mkdir(configDir, 0x1EDu)
        val wizard =
            buildWizard(readLineOverride = {
                inputQueue.removeFirstOrNull() // returns null once all 2 consumed
            })
        wizard.run()

        assertTrue(!fileExists("$configDir/engine.yaml"), "engine.yaml should not be created on EOF at telegram prompt")
        assertTrue(!fileExists("$configDir/gateway.yaml"), "gateway.yaml should not be created on EOF at telegram prompt")
    }

    // --- Skip Telegram ---

    @Test
    fun `answering n at configure telegram skips token prompt and omits telegram from gateway yaml`() {
        val inputs =
            listOf(
                "my-key", // API key
                "test/model", // model text
                "n", // Configure Telegram? → n = skip
                // NO telegram token or chat IDs prompts
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayYaml = readFileText("$configDir/gateway.yaml")
        assertNotNull(gatewayYaml)
        assertTrue(
            !gatewayYaml.contains("telegram:"),
            "Expected no telegram section when skipped:\n$gatewayYaml",
        )
    }

    @Test
    fun `answering n at configure telegram writes empty KLAW_TELEGRAM_TOKEN in env`() {
        val inputs =
            listOf(
                "my-key",
                "test/model",
                "n", // skip telegram
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val content = readFileText("$configDir/.env")
        assertNotNull(content)
        // Token key must be present but have no value
        assertTrue(content.contains("KLAW_TELEGRAM_TOKEN="), "Expected KLAW_TELEGRAM_TOKEN key in .env:\n$content")
        val tokenLine = content.lines().firstOrNull { it.startsWith("KLAW_TELEGRAM_TOKEN=") } ?: ""
        assertTrue(
            tokenLine == "KLAW_TELEGRAM_TOKEN=",
            "Expected empty token value, got: '$tokenLine'",
        )
    }

    @Test
    fun `answering y at configure telegram includes telegram section in gateway yaml`() {
        val inputs =
            listOf(
                "my-key",
                "test/model",
                "y", // configure telegram
                "my-bot-token", // token
                "", // chat IDs
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayYaml = readFileText("$configDir/gateway.yaml")
        assertNotNull(gatewayYaml)
        assertTrue(gatewayYaml.contains("telegram"), "Expected telegram section:\n$gatewayYaml")
        assertTrue(gatewayYaml.contains("KLAW_TELEGRAM_TOKEN"), "Expected token env var:\n$gatewayYaml")
    }

    @Test
    fun `blank answer at configure telegram defaults to y and includes telegram section`() {
        val inputs =
            listOf(
                "my-key",
                "test/model",
                "", // blank = default Y
                "my-bot-token",
                "",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayYaml = readFileText("$configDir/gateway.yaml")
        assertNotNull(gatewayYaml)
        assertTrue(gatewayYaml.contains("telegram"), "Expected telegram section on blank answer:\n$gatewayYaml")
    }

    // --- API key validation ---

    @Test
    fun `valid api key response shows check mark in output`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "valid-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { """{"data":[{"id":"model-1"}]}""" },
                radioSelector = { _, prompt -> if (prompt.contains("LLM")) 0 else null },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val text = output.joinToString("\n")
        assertTrue(text.contains("✓"), "Expected ✓ in output on valid key:\n$text")
    }

    @Test
    fun `invalid api key response shows warning in output`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "bad-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { """{"error":"unauthorized"}""" },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val text = output.joinToString("\n")
        assertTrue(text.contains("⚠"), "Expected ⚠ in output on invalid key:\n$text")
    }

    @Test
    fun `null command output continues wizard without crash`() {
        val inputs =
            listOf(
                "key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { null },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(fileExists("$configDir/engine.yaml"), "Wizard should complete even with null commandOutput")
    }

    @Test
    fun `api key with single quote skips validation and continues`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "key'with'quotes",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )
        val commandOutputCalled = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { cmd ->
                    commandOutputCalled += cmd
                    null
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(commandOutputCalled.isEmpty(), "commandOutput should not be called for unsafe key")
    }

    // --- Model selection ---

    @Test
    fun `radio selector called with fetched models and index 0 selects first model with provider prefix`() {
        val modelsJson = """{"data":[{"id":"glm-5"},{"id":"glm-4-plus"}]}"""
        val capturedModels = mutableListOf<String>()

        // When radio selector is called, no model text prompt is shown
        // Provider alias comes automatically from selected provider
        val inputs =
            listOf(
                "valid-key",
                // radio selection at index 0 (glm-5) via lambda — no text prompts for URL or alias
                "n", // telegram
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { modelsJson },
                radioSelector = { items, prompt ->
                    if (!prompt.contains("LLM")) capturedModels += items
                    0 // select first item (provider or model)
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(capturedModels.isNotEmpty(), "RadioSelector should have been called with model names")
        assertTrue(capturedModels.contains("glm-5"), "Expected 'glm-5' in model list: $capturedModels")

        val engineYaml = readFileText("$configDir/engine.yaml")
        assertNotNull(engineYaml)
        assertTrue(engineYaml.contains("zai/glm-5"), "Expected 'zai/glm-5' in engine.yaml:\n$engineYaml")
    }

    @Test
    fun `radio selector returns null falls back to text prompt for model`() {
        val modelsJson = """{"data":[{"id":"glm-5"},{"id":"glm-4-plus"}]}"""

        val inputs =
            listOf(
                "valid-key",
                // radioSelector returns null for model → text prompt
                "my/custom-model", // text fallback
                "n", // telegram
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { modelsJson },
                radioSelector = { _, prompt -> if (prompt.contains("LLM")) 0 else null },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val engineYaml = readFileText("$configDir/engine.yaml")
        assertNotNull(engineYaml)
        assertTrue(engineYaml.contains("my/custom-model"), "Expected manual model in engine.yaml:\n$engineYaml")
    }

    @Test
    fun `command output null skips radio selector and uses text prompt for model`() {
        val modelRadioSelectorCalled = mutableListOf<Boolean>()

        val inputs =
            listOf(
                "key",
                "zai/glm-5", // text prompt (no models fetched)
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { null },
                radioSelector = { _, prompt ->
                    if (!prompt.contains("LLM")) modelRadioSelectorCalled += true
                    0
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(modelRadioSelectorCalled.isEmpty(), "RadioSelector should not be called for model when no models fetched")

        val engineYaml = readFileText("$configDir/engine.yaml")
        assertNotNull(engineYaml)
        assertTrue(engineYaml.contains("zai/glm-5"), "Expected typed model in engine.yaml:\n$engineYaml")
    }

    // --- Existing tests (updated inputs to include telegram? prompt) ---

    @Test
    fun `phase 5 config generation writes engine yaml and gateway yaml`() {
        val inputs =
            listOf(
                "my-api-key", // API key
                "test/model", // model ID
                "", // Configure Telegram? [Y/n]: blank = Y
                "bot-token-123", // telegram token
                "", // allowed chat IDs (empty = allow all)
                "n", // Phase 5: disable console
                "TestAgent", // agent name
                "curious, helpful", // personality
                "personal assistant", // role
                "developer", // user info
                "coding", // domain
            )

        val engineResponse =
            """{"soul":"Be helpful","identity":"TestAgent","agents":"Do tasks","user":"developer"}"""
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
        assertTrue(engineYaml.contains("api.z.ai"), "provider URL in engine.yaml")
        assertTrue(engineYaml.contains("test/model"), "model in engine.yaml")
    }

    @Test
    fun `phase 5 writes dot env with 0600 permissions`() {
        val inputs =
            listOf(
                "secret-api-key",
                "test/model",
                "", // Configure Telegram? blank = Y
                "telegram-bot-token",
                "",
                "n", // Phase 5: disable console
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
        assertTrue(content.contains("ZAI_API_KEY=secret-api-key"), "API key in .env: $content")
        assertTrue(content.contains("KLAW_TELEGRAM_TOKEN=telegram-bot-token"), "Token in .env: $content")
    }

    @Test
    fun `phase 8 writes workspace identity files`() {
        val inputs =
            listOf(
                "my-key",
                "test/model",
                "", // telegram? Y
                "my-token",
                "",
                "n", // Phase 5: disable console
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
                "my-key",
                "test/model",
                "", // telegram? Y
                "my-token",
                "",
                "n", // Phase 5: disable console
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

        assertTrue(fileExists("$tmpDir/service"), "Service output dir should exist")
    }

    @Test
    fun `docker env phase 6 calls docker compose for engine and not systemctl`() {
        val commandsRun = mutableListOf<String>()
        val inputs =
            listOf(
                "", // docker tag (Phase 2, blank = latest)
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // Phase 6: disable console
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
            dockerComposeCalls.any { it.contains("engine") },
            "Expected docker compose call for engine, got: $commandsRun",
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
                "", // docker tag (Phase 2)
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // Phase 6: disable console
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
                "", // docker tag (Phase 2)
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // Phase 6: disable console
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

        val phase9Calls = commandsRun.filter { it.contains("engine") && it.contains("gateway") && it.contains("docker compose") }
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
                "", // docker tag (Phase 2)
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // Phase 6: disable console
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
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // Phase 5: disable console
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
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // Phase 5: disable console
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

        val serviceFiles = listDirectory("$tmpDir/service")
        val anyFileContainsLocalBin =
            serviceFiles.any { file ->
                val content = readFileText("$tmpDir/service/$file")
                content?.contains(".local/bin/klaw-engine") == true ||
                    content?.contains(".local/bin/klaw-gateway") == true
            }
        assertTrue(anyFileContainsLocalBin, "Expected .local/bin paths in service files, got files: $serviceFiles")
    }

    @Test
    fun `phase 5 with console enabled writes console section with enabled=true and port to gateway yaml`() {
        val inputs =
            listOf(
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token-123",
                "", // allowed chat IDs
                "y", // Phase 5: enable console
                "37474", // Phase 5: port
                "Klaw",
                "curious, helpful",
                "personal assistant",
                "developer",
                "",
            )

        val engineResponse = """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayYaml = readFileText("$configDir/gateway.yaml")
        assertNotNull(gatewayYaml)
        assertTrue(gatewayYaml.contains("enabled: true"), "Expected 'enabled: true' in gateway.yaml:\n$gatewayYaml")
        assertTrue(gatewayYaml.contains("port: 37474"), "Expected 'port: 37474' in gateway.yaml:\n$gatewayYaml")
    }

    @Test
    fun `phase 5 with console disabled omits console section from gateway yaml`() {
        val inputs =
            listOf(
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token-123",
                "", // allowed chat IDs
                "n", // Phase 5: do not enable console
                "Klaw",
                "curious, helpful",
                "personal assistant",
                "developer",
                "",
            )

        val engineResponse = """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayYaml = readFileText("$configDir/gateway.yaml")
        assertNotNull(gatewayYaml)
        assertTrue(!gatewayYaml.contains("console:"), "Expected no console section in gateway.yaml:\n$gatewayYaml")
    }

    @Test
    fun `phase 5 with console enabled and custom port 9090 uses port 9090 in gateway yaml`() {
        val inputs =
            listOf(
                "my-api-key",
                "test/model",
                "", // telegram? Y
                "bot-token-123",
                "", // allowed chat IDs
                "y", // Phase 5: enable console
                "9090", // Phase 5: custom port
                "Klaw",
                "curious, helpful",
                "personal assistant",
                "developer",
                "",
            )

        val engineResponse = """{"soul":"Be helpful","identity":"Klaw","agents":"Do tasks","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayYaml = readFileText("$configDir/gateway.yaml")
        assertNotNull(gatewayYaml)
        assertTrue(gatewayYaml.contains("port: 9090"), "Expected 'port: 9090' in gateway.yaml:\n$gatewayYaml")
    }

    // --- Hybrid mode tests ---

    @Test
    fun `hybrid mode phase 2 selects docker services and prompts for docker tag`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "unstable", // docker tag
                "my-key",
                "test/model",
                "n", // telegram
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 }, // 1 = Docker services (hybrid)
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("Deployment mode"), "Expected 'Deployment mode' phase in output:\n$outputText")
    }

    @Test
    fun `hybrid mode phase 7 writes docker-compose yml in config dir`() {
        val inputs =
            listOf(
                "latest", // docker tag
                "my-key",
                "test/model",
                "n", // telegram
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        assertTrue(
            fileExists("$configDir/docker-compose.yml"),
            "docker-compose.yml should be written for hybrid mode",
        )
        val compose = readFileText("$configDir/docker-compose.yml")
        assertNotNull(compose)
        assertTrue(compose.contains("klaw-engine"), "Compose should contain engine service")
        assertTrue(compose.contains("klaw-gateway"), "Compose should contain gateway service")
        assertTrue(!compose.contains("cli"), "Compose should NOT contain cli service")
    }

    @Test
    fun `hybrid mode phase 7 writes deploy conf with mode=hybrid`() {
        val inputs =
            listOf(
                "unstable", // docker tag
                "my-key",
                "test/model",
                "n", // telegram
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        assertTrue(fileExists("$configDir/deploy.conf"), "deploy.conf should be written")
        val conf = readDeployConf(configDir)
        assertTrue(conf.mode == DeployMode.HYBRID, "Expected HYBRID mode, got: ${conf.mode}")
        assertTrue(conf.dockerTag == "unstable", "Expected tag 'unstable', got: ${conf.dockerTag}")
    }

    @Test
    fun `hybrid mode docker tag blank defaults to latest`() {
        val inputs =
            listOf(
                "", // blank docker tag = latest
                "my-key",
                "test/model",
                "n", // telegram
                "n", // console
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val conf = readDeployConf(configDir)
        assertTrue(conf.dockerTag == "latest", "Expected default tag 'latest', got: ${conf.dockerTag}")
    }

    @Test
    fun `hybrid mode compose file uses bind mount paths not named volumes`() {
        val inputs =
            listOf(
                "latest",
                "my-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val compose = readFileText("$configDir/docker-compose.yml")
        assertNotNull(compose)
        // Should contain the actual tmpDir paths (bind mounts), not "klaw-state" etc.
        assertTrue(compose.contains("$tmpDir/state:"), "Expected bind mount with state path:\n$compose")
        assertTrue(compose.contains("$tmpDir/data:"), "Expected bind mount with data path:\n$compose")
    }

    @Test
    fun `hybrid mode phase 11 calls docker compose up for both services`() {
        val commandsRun = mutableListOf<String>()
        val inputs =
            listOf(
                "latest",
                "my-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandRunner = { cmd ->
                    commandsRun += cmd
                    0
                },
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val composeUpCalls =
            commandsRun.filter {
                it.contains("docker compose") && it.contains("up -d") &&
                    it.contains("engine") && it.contains("gateway")
            }
        assertTrue(
            composeUpCalls.isNotEmpty(),
            "Expected docker compose up for both services in hybrid mode, got: $commandsRun",
        )
        // Should use config dir compose file, not /app/docker-compose.yml
        assertTrue(
            composeUpCalls.any { it.contains(configDir) },
            "Expected config dir path in compose command, got: $composeUpCalls",
        )
    }

    @Test
    fun `hybrid mode does not write systemd unit files`() {
        val inputs =
            listOf(
                "latest",
                "my-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/service", 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val unitFiles =
            listDirectory("$tmpDir/service").filter {
                it.endsWith(".service") || it.endsWith(".plist")
            }
        assertTrue(
            unitFiles.isEmpty(),
            "Expected no systemd/launchd unit files in hybrid mode, got: $unitFiles",
        )
    }

    @Test
    fun `native mode phase 2 presents mode selection and writes deploy conf with mode=native`() {
        val modeSelectorCalled = mutableListOf<Boolean>()
        val inputs =
            listOf(
                "my-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ ->
                    modeSelectorCalled += true
                    0 // native
                },
            )
        wizard.run()

        assertTrue(modeSelectorCalled.isNotEmpty(), "Mode selector should be called for non-docker env")
        assertTrue(fileExists("$configDir/deploy.conf"), "deploy.conf should be written")
        val conf = readDeployConf(configDir)
        assertTrue(conf.mode == DeployMode.NATIVE, "Expected NATIVE mode, got: ${conf.mode}")
    }

    @Test
    fun `docker env phase 2 skips mode selection and writes deploy conf with mode=docker`() {
        val modeSelectorCalled = mutableListOf<Boolean>()
        val inputs =
            listOf(
                "", // docker tag
                "my-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                isDockerEnv = true,
                modeSelector = { _, _ ->
                    modeSelectorCalled += true
                    0
                },
            )
        wizard.run()

        assertTrue(modeSelectorCalled.isEmpty(), "Mode selector should NOT be called for docker env")
        assertTrue(fileExists("$configDir/deploy.conf"), "deploy.conf should be written")
        val conf = readDeployConf(configDir)
        assertTrue(conf.mode == DeployMode.DOCKER, "Expected DOCKER mode, got: ${conf.mode}")
    }

    @Test
    fun `mode selection cancelled aborts wizard cleanly`() {
        val output = mutableListOf<String>()
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                output = output,
                modeSelector = { _, _ -> null }, // cancelled
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("Interrupted"), "Expected 'Interrupted' in output:\n$outputText")
        assertTrue(!fileExists("$configDir/engine.yaml"), "engine.yaml should not be created when mode cancelled")
    }

    @Test
    fun `hybrid mode printSummary shows compose file path`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "latest",
                "my-key",
                "test/model",
                "n",
                "n",
                "Klaw",
                "helpful",
                "assistant",
                "user",
                "",
            )

        val engineResponse = """{"soul":"x","identity":"Klaw","agents":"x","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(
            outputText.contains("docker-compose.yml"),
            "Expected compose file path in hybrid summary:\n$outputText",
        )
        assertTrue(
            !outputText.contains("docker run"),
            "Expected no 'docker run' in hybrid summary:\n$outputText",
        )
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
