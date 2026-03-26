package io.github.klaw.cli.init

import io.github.klaw.cli.update.GitHubRelease
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.getpid
import platform.posix.stat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** Stub release client that returns a dummy release (passes prefetch check). */
    private val stubReleaseClient =
        object : GitHubReleaseClient {
            override suspend fun fetchLatest() =
                GitHubRelease(tagName = "v0.0.0", assets = emptyList(), prerelease = false, draft = false)

            override suspend fun fetchByTag(tag: String) =
                GitHubRelease(tagName = tag, assets = emptyList(), prerelease = false, draft = false)
        }

    @Suppress("LongParameterList")
    private fun buildWizard(
        inputs: List<String> = emptyList(),
        output: MutableList<String> = mutableListOf(),
        engineResponses: Map<String, String> = emptyMap(),
        commandRunner: (String) -> Int = { 0 },
        commandOutput: (String) -> String? = { cmd ->
            when {
                cmd.startsWith("command -v java") -> "/usr/bin/java"
                cmd.startsWith("java -version") -> """openjdk version "21.0.1" 2023-10-17"""
                else -> """{"content":[{"type":"text","text":"ok"}],"data":[]}"""
            }
        },
        radioSelector: (List<String>, String) -> Int? = { _, _ -> 0 },
        modeSelector: (List<String>, String) -> Int? = { _, _ -> 0 },
        isDockerEnv: Boolean = false,
        readLineOverride: (() -> String?)? = null,
        force: Boolean = false,
        releaseClient: GitHubReleaseClient = stubReleaseClient,
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
            requestFn = { cmd, _ -> engineResponses[cmd] ?: """{"error":"not mocked"}""" },
            readLine = readLineOverride ?: { inputQueue.removeFirstOrNull() },
            printer = { output += it },
            commandRunner = commandRunner,
            commandOutput = commandOutput,
            radioSelector = radioSelector,
            modeSelector = modeSelector,
            isDockerEnv = isDockerEnv,
            engineStarterFactory = { _, startCommand ->
                EngineStarter(
                    enginePort = 7470,
                    engineHost = "127.0.0.1",
                    portChecker = { _, _ -> true },
                    commandRunner = commandRunner,
                    pollIntervalMs = 10L,
                    timeoutMs = 50L,
                    startCommand = startCommand,
                )
            },
            force = force,
            releaseClient = releaseClient,
            jarDir = "$tmpDir/jars",
            binDir = "$tmpDir/bin",
        )
    }

    // --- Phase 1: pre-check ---

    @Test
    fun `phase 1 aborts when engine yaml already exists`() {
        // Pre-create engine.json to simulate already-initialized state
        platform.posix.mkdir(configDir, 0x1EDu)
        writeFile("$configDir/engine.json", "# existing config")

        val output = mutableListOf<String>()
        val wizard = buildWizard(output = output)
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("Already"), "Expected 'Already' in output: $outputText")
        // engine.json should not be overwritten
        val content = readFileText("$configDir/engine.json")
        assertTrue(content?.contains("existing config") == true, "engine.json should not be overwritten")
    }

    // --- Exit / EOF handling ---

    @Test
    fun `null readline at first prompt exits wizard early and writes no files`() {
        platform.posix.mkdir(configDir, 0x1EDu)
        val wizard = buildWizard(readLineOverride = { null })
        wizard.run()

        assertTrue(!fileExists("$configDir/engine.json"), "engine.json should not be created on EOF")
        assertTrue(!fileExists("$configDir/gateway.json"), "gateway.json should not be created on EOF")
    }

    @Test
    fun `esc input at api key prompt exits wizard early and writes no files`() {
        platform.posix.mkdir(configDir, 0x1EDu)
        val wizard = buildWizard(readLineOverride = { "\u001B" })
        wizard.run()

        assertTrue(!fileExists("$configDir/engine.json"), "engine.json should not be created on ESC")
        assertTrue(!fileExists("$configDir/gateway.json"), "gateway.json should not be created on ESC")
    }

    @Test
    fun `null readline at telegram prompt exits wizard early and writes no config files`() {
        // 1 input consumed by Phase 4 (key), then null at telegram prompt
        val inputQueue = ArrayDeque(listOf("key"))
        platform.posix.mkdir(configDir, 0x1EDu)
        val wizard =
            buildWizard(readLineOverride = {
                inputQueue.removeFirstOrNull() // returns null once key consumed
            })
        wizard.run()

        assertTrue(!fileExists("$configDir/engine.json"), "engine.json should not be created on EOF at telegram prompt")
        assertTrue(
            !fileExists("$configDir/gateway.json"),
            "gateway.json should not be created on EOF at telegram prompt",
        )
    }

    // --- Skip Telegram ---

    @Test
    fun `answering n at configure telegram skips token prompt and omits telegram from gateway yaml`() {
        val inputs =
            listOf(
                "my-key", // API key
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n", // Configure Telegram? → n = skip
                // NO telegram token or chat IDs prompts
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayJson = readFileText("$configDir/gateway.json")
        assertNotNull(gatewayJson)
        assertTrue(
            !gatewayJson.contains("telegram"),
            "Expected no telegram section when skipped:\n$gatewayJson",
        )
    }

    @Test
    fun `answering n at configure telegram writes empty KLAW_TELEGRAM_TOKEN in env`() {
        val inputs =
            listOf(
                "my-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n", // skip telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
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
                "y", // configure telegram
                "my-bot-token", // token
                "", // chat IDs
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayJson = readFileText("$configDir/gateway.json")
        assertNotNull(gatewayJson)
        assertTrue(gatewayJson.contains("telegram"), "Expected telegram section:\n$gatewayJson")
        assertTrue(gatewayJson.contains("KLAW_TELEGRAM_TOKEN"), "Expected token env var:\n$gatewayJson")
    }

    @Test
    fun `blank answer at configure telegram defaults to y and includes telegram section`() {
        val inputs =
            listOf(
                "my-key",
                "", // blank = default Y
                "my-bot-token",
                "",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayJson = readFileText("$configDir/gateway.json")
        assertNotNull(gatewayJson)
        assertTrue(gatewayJson.contains("telegram"), "Expected telegram section on blank answer:\n$gatewayJson")
    }

    // --- API key validation ---

    @Test
    fun `valid api key response shows check mark in output`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "valid-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { """{"content":[{"type":"text","text":"ok"}]}""" },
                radioSelector = { _, _ -> 0 },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val text = output.joinToString("\n")
        assertTrue(text.contains("✓"), "Expected ✓ in output on valid key:\n$text")
    }

    @Test
    fun `invalid api key re-prompts until valid key provided`() {
        val output = mutableListOf<String>()
        var curlCallIdx = 0
        val inputs =
            listOf(
                "bad-key",
                "good-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { _ ->
                    curlCallIdx++
                    if (curlCallIdx ==
                        1
                    ) {
                        """{"type":"error","error":{"type":"authentication_error","message":"invalid"}}"""
                    } else {
                        """{"content":[{"type":"text","text":"ok"}]}"""
                    }
                },
                radioSelector = { _, _ -> 0 },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val text = output.joinToString("\n")
        assertTrue(text.contains("⚠"), "Expected warning on first invalid key:\n$text")
        assertTrue(text.contains("✓"), "Expected success on second valid key:\n$text")
        assertTrue(curlCallIdx >= 2, "Expected at least 2 validation calls, got $curlCallIdx")
        assertTrue(fileExists("$configDir/engine.json"), "Wizard should complete after valid key")
    }

    @Test
    fun `null command output re-prompts until valid key provided`() {
        var curlCallIdx = 0
        val inputs =
            listOf(
                "key1",
                "key2",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { _ ->
                    curlCallIdx++
                    if (curlCallIdx == 1) null else """{"content":[{"type":"text","text":"ok"}]}"""
                },
                radioSelector = { _, _ -> 0 },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "Wizard should complete after valid key on retry")
    }

    @Test
    fun `api key with single quote skips validation and proceeds`() {
        val output = mutableListOf<String>()
        val commandOutputCalled = mutableListOf<String>()
        val inputs =
            listOf(
                "key'with'quotes",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { cmd ->
                    commandOutputCalled += cmd
                    """{"content":[{"type":"text","text":"ok"}]}"""
                },
                radioSelector = { _, _ -> 0 },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val text = output.joinToString("\n")
        assertTrue(text.contains("unsafe"), "Expected unsafe characters warning:\n$text")
        // Validation skipped — no curl calls for API key validation
        val validationCalls = commandOutputCalled.filter { it.contains("curl") }
        assertTrue(
            validationCalls.isEmpty(),
            "No curl validation should be called for unsafe key, got: $validationCalls",
        )
        assertTrue(fileExists("$configDir/engine.json"), "Wizard should complete with unsafe key")
    }

    @Test
    fun `empty api key re-prompts until valid key provided`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "",
                "  ",
                "good-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { """{"content":[{"type":"text","text":"ok"}]}""" },
                radioSelector = { _, _ -> 0 },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val text = output.joinToString("\n")
        assertTrue(text.contains("cannot be empty"), "Expected empty key warning:\n$text")
        assertTrue(fileExists("$configDir/engine.json"), "Wizard should complete after valid key")
    }

    @Test
    fun `EOF during api key re-prompt aborts wizard`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "",
            )
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(!fileExists("$configDir/engine.json"), "Wizard should abort on EOF during API key re-prompt")
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
                "n", // vision (glm-5 doesn't support image)
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { modelsJson },
                radioSelector = { items, prompt ->
                    if (!prompt.contains("LLM")) capturedModels += items
                    if (prompt.contains("LLM")) 1 else 0 // select z.ai (index 1) for LLM, first item for model
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(capturedModels.isNotEmpty(), "RadioSelector should have been called with model names")
        assertTrue(capturedModels.contains("glm-5"), "Expected 'glm-5' in model list: $capturedModels")

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(engineJson.contains("zai/glm-5"), "Expected 'zai/glm-5' in engine.json:\n$engineJson")
    }

    @Test
    fun `radio selector returns null falls back to text prompt for model`() {
        val modelsJson = """{"data":[{"id":"glm-5"},{"id":"glm-4-plus"}]}"""

        val inputs =
            listOf(
                "valid-key",
                // radioSelector returns null for model → text prompt
                "my/custom-model", // text fallback
                "n", // vision (unknown model doesn't support image)
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { modelsJson },
                radioSelector = { _, prompt -> if (prompt.contains("LLM")) 1 else null }, // select z.ai
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(engineJson.contains("my/custom-model"), "Expected manual model in engine.json:\n$engineJson")
    }

    @Test
    fun `command output null skips radio selector and uses text prompt for model`() {
        val modelRadioSelectorCalled = mutableListOf<Boolean>()

        val inputs =
            listOf(
                "key",
                "zai/glm-5", // text prompt (no models fetched)
                "n", // vision (glm-5 doesn't support image)
                "n",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                commandOutput = { """{"data":[]}""" },
                radioSelector = { _, prompt ->
                    if (!prompt.contains("LLM")) modelRadioSelectorCalled += true
                    if (prompt.contains("LLM")) 1 else 0 // select z.ai to test OpenAI model fetch path
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        assertTrue(
            modelRadioSelectorCalled.isEmpty(),
            "RadioSelector should not be called for model when no models fetched",
        )

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(engineJson.contains("zai/glm-5"), "Expected typed model in engine.json:\n$engineJson")
    }

    // --- Vision model selection ---

    @Test
    fun `main model with image support skips vision question`() {
        val output = mutableListOf<String>()
        // Use anthropic with claude-sonnet-4-5-20250514 which supports image
        val inputs =
            listOf(
                "valid-key",
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                radioSelector = { _, _ -> 0 }, // Anthropic + first model
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(
            !outputText.contains("vision", ignoreCase = true),
            "Expected no vision question for image-capable model:\n$outputText",
        )
    }

    @Test
    fun `main model without image support asks vision and user declines`() {
        val modelsJson = """{"data":[{"id":"glm-5"},{"id":"glm-4.6v"}]}"""
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "valid-key",
                "n", // vision? no
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { modelsJson },
                radioSelector = { _, prompt ->
                    if (prompt.contains("LLM")) 1 else 0 // zai + glm-5
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("vision", ignoreCase = true), "Expected vision question:\n$outputText")

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(!engineJson.contains("\"vision\""), "Expected no vision section when declined:\n$engineJson")
    }

    @Test
    fun `main model without image support and user enables vision`() {
        val modelsJson = """{"data":[{"id":"glm-5"},{"id":"glm-4.6v"},{"id":"glm-4.5v"}]}"""
        val output = mutableListOf<String>()
        var radioCallIdx = 0
        val inputs =
            listOf(
                "valid-key",
                "y", // vision? yes
                // vision model selected via radioSelector
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { modelsJson },
                radioSelector = { items, prompt ->
                    radioCallIdx++
                    when {
                        prompt.contains("LLM") -> 1

                        // zai
                        radioCallIdx == 2 -> 0

                        // glm-5 (main model)
                        radioCallIdx == 3 -> 0

                        // first vision model (glm-4.6v)
                        else -> 0
                    }
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        val config = parseEngineConfig(engineJson)
        assertTrue(config.vision.enabled, "Expected vision.enabled=true")
        assertTrue(
            config.vision.model.contains("glm-4"),
            "Expected vision model to contain glm-4, got: ${config.vision.model}",
        )
        assertTrue(config.vision.attachmentsDirectory.isNotBlank(), "Expected non-empty attachmentsDirectory")
        assertTrue(config.models.size >= 2, "Expected at least 2 models (main + vision)")

        val gatewayJson = readFileText("$configDir/gateway.json")
        assertNotNull(gatewayJson)
        val gwConfig = parseGatewayConfig(gatewayJson)
        assertTrue(gwConfig.attachments.directory.isNotBlank(), "Expected gateway attachments.directory set")
    }

    @Test
    fun `vision model selection with no vision-capable models falls back to text input`() {
        // API returns only non-vision models
        val modelsJson = """{"data":[{"id":"glm-5"},{"id":"unknown-model"}]}"""
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "valid-key",
                "y", // vision? yes
                "zai/glm-4.6v", // text fallback for vision model
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                commandOutput = { modelsJson },
                radioSelector = { _, prompt ->
                    if (prompt.contains("LLM")) 1 else 0 // zai + glm-5
                },
                engineResponses = mapOf("klaw_init_generate_identity" to """{"identity":"Klaw","user":"x"}"""),
            )
        platform.posix.mkdir(configDir, 0x1EDu)
        wizard.run()

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        val config = parseEngineConfig(engineJson)
        assertTrue(config.vision.enabled, "Expected vision.enabled=true")
        assertTrue(
            config.vision.model == "zai/glm-4.6v",
            "Expected vision model zai/glm-4.6v, got: ${config.vision.model}",
        )
    }

    // --- Existing tests (updated inputs to include telegram? prompt) ---

    @Test
    fun `phase 5 config generation writes engine yaml and gateway yaml`() {
        val inputs =
            listOf(
                "my-api-key", // API key
                // model selected via radioSelector (index 0 = first Anthropic model)
                "", // Configure Telegram? [Y/n]: blank = Y
                "bot-token-123", // telegram token
                "", // allowed chat IDs (empty = allow all)
                "n", // discord
                "n", // Phase 5: disable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "TestAgent", // agent name
                "personal assistant", // role
                "developer", // user info
            )

        val engineResponse =
            """{"identity":"TestAgent","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "engine.json should be created")
        assertTrue(fileExists("$configDir/gateway.json"), "gateway.json should be created")

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(engineJson.contains("ANTHROPIC_API_KEY"), "api key env var in engine.json")
        assertTrue(!engineJson.contains("api.anthropic.com"), "known provider should not have explicit endpoint")
        assertTrue(engineJson.contains("anthropic/claude-sonnet-4-5-20250514"), "model in engine.json")
    }

    @Test
    fun `phase 5 writes dot env with 0600 permissions`() {
        val inputs =
            listOf(
                "secret-api-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "", // Configure Telegram? blank = Y
                "telegram-bot-token",
                "",
                "n", // discord
                "n", // Phase 5: disable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "developer",
            )

        val engineResponse =
            """{"identity":"Klaw","user":"developer"}"""
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
        assertTrue(content.contains("ANTHROPIC_API_KEY=secret-api-key"), "API key in .env: $content")
        assertTrue(content.contains("KLAW_TELEGRAM_TOKEN=telegram-bot-token"), "Token in .env: $content")
    }

    @Test
    fun `phase 8 writes workspace identity files`() {
        val inputs =
            listOf(
                "my-key",
                "", // telegram? Y
                "my-token",
                "",
                "n", // discord
                "n", // Phase 5: disable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "MyBot",
                "coding helper",
                "engineer",
            )

        val engineResponse =
            """{"identity":"MyBot assistant","user":"An engineer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        // SOUL.md and AGENTS.md are predefined stubs written by WorkspaceInitializer (Phase 6),
        // NOT by identity generation (Phase 10). They should NOT contain LLM fallback text.
        assertTrue(fileExists("$workspaceDir/SOUL.md"), "SOUL.md should be created by WorkspaceInitializer")
        assertTrue(fileExists("$workspaceDir/AGENTS.md"), "AGENTS.md should be created by WorkspaceInitializer")
        val soulContent = readFileText("$workspaceDir/SOUL.md")
        assertNotNull(soulContent, "SOUL.md should be readable")
        assertTrue(soulContent.contains("# Soul"), "SOUL.md should contain stub header")
        assertTrue(
            !soulContent.contains("Be helpful and curious"),
            "SOUL.md should be a predefined stub, not LLM fallback: $soulContent",
        )
        val agentsContent = readFileText("$workspaceDir/AGENTS.md")
        assertNotNull(agentsContent, "AGENTS.md should be readable")
        assertTrue(agentsContent.contains("# Agents"), "AGENTS.md should contain stub header")
        assertTrue(
            !agentsContent.contains("Help the user effectively"),
            "AGENTS.md should be a predefined stub, not LLM fallback: $agentsContent",
        )

        // IDENTITY.md and USER.md are generated by LLM (Phase 10)
        assertTrue(fileExists("$workspaceDir/IDENTITY.md"), "IDENTITY.md should be created by identity generation")
        assertTrue(fileExists("$workspaceDir/USER.md"), "USER.md should be created by identity generation")
        val identityContent = readFileText("$workspaceDir/IDENTITY.md")
        assertNotNull(identityContent, "IDENTITY.md should be readable")
        assertTrue(identityContent.contains("MyBot"), "IDENTITY.md should contain LLM-generated content")
        val userContent = readFileText("$workspaceDir/USER.md")
        assertNotNull(userContent, "USER.md should be readable")
        assertTrue(userContent.contains("engineer"), "USER.md should contain LLM-generated content")
    }

    @Test
    fun `identity generation failure shows error and writes stubs`() {
        val inputs =
            listOf(
                "my-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "", // telegram? Y
                "my-token",
                "",
                "n", // discord
                "n", // Phase 5: disable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "MyBot",
                "coding helper",
                "Roman",
            )

        val engineResponse = """{"error":"AllProvidersFailedError"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        // Stubs should still be written
        assertTrue(fileExists("$workspaceDir/IDENTITY.md"), "IDENTITY.md stub should be created")
        assertTrue(fileExists("$workspaceDir/USER.md"), "USER.md stub should be created")
        val identityContent = readFileText("$workspaceDir/IDENTITY.md")
        assertNotNull(identityContent, "IDENTITY.md should be readable")
        assertTrue(identityContent.contains("MyBot"), "IDENTITY.md stub should contain agent name")
        val userContent = readFileText("$workspaceDir/USER.md")
        assertNotNull(userContent, "USER.md should be readable")
        assertTrue(userContent.contains("Roman"), "USER.md stub should contain user info")

        // Output should contain failure message, not success
        val joined = output.joinToString("\n")
        assertTrue(
            joined.contains("Stub files written") || joined.contains("failed"),
            "Output should mention failure or suggest editing identity files: $joined",
        )
    }

    @Test
    fun `phase 9 generates service unit files`() {
        val inputs =
            listOf(
                "my-key",
                "", // telegram? Y
                "my-token",
                "",
                "n", // discord
                "n", // Phase 5: disable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse =
            """{"identity":"Klaw","user":"user"}"""
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
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // Phase 6: disable localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse =
            """{"identity":"Klaw","user":"developer"}"""
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
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // Phase 6: disable localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse =
            """{"identity":"Klaw","user":"developer"}"""
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
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // Phase 6: disable localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse =
            """{"identity":"Klaw","user":"developer"}"""
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

        val phase9Calls =
            commandsRun.filter {
                it.contains("engine") && it.contains("gateway") &&
                    it.contains("docker compose")
            }
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
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // Phase 6: disable localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse =
            """{"identity":"Klaw","user":"developer"}"""
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
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // Phase 5: disable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse =
            """{"identity":"Klaw","user":"developer"}"""
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
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // Phase 5: disable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse =
            """{"identity":"Klaw","user":"developer"}"""
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
        val anyFileContainsBinDir =
            serviceFiles.any { file ->
                val content = readFileText("$tmpDir/service/$file")
                content?.contains("$tmpDir/bin/klaw-engine") == true ||
                    content?.contains("$tmpDir/bin/klaw-gateway") == true
            }
        assertTrue(
            anyFileContainsBinDir,
            "Expected binDir paths in service files, got files: $serviceFiles",
        )
    }

    @Test
    fun `phase 5 with localWs enabled writes localWs section with enabled=true and port to gateway json`() {
        val inputs =
            listOf(
                "my-api-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "", // telegram? Y
                "bot-token-123",
                "", // allowed chat IDs
                "n", // discord
                "y", // Phase 5: enable localWs
                "37474", // Phase 5: port
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "personal assistant",
                "developer",
            )

        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayJson = readFileText("$configDir/gateway.json")
        assertNotNull(gatewayJson)
        assertTrue(gatewayJson.contains("\"enabled\": true"), "Expected 'enabled: true' in gateway.json:\n$gatewayJson")
        // Default port (37474) is not encoded by minimal encoder; verify via parse
        val config = parseGatewayConfig(gatewayJson)
        assertTrue(config.channels.localWs?.port == 37474, "Expected default port 37474 in parsed config")
    }

    @Test
    fun `phase 5 with localWs disabled omits localWs section from gateway json`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token-123",
                "", // allowed chat IDs
                "n", // discord
                "n", // Phase 5: do not enable localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "personal assistant",
                "developer",
            )

        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayJson = readFileText("$configDir/gateway.json")
        assertNotNull(gatewayJson)
        assertTrue(!gatewayJson.contains("localWs"), "Expected no localWs section in gateway.json:\n$gatewayJson")
    }

    @Test
    fun `phase 5 with localWs enabled and custom port 9090 uses port 9090 in gateway json`() {
        val inputs =
            listOf(
                "my-api-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "", // telegram? Y
                "bot-token-123",
                "", // allowed chat IDs
                "n", // discord
                "y", // Phase 5: enable localWs
                "9090", // Phase 5: custom port
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "personal assistant",
                "developer",
            )

        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val gatewayJson = readFileText("$configDir/gateway.json")
        assertNotNull(gatewayJson)
        assertTrue(gatewayJson.contains("9090"), "Expected 'port: 9090' in gateway.json:\n$gatewayJson")
    }

    // --- Hybrid mode tests ---

    @Test
    fun `hybrid mode phase 2 selects docker services and prompts for docker tag`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "unstable", // docker tag
                "my-key",
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        assertTrue(
            fileExists("$configDir/docker-compose.json"),
            "docker-compose.json should be written for hybrid mode",
        )
        val compose = readFileText("$configDir/docker-compose.json")
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
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val compose = readFileText("$configDir/docker-compose.json")
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
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
        // Should use config dir compose file, not /app/docker-compose.json
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
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
                "n",
                "n",
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
                "n",
                "n",
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
        assertTrue(!fileExists("$configDir/engine.json"), "engine.json should not be created when mode cancelled")
    }

    @Test
    fun `hybrid mode printSummary shows compose file path`() {
        val output = mutableListOf<String>()
        val inputs =
            listOf(
                "latest",
                "my-key",
                "n",
                "n",
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
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
            outputText.contains("docker-compose.json"),
            "Expected compose file path in hybrid summary:\n$outputText",
        )
        assertTrue(
            !outputText.contains("docker run"),
            "Expected no 'docker run' in hybrid summary:\n$outputText",
        )
    }

    @Test
    fun `hybrid mode sets broad permissions on state and data dirs`() {
        val inputs =
            listOf(
                "latest",
                "my-key",
                "n",
                "n",
                "n", // skip web search
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"x"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val stateMode = getFileMode("$tmpDir/state")
        val dataMode = getFileMode("$tmpDir/data")
        assertTrue(stateMode and 0x1FFu == 0x1FFu, "Expected 0777 on state dir, got ${stateMode.toString(8)}")
        assertTrue(dataMode and 0x1FFu == 0x1FFu, "Expected 0777 on data dir, got ${dataMode.toString(8)}")
    }

    // --- Force reinit tests ---

    @Test
    fun `force flag with user declining confirmation exits and preserves files`() {
        platform.posix.mkdir(configDir, 0x1EDu)
        writeFile("$configDir/engine.json", "existing")
        writeFile("$configDir/gateway.json", "existing-gw")

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                output = output,
                readLineOverride = { "n" }, // decline confirmation
                force = true,
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("Aborted"), "Expected 'Aborted' in output: $outputText")
        assertTrue(fileExists("$configDir/engine.json"), "engine.json should be preserved")
        assertTrue(fileExists("$configDir/gateway.json"), "gateway.json should be preserved")
    }

    @Test
    fun `force flag with user confirming native mode deletes xdg dirs and runs normal init`() {
        // Pre-create dirs to simulate existing installation
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/data", 0x1EDu)
        platform.posix.mkdir("$tmpDir/state", 0x1EDu)
        platform.posix.mkdir("$tmpDir/cache", 0x1EDu)
        writeFile("$configDir/engine.json", "old-config")
        writeDeployConf(configDir, DeployConfig(DeployMode.NATIVE))

        val commands = mutableListOf<String>()
        val inputs =
            listOf(
                "y", // confirm reinit
                "my-key", // API key
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw", // agent name
                "assistant", // role
                "user", // user info
            )
        val engineResponse = """{"identity":"# Klaw","user":"# User"}"""
        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandRunner = { cmd ->
                    commands += cmd
                    0
                },
                force = true,
            )
        wizard.run()

        // XDG dirs should have been deleted and then recreated by normal init
        assertTrue(fileExists("$configDir/engine.json"), "engine.json should be recreated by init wizard")
        val newConfig = readFileText("$configDir/engine.json")
        assertTrue(
            newConfig?.contains("old-config") != true,
            "engine.json should contain new config, not old",
        )
    }

    @Test
    fun `force flag with hybrid mode issues docker compose down`() {
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/data", 0x1EDu)
        platform.posix.mkdir("$tmpDir/state", 0x1EDu)
        platform.posix.mkdir("$tmpDir/cache", 0x1EDu)
        writeFile("$configDir/engine.json", "old")
        writeDeployConf(configDir, DeployConfig(DeployMode.HYBRID))

        val commands = mutableListOf<String>()
        val inputs =
            listOf(
                "y", // confirm reinit
                "my-key",
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )
        val engineResponse = """{"identity":"# Klaw","user":"# User"}"""
        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandRunner = { cmd ->
                    commands += cmd
                    0
                },
                force = true,
            )
        wizard.run()

        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("down") },
            "Expected docker compose down for hybrid mode, got: $commands",
        )
    }

    @Test
    fun `force flag when not initialized skips cleanup and runs normal init`() {
        // Do NOT create engine.json — not initialized
        platform.posix.mkdir(configDir, 0x1EDu)

        val inputs =
            listOf(
                "my-key",
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )
        val engineResponse = """{"identity":"# Klaw","user":"# User"}"""
        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                force = true,
            )
        wizard.run()

        // Should not contain cleanup messages
        val outputText = output.joinToString("\n")
        assertTrue(!outputText.contains("Aborted"), "Should not have aborted")
        assertTrue(!outputText.contains("Existing installation removed"), "Should not have cleaned up")
        assertTrue(fileExists("$configDir/engine.json"), "engine.json should be created by normal init")
    }

    @Test
    fun `force reinit skips identity hatching when workspace is non-empty`() {
        // Pre-create existing installation
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/data", 0x1EDu)
        platform.posix.mkdir("$tmpDir/state", 0x1EDu)
        platform.posix.mkdir("$tmpDir/cache", 0x1EDu)
        platform.posix.mkdir(workspaceDir, 0x1EDu)
        writeFile("$configDir/engine.json", "old-config")
        writeDeployConf(configDir, DeployConfig(DeployMode.NATIVE))

        // Pre-create identity files in workspace (preserved by --force)
        val originalIdentity = "# My Custom Identity\n\nI am a unique agent."
        val originalUser = "# My User\n\nA power user."
        writeFile("$workspaceDir/IDENTITY.md", originalIdentity)
        writeFile("$workspaceDir/USER.md", originalUser)

        val engineRequestCmds = mutableListOf<String>()
        // No identity questions needed — 3 fewer inputs
        val inputQueue =
            ArrayDeque(
                listOf(
                    "y", // confirm reinit
                    "my-key", // API key
                    "n", // skip telegram
                    "n", // discord
                    "n", // skip localWs
                    "n", // skip web search
                    "", // host exec (default yes)
                    "", // pre-approval (default yes)
                ),
            )
        val output = mutableListOf<String>()
        val wizardWithTracking =
            InitWizard(
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
                requestFn = { cmd, _ ->
                    engineRequestCmds += cmd
                    """{"error":"should not be called"}"""
                },
                readLine = { inputQueue.removeFirstOrNull() },
                printer = { output += it },
                commandRunner = { 0 },
                commandOutput = { """{"data":[]}""" },
                radioSelector = { _, _ -> 0 },
                modeSelector = { _, _ -> 0 },
                isDockerEnv = false,
                engineStarterFactory = { _, _ ->
                    EngineStarter(
                        enginePort = 7470,
                        engineHost = "127.0.0.1",
                        portChecker = { _, _ -> true },
                        commandRunner = { 0 },
                        pollIntervalMs = 10L,
                        timeoutMs = 50L,
                    )
                },
                force = true,
            )
        wizardWithTracking.run()

        // Identity files should NOT be overwritten
        val identityContent = readFileText("$workspaceDir/IDENTITY.md")
        assertTrue(
            identityContent == originalIdentity,
            "IDENTITY.md should be preserved, got: $identityContent",
        )
        val userContent = readFileText("$workspaceDir/USER.md")
        assertTrue(
            userContent == originalUser,
            "USER.md should be preserved, got: $userContent",
        )

        // No engine request for identity generation
        assertTrue(
            engineRequestCmds.none { it == "klaw_init_generate_identity" },
            "Should not have called klaw_init_generate_identity, got: $engineRequestCmds",
        )

        // Output should contain preservation message
        val outputText = output.joinToString("\n")
        assertTrue(
            outputText.contains("preserved", ignoreCase = true),
            "Expected 'preserved' in output:\n$outputText",
        )
    }

    @Test
    fun `force reinit runs identity hatching when workspace is empty`() {
        // Pre-create existing installation with empty workspace dir
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/data", 0x1EDu)
        platform.posix.mkdir("$tmpDir/state", 0x1EDu)
        platform.posix.mkdir("$tmpDir/cache", 0x1EDu)
        platform.posix.mkdir(workspaceDir, 0x1EDu)
        writeFile("$configDir/engine.json", "old-config")
        writeDeployConf(configDir, DeployConfig(DeployMode.NATIVE))

        val inputs =
            listOf(
                "y", // confirm reinit
                "my-key", // API key
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw", // agent name (identity hatching runs)
                "assistant", // role
                "user", // user info
            )
        val engineResponse = """{"identity":"# Klaw","user":"# User"}"""
        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                force = true,
            )
        wizard.run()

        // Identity files should be created
        assertTrue(fileExists("$workspaceDir/IDENTITY.md"), "IDENTITY.md should be created")
        assertTrue(fileExists("$workspaceDir/USER.md"), "USER.md should be created")
        val identityContent = readFileText("$workspaceDir/IDENTITY.md")
        assertTrue(
            identityContent?.contains("Klaw") == true,
            "IDENTITY.md should contain generated content",
        )
    }

    @Test
    fun `force reinit runs identity hatching when workspace does not exist`() {
        // Pre-create existing installation but NO workspace dir
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/data", 0x1EDu)
        platform.posix.mkdir("$tmpDir/state", 0x1EDu)
        platform.posix.mkdir("$tmpDir/cache", 0x1EDu)
        writeFile("$configDir/engine.json", "old-config")
        writeDeployConf(configDir, DeployConfig(DeployMode.NATIVE))

        val inputs =
            listOf(
                "y", // confirm reinit
                "my-key", // API key
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw", // agent name (identity hatching runs)
                "assistant", // role
                "user", // user info
            )
        val engineResponse = """{"identity":"# Klaw","user":"# User"}"""
        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                force = true,
            )
        wizard.run()

        assertTrue(fileExists("$workspaceDir/IDENTITY.md"), "IDENTITY.md should be created")
        assertTrue(fileExists("$workspaceDir/USER.md"), "USER.md should be created")
    }

    @Test
    fun `force reinit skips identity hatching when workspace has non-identity files`() {
        // Pre-create existing installation with workspace containing only non-identity files
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir("$tmpDir/data", 0x1EDu)
        platform.posix.mkdir("$tmpDir/state", 0x1EDu)
        platform.posix.mkdir("$tmpDir/cache", 0x1EDu)
        platform.posix.mkdir(workspaceDir, 0x1EDu)
        writeFile("$configDir/engine.json", "old-config")
        writeDeployConf(configDir, DeployConfig(DeployMode.NATIVE))
        writeFile("$workspaceDir/SOUL.md", "custom soul file")

        val engineRequestCmds = mutableListOf<String>()
        val inputQueue =
            ArrayDeque(
                listOf(
                    "y", // confirm reinit
                    "my-key", // API key
                    "n", // skip telegram
                    "n", // discord
                    "n", // skip localWs
                    "n", // skip web search
                    "", // host exec (default yes)
                    "", // pre-approval (default yes)
                ),
            )
        val output = mutableListOf<String>()
        val wizard =
            InitWizard(
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
                requestFn = { cmd, _ ->
                    engineRequestCmds += cmd
                    """{"error":"should not be called"}"""
                },
                readLine = { inputQueue.removeFirstOrNull() },
                printer = { output += it },
                commandRunner = { 0 },
                commandOutput = { """{"data":[]}""" },
                radioSelector = { _, _ -> 0 },
                modeSelector = { _, _ -> 0 },
                isDockerEnv = false,
                engineStarterFactory = { _, _ ->
                    EngineStarter(
                        enginePort = 7470,
                        engineHost = "127.0.0.1",
                        portChecker = { _, _ -> true },
                        commandRunner = { 0 },
                        pollIntervalMs = 10L,
                        timeoutMs = 50L,
                    )
                },
                force = true,
            )
        wizard.run()

        assertTrue(
            engineRequestCmds.none { it == "klaw_init_generate_identity" },
            "Should skip identity hatching when workspace is non-empty, got: $engineRequestCmds",
        )
        val outputText = output.joinToString("\n")
        assertTrue(
            outputText.contains("preserved", ignoreCase = true),
            "Expected 'preserved' in output:\n$outputText",
        )
    }

    @Test
    fun `fresh init runs identity hatching even when workspace identity files exist`() {
        // Pre-create workspace with identity files but NO engine.json (fresh init scenario)
        platform.posix.mkdir(configDir, 0x1EDu)
        platform.posix.mkdir(workspaceDir, 0x1EDu)
        writeFile("$workspaceDir/IDENTITY.md", "old identity")
        writeFile("$workspaceDir/USER.md", "old user")

        val inputs =
            listOf(
                "my-key", // API key
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "NewBot", // agent name (hatching runs on fresh init)
                "helper", // role
                "dev", // user info
            )
        val engineResponse = """{"identity":"# NewBot","user":"# dev"}"""
        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        // Identity files should be overwritten with new content
        val identityContent = readFileText("$workspaceDir/IDENTITY.md")
        assertTrue(
            identityContent?.contains("NewBot") == true,
            "IDENTITY.md should be overwritten on fresh init, got: $identityContent",
        )
        val userContent = readFileText("$workspaceDir/USER.md")
        assertTrue(
            userContent?.contains("dev") == true,
            "USER.md should be overwritten on fresh init, got: $userContent",
        )
    }

    // --- Web search setup tests ---

    @Test
    fun `web search skip when user answers no`() {
        val inputs =
            listOf(
                "my-key",
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(
            !engineJson.contains("\"web\""),
            "Expected no web section when search skipped:\n$engineJson",
        )
        val envContent = readFileText("$configDir/.env")
        assertNotNull(envContent)
        assertTrue(
            !envContent.contains("BRAVE_SEARCH_API_KEY"),
            "Expected no BRAVE_SEARCH_API_KEY in .env when skipped:\n$envContent",
        )
        assertTrue(
            !envContent.contains("TAVILY_API_KEY"),
            "Expected no TAVILY_API_KEY in .env when skipped:\n$envContent",
        )
    }

    @Test
    fun `web search brave provider selected and key validated`() {
        val inputs =
            listOf(
                "my-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "y", // enable web search
                "brave-api-key-123", // search API key
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandOutput = { cmd ->
                    if (cmd.contains("brave")) "200" else """{"content":[{"type":"text","text":"ok"}]}"""
                },
                radioSelector = { _, prompt ->
                    when {
                        prompt.contains("LLM") -> 0

                        prompt.contains("search", ignoreCase = true) -> 0

                        // Brave
                        else -> 0 // model selection (first Anthropic model)
                    }
                },
            )
        wizard.run()

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(engineJson.contains("\"web\""), "Expected web section in engine.json:\n$engineJson")
        assertTrue(engineJson.contains("\"search\""), "Expected web.search section in engine.json:\n$engineJson")
        assertTrue(engineJson.contains("\"enabled\": true"), "Expected enabled: true in web.search:\n$engineJson")
        // "brave" is the default provider — encodeDefaults=false omits it; verify via apiKey ref
        assertTrue(
            engineJson.contains("BRAVE_SEARCH_API_KEY"),
            "Expected BRAVE_SEARCH_API_KEY env var ref in engine.json:\n$engineJson",
        )

        val envContent = readFileText("$configDir/.env")
        assertNotNull(envContent)
        assertTrue(
            envContent.contains("BRAVE_SEARCH_API_KEY=brave-api-key-123"),
            "Expected BRAVE_SEARCH_API_KEY in .env:\n$envContent",
        )
    }

    @Test
    fun `web search tavily provider selected and key validated`() {
        val inputs =
            listOf(
                "my-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "y", // enable web search
                "tavily-api-key-456", // search API key
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandOutput = { cmd ->
                    if (cmd.contains(
                            "tavily",
                        )
                    ) {
                        """{"results":[]}"""
                    } else {
                        """{"content":[{"type":"text","text":"ok"}]}"""
                    }
                },
                radioSelector = { _, prompt ->
                    when {
                        prompt.contains("LLM") -> 0

                        prompt.contains("search", ignoreCase = true) -> 1

                        // Tavily
                        else -> 0 // model selection (first Anthropic model)
                    }
                },
            )
        wizard.run()

        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(engineJson.contains("\"web\""), "Expected web section in engine.json:\n$engineJson")
        assertTrue(engineJson.contains("\"search\""), "Expected web.search section in engine.json:\n$engineJson")
        assertTrue(
            engineJson.contains("\"provider\": \"tavily\""),
            "Expected provider: tavily in web.search:\n$engineJson",
        )
        assertTrue(
            engineJson.contains("TAVILY_API_KEY"),
            "Expected TAVILY_API_KEY env var ref in engine.json:\n$engineJson",
        )

        val envContent = readFileText("$configDir/.env")
        assertNotNull(envContent)
        assertTrue(
            envContent.contains("TAVILY_API_KEY=tavily-api-key-456"),
            "Expected TAVILY_API_KEY in .env:\n$envContent",
        )
    }

    @Test
    fun `web search API key validation retry on failure`() {
        val output = mutableListOf<String>()
        var searchValidationCallIdx = 0
        val inputs =
            listOf(
                "my-key",
                // model selected via radioSelector (index 0 = first Anthropic model)
                "n", // skip telegram
                "n", // discord
                "n", // skip localWs
                "y", // enable web search
                "bad-search-key", // first attempt (will fail validation)
                "good-search-key", // second attempt (will pass validation)
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "user",
            )

        val engineResponse = """{"identity":"Klaw","user":"user"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandOutput = { cmd ->
                    if (cmd.contains("brave")) {
                        searchValidationCallIdx++
                        if (searchValidationCallIdx == 1) "403" else "200"
                    } else {
                        """{"content":[{"type":"text","text":"ok"}]}"""
                    }
                },
                radioSelector = { _, prompt ->
                    when {
                        prompt.contains("LLM") -> 0

                        prompt.contains("search", ignoreCase = true) -> 0

                        // Brave
                        else -> 0 // model selection (first Anthropic model)
                    }
                },
            )
        wizard.run()

        assertTrue(
            searchValidationCallIdx >= 2,
            "Expected at least 2 search validation calls, got $searchValidationCallIdx",
        )
        val text = output.joinToString("\n")
        assertTrue(
            text.contains("⚠") || text.contains("failed"),
            "Expected warning on first invalid search key:\n$text",
        )
        assertTrue(fileExists("$configDir/engine.json"), "Wizard should complete after valid search key")

        val envContent = readFileText("$configDir/.env")
        assertNotNull(envContent)
        assertTrue(
            envContent.contains("BRAVE_SEARCH_API_KEY=good-search-key"),
            "Expected good search key in .env:\n$envContent",
        )
    }

    // --- Host exec prompt ---

    @Test
    fun `native mode host exec prompt defaults to yes`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        val config = parseEngineConfig(engineJson)
        assertTrue(config.hostExecution.enabled, "Expected hostExecution.enabled=true for native mode default")
    }

    @Test
    fun `native mode host exec prompt n disables host execution`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "n", // host exec = no
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(!engineJson.contains("hostExecution"), "Expected no hostExecution section when disabled")
    }

    @Test
    fun `hybrid mode skips host exec prompt`() {
        val inputs =
            listOf(
                "latest", // docker tag
                "my-key",
                "n", // telegram
                "n", // discord
                "n", // localWs
                "n", // skip web search
                // NO host exec input — hybrid should not ask
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                modeSelector = { _, _ -> 1 },
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(!outputText.contains("host command execution"), "Hybrid should not ask about host exec")
        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
    }

    // --- Pre-approval ---

    @Test
    fun `native mode pre-approval defaults to yes and selects model via radio`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                // model selected via radioSelector (index 0)
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        val config = parseEngineConfig(engineJson)
        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("pre-approval"), "Expected pre-approval prompt in output")
        assertTrue(config.hostExecution.enabled, "Expected hostExecution.enabled=true")
        assertTrue(config.hostExecution.preValidation.enabled, "Expected preValidation.enabled=true")
        assertEquals(
            "anthropic/${ANTHROPIC_MODELS[0]}",
            config.hostExecution.preValidation.model,
            "Expected first Anthropic model as pre-approval model",
        )
    }

    @Test
    fun `native mode pre-approval no leaves model empty`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "n", // pre-approval = no
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        val config = parseEngineConfig(engineJson)
        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("pre-approval"), "Expected pre-approval prompt in output")
        assertTrue(config.hostExecution.enabled, "Expected hostExecution.enabled=true")
        // preValidation.enabled defaults to true; with empty model the engine skips LLM scoring (by design)
        assertEquals("", config.hostExecution.preValidation.model, "Expected empty model when pre-approval declined")
    }

    @Test
    fun `native mode host exec no skips pre-approval question`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "n", // host exec = no
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(!outputText.contains("pre-approval"), "Should not ask about pre-approval when host exec disabled")
        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        assertTrue(!engineJson.contains("hostExecution"), "Expected no hostExecution when disabled")
    }

    @Test
    fun `native mode pre-approval selects non-first model`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                // model selected via radioSelector with custom index
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                radioSelector = { _, prompt ->
                    if (prompt.contains("pre-approval")) 2 else 0
                },
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        val config = parseEngineConfig(engineJson)
        assertEquals(
            "anthropic/${ANTHROPIC_MODELS[2]}",
            config.hostExecution.preValidation.model,
            "Expected third Anthropic model as pre-approval model",
        )
    }

    @Test
    fun `native mode pre-approval falls back to text input when radio cancelled`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                // radio cancelled (returns null) → falls back to text input
                "anthropic/claude-3-5-haiku-20241022", // typed model
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                radioSelector = { _, prompt ->
                    if (prompt.contains("pre-approval")) null else 0
                },
            )
        wizard.run()

        assertTrue(fileExists("$configDir/engine.json"), "engine.json should exist")
        val engineJson = readFileText("$configDir/engine.json")
        assertNotNull(engineJson)
        val config = parseEngineConfig(engineJson)
        assertEquals(
            "anthropic/claude-3-5-haiku-20241022",
            config.hostExecution.preValidation.model,
            "Expected typed model as pre-approval model",
        )
    }

    // --- Native mode Docker warning ---

    @Test
    fun `native mode warns when docker is not available`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandRunner = { cmd -> if (cmd.contains("docker info")) 1 else 0 },
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("Docker not found"), "Expected Docker warning in output:\n$outputText")
    }

    // --- Java version warning in native mode ---

    @Test
    fun `native mode warns when java is not found`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val output = mutableListOf<String>()
        val wizard =
            buildWizard(
                inputs = inputs,
                output = output,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
                commandOutput = { cmd ->
                    if (cmd.contains("java -version")) {
                        null
                    } else {
                        """{"content":[{"type":"text","text":"ok"}],"data":[]}"""
                    }
                },
            )
        wizard.run()

        val outputText = output.joinToString("\n")
        assertTrue(outputText.contains("Java not found"), "Expected Java warning in output:\n$outputText")
    }

    // --- Wrapper script creation ---

    @Test
    fun `native mode creates wrapper scripts in bin dir`() {
        val inputs =
            listOf(
                "my-api-key",
                "", // telegram? Y
                "bot-token",
                "",
                "n", // discord
                "n", // localWs
                "n", // skip web search
                "", // host exec (default yes)
                "", // pre-approval (default yes)
                "Klaw",
                "assistant",
                "developer",
            )
        val engineResponse = """{"identity":"Klaw","user":"developer"}"""
        platform.posix.mkdir(configDir, 0x1EDu)

        val wizard =
            buildWizard(
                inputs = inputs,
                engineResponses = mapOf("klaw_init_generate_identity" to engineResponse),
            )
        wizard.run()

        assertTrue(fileExists("$tmpDir/bin/klaw-engine"), "Expected engine wrapper script")
        assertTrue(fileExists("$tmpDir/bin/klaw-gateway"), "Expected gateway wrapper script")
        val engineContent = readFileText("$tmpDir/bin/klaw-engine")
        assertNotNull(engineContent)
        assertTrue(engineContent.contains("java"), "Expected java in engine wrapper")
        assertTrue(engineContent.contains("klaw-engine.jar"), "Expected JAR reference in engine wrapper")
        assertTrue(
            engineContent.contains("$tmpDir/jars/klaw-engine.jar"),
            "Wrapper should reference jarDir path, got: $engineContent",
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getFileMode(path: String): UInt =
        memScoped {
            val statBuf = alloc<stat>()
            if (stat(path, statBuf.ptr) == 0) {
                statBuf.st_mode.toUInt()
            } else {
                0u
            }
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
