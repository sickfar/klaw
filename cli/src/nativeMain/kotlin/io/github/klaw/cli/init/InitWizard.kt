package io.github.klaw.cli.init

import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.ui.RadioSelector
import io.github.klaw.cli.ui.Spinner
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.deleteRecursively
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

private const val TOTAL_PHASES = 8
private const val DEFAULT_MODEL = "zai/glm-5"
private const val MODELS_FETCH_TIMEOUT = 10

internal data class LlmProvider(
    val label: String,
    val baseUrl: String,
    val alias: String,
)

private val LLM_PROVIDERS =
    listOf(
        LlmProvider("z.ai GLM", "https://api.z.ai/api/paas/v4", "zai"),
    )

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@Suppress("LongParameterList")
internal class InitWizard(
    private val configDir: String = KlawPaths.config,
    private val workspaceDir: String = KlawPaths.workspace,
    private val dataDir: String = KlawPaths.data,
    private val stateDir: String = KlawPaths.state,
    private val cacheDir: String = KlawPaths.cache,
    private val conversationsDir: String = KlawPaths.conversations,
    private val memoryDir: String = KlawPaths.memory,
    private val skillsDir: String = KlawPaths.skills,
    private val modelsDir: String = KlawPaths.models,
    private val serviceOutputDir: String = ServiceInstaller.defaultOutputDir(),
    private val enginePort: Int = KlawPaths.enginePort,
    private val engineHost: String = KlawPaths.engineHost,
    private val requestFn: EngineRequest,
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
    private val commandOutput: (String) -> String? = { cmd ->
        val pipe = popen(cmd, "r")
        if (pipe == null) {
            null
        } else {
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            buf.usePinned { pinned ->
                var n: Int
                do {
                    n = fread(pinned.addressOf(0), 1.convert(), buf.size.convert(), pipe).toInt()
                    if (n > 0) sb.append(buf.decodeToString(0, n))
                } while (n > 0)
            }
            pclose(pipe)
            sb.toString()
        }
    },
    private val radioSelector: (items: List<String>, prompt: String) -> Int? = { items, prompt ->
        RadioSelector(items).select(prompt)
    },
    /** Separate from [radioSelector] so tests can mock mode selection independently from model selection. */
    private val modeSelector: (items: List<String>, prompt: String) -> Int? = { items, prompt ->
        RadioSelector(items).select(prompt)
    },
    /** When true, Docker Compose commands are used instead of systemd/launchd. */
    private val isDockerEnv: Boolean = isInsideDocker(),
    /** Factory that receives an `onTick` callback and start command used to drive a spinner during polling. */
    private val engineStarterFactory: (onTick: () -> Unit, startCommand: String?) -> EngineStarter =
        { onTick, startCommand ->
            EngineStarter(
                enginePort = enginePort,
                engineHost = engineHost,
                commandRunner = commandRunner,
                onTick = onTick,
                startCommand = startCommand,
            )
        },
    private val force: Boolean = false,
) {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run() {
        // ── Collection phases (1–6): gather all user input before touching disk ──

        phase(1, "Pre-check")
        if (fileExists("$configDir/engine.json")) {
            if (!force) {
                CliLogger.info { "already initialized, skipping" }
                printer("Already initialized. Use: klaw config set to modify settings.")
                return
            }
            if (!cleanupExistingInstallation()) return
        }

        CliLogger.info { "phase 2: deployment mode" }
        phase(2, "Deployment mode")
        val resolvedMode: DeployMode
        val dockerTag: String
        if (isDockerEnv) {
            resolvedMode = DeployMode.DOCKER
            printer("Docker image tag [latest]:")
            dockerTag = (readLineOrExit() ?: return).trim().ifBlank { "latest" }
        } else {
            val modeItems = listOf("Fully native (systemd/launchd)", "Docker services")
            val modeIdx = modeSelector(modeItems, "Deployment mode:")
            if (modeIdx == null) {
                printer("Interrupted.")
                return
            }
            resolvedMode = if (modeIdx == 0) DeployMode.NATIVE else DeployMode.HYBRID
            if (resolvedMode == DeployMode.HYBRID) {
                printer("Docker image tag [latest]:")
                dockerTag = (readLineOrExit() ?: return).trim().ifBlank { "latest" }
            } else {
                dockerTag = "latest"
            }
        }

        CliLogger.info { "phase 3: LLM provider setup" }
        CliLogger.debug { "resolved deploy mode=${resolvedMode.configName}" }
        phase(3, "LLM provider setup")
        val providerIdx = radioSelector(LLM_PROVIDERS.map { it.label }, "LLM provider:")
        val providerUrl: String
        val defaultAlias: String
        if (providerIdx != null && providerIdx in LLM_PROVIDERS.indices) {
            val selected = LLM_PROVIDERS[providerIdx]
            providerUrl = selected.baseUrl
            defaultAlias = selected.alias
        } else {
            printer("Interrupted.")
            return
        }
        printer("LLM API key:")
        val llmApiKey = (readLineOrExit() ?: return).trim()

        // Validate key (if URL and key are safe to inject into shell command)
        val validationResponse = validateApiKey(providerUrl, llmApiKey)

        // Fetch models or fall back to text input
        val modelId = selectModel(validationResponse, defaultAlias)
        if (modelId == null) {
            printer("Interrupted.")
            return
        }

        CliLogger.info { "phase 4: Telegram setup" }
        phase(4, "Telegram setup")
        printer("Configure Telegram bot? [Y/n]:")
        val telegramAnswer = readLineOrExit() ?: return
        val configureTelegram = telegramAnswer.trim().lowercase() != "n"
        val telegramToken: String
        val chatIds: List<String>
        if (configureTelegram) {
            printer("Telegram bot token:")
            telegramToken = (readLineOrExit() ?: return).trim()
            printer("Allowed chat IDs (comma-separated, empty = allow all):")
            val rawChatIds = readLineOrExit()?.trim().orEmpty()
            chatIds =
                if (rawChatIds.isBlank()) {
                    emptyList()
                } else {
                    rawChatIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
        } else {
            telegramToken = ""
            chatIds = emptyList()
        }

        CliLogger.info { "phase 5: WebSocket chat setup" }
        phase(5, "WebSocket chat setup")
        printer("Enable WebSocket chat for klaw chat and future web UI? [y/N]:")
        val enableConsole = readLineOrExit()?.trim()?.lowercase() == "y"
        var consolePort = 37474
        if (enableConsole) {
            printer("Gateway WebSocket port [37474]:")
            consolePort = readLineOrExit()?.trim()?.toIntOrNull() ?: 37474
            success("WebSocket chat enabled on port $consolePort")
        } else {
            printer("WebSocket chat disabled (can be enabled later in gateway.json)")
        }

        // ── Action phases (6–8): create directories, write configs, start services ──

        val composeFilePath =
            when (resolvedMode) {
                DeployMode.HYBRID -> "$configDir/docker-compose.json"
                DeployMode.DOCKER -> "/app/docker-compose.json"
                DeployMode.NATIVE -> ""
            }

        CliLogger.info { "phase 6: setup" }
        phase(6, "Setup")
        WorkspaceInitializer(
            configDir = configDir,
            dataDir = dataDir,
            stateDir = stateDir,
            cacheDir = cacheDir,
            workspaceDir = workspaceDir,
            conversationsDir = conversationsDir,
            memoryDir = memoryDir,
            skillsDir = skillsDir,
            modelsDir = modelsDir,
        ).initialize()
        // Broaden permissions so the container's klaw user (UID 10001) can write to host-owned dirs.
        // 0777 is acceptable: Raspberry Pi is single-user; multi-user hosts should use group-based ACLs.
        if (resolvedMode == DeployMode.HYBRID || resolvedMode == DeployMode.DOCKER) {
            chmodWorldRwx(stateDir)
            chmodWorldRwx(dataDir)
        }
        CliLogger.debug { "writing config files to $configDir" }
        writeFileText("$configDir/engine.json", ConfigTemplates.engineJson(providerUrl, modelId))
        writeFileText(
            "$configDir/gateway.json",
            ConfigTemplates.gatewayJson(
                telegramEnabled = configureTelegram,
                allowedChatIds = chatIds,
                enableConsole = enableConsole,
                consolePort = consolePort,
            ),
        )
        val apiKeyEnvVar = ConfigTemplates.apiKeyEnvVar(defaultAlias)
        EnvWriter.write(
            "$configDir/.env",
            mapOf(
                apiKeyEnvVar to llmApiKey,
                "KLAW_TELEGRAM_TOKEN" to telegramToken,
            ),
        )
        writeDeployConf(configDir, DeployConfig(resolvedMode, dockerTag))
        if (resolvedMode == DeployMode.HYBRID) {
            writeFileText(
                "$configDir/docker-compose.json",
                ConfigTemplates.dockerComposeJson(stateDir, dataDir, configDir, workspaceDir, dockerTag),
            )
        }
        success("Directories and configuration written")

        CliLogger.info { "phase 7: engine auto-start" }
        phase(7, "Engine auto-start")
        val spinner = Spinner("Starting Engine...")
        val startCommand =
            when (resolvedMode) {
                DeployMode.HYBRID, DeployMode.DOCKER -> {
                    require("'" !in composeFilePath) {
                        "composeFilePath must not contain single-quote characters"
                    }
                    "docker compose -f '$composeFilePath' up -d engine"
                }

                DeployMode.NATIVE -> {
                    null
                }
            }
        val engineStarter = engineStarterFactory({ spinner.tick() }, startCommand)
        val engineStarted = engineStarter.startAndWait()
        if (!engineStarted) {
            CliLogger.warn { "engine did not start within timeout" }
            printer("${AnsiColors.YELLOW}⚠ Engine did not start automatically.${AnsiColors.RESET}")
            val manualStartCmd =
                when (resolvedMode) {
                    DeployMode.DOCKER, DeployMode.HYBRID -> {
                        "docker compose up -d engine"
                    }

                    DeployMode.NATIVE -> {
                        if (Platform.osFamily == OsFamily.MACOSX) {
                            val home = platform.posix.getenv("HOME")?.toKString() ?: "~"
                            "launchctl load -w $home/Library/LaunchAgents/io.github.klaw.engine.plist"
                        } else {
                            "systemctl --user start klaw-engine"
                        }
                    }
                }
            printer("  Start it manually: $manualStartCmd")
            printer("  Identity generation will proceed and may fail if engine is not running.")
        } else {
            spinner.done("Engine started")
        }

        CliLogger.info { "phase 8: service/container startup" }
        when (resolvedMode) {
            DeployMode.DOCKER -> {
                phase(8, "Container startup")
                val dockerInstaller = DockerComposeInstaller(composeFilePath, printer, commandRunner)
                if (!dockerInstaller.installServices()) {
                    printer("${AnsiColors.YELLOW}⚠ docker compose up failed.${AnsiColors.RESET}")
                    printer("  Start manually: docker compose up -d engine gateway")
                } else {
                    success("Engine and Gateway containers started")
                }
            }

            DeployMode.HYBRID -> {
                phase(8, "Container startup")
                val dockerInstaller = DockerComposeInstaller(composeFilePath, printer, commandRunner)
                if (!dockerInstaller.installServices()) {
                    printer("${AnsiColors.YELLOW}⚠ docker compose up failed.${AnsiColors.RESET}")
                    printer("  Start manually: docker compose -f '$composeFilePath' up -d engine gateway")
                } else {
                    success("Engine and Gateway containers started")
                }
            }

            DeployMode.NATIVE -> {
                phase(8, "Service setup")
                val envFile = "$configDir/.env"
                val home = platform.posix.getenv("HOME")?.toKString() ?: "~"
                val engineBin = "$home/.local/bin/klaw-engine"
                val gatewayBin = "$home/.local/bin/klaw-gateway"
                val serviceInstaller =
                    ServiceInstaller(
                        outputDir = serviceOutputDir,
                        commandRunner = { cmd ->
                            commandRunner(cmd)
                            Unit
                        },
                    )
                serviceInstaller.install(engineBin, gatewayBin, envFile)
                success("Service files written")
            }
        }

        // ── Identity: collect Q&A and generate via engine ──

        printer("")
        printer("Agent name [Klaw]:")
        val agentName = readLineOrExit()?.trim().orEmpty().ifBlank { "Klaw" }
        printer("Primary role (e.g. personal assistant, coding helper):")
        val role = readLineOrExit()?.trim().orEmpty()
        printer("Tell me about the user who will work with this agent:")
        val userInfo = readLineOrExit()?.trim().orEmpty()

        val identitySpinner = Spinner("Generating identity files...")
        val identityJson =
            runBlocking {
                val spinnerJob =
                    launch(Dispatchers.Default) {
                        while (isActive) {
                            identitySpinner.tick()
                            delay(100)
                        }
                    }
                val result =
                    try {
                        withContext(Dispatchers.Default) {
                            requestFn(
                                "klaw_init_generate_identity",
                                mapOf(
                                    "name" to agentName,
                                    "role" to role,
                                    "user_info" to userInfo,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        CliLogger.error { "identity generation failed: ${e::class.simpleName}" }
                        """{"error":"${e::class.simpleName}"}"""
                    }
                spinnerJob.cancel()
                result
            }
        identitySpinner.done("Identity generated")
        val identity = extractJsonField(identityJson, "identity") ?: "# Identity\n\n$agentName"
        val user = extractJsonField(identityJson, "user") ?: "# User\n\n$userInfo"
        writeFileText("$workspaceDir/IDENTITY.md", identity)
        writeFileText("$workspaceDir/USER.md", user)

        printSummary(agentName, modelId, resolvedMode, dockerTag)
    }

    /**
     * Reads a line using the injected [readLine] callback.
     * Returns null on EOF (null from readLine) or ESC prefix — caller should return early.
     */
    private fun readLineOrExit(): String? {
        val line = readLine() ?: return null
        if (line.startsWith('\u001B')) {
            printer("Interrupted.")
            return null
        }
        return line
    }

    /**
     * Tears down an existing installation: stops services, removes containers (if Docker/Hybrid),
     * and deletes XDG directories (config, data, state, cache). Workspace is preserved.
     * Returns true to proceed with init, false if user declined.
     */
    private fun cleanupExistingInstallation(): Boolean {
        printer("This will stop all services, remove configuration, and reinitialize.")
        printer("Workspace ($workspaceDir) will be preserved.")
        printer("Continue? [y/N]:")
        val answer = readLine()?.trim()?.lowercase()
        if (answer != "y") {
            CliLogger.info { "force reinit declined by user" }
            printer("Aborted.")
            return false
        }

        CliLogger.info { "force reinit: cleaning up existing installation" }
        val deployConfig = readDeployConf(configDir)
        val composeFilePath =
            when (deployConfig.mode) {
                DeployMode.DOCKER -> "/app/docker-compose.json"
                DeployMode.HYBRID -> "$configDir/docker-compose.json"
                DeployMode.NATIVE -> ""
            }
        val serviceManager = ServiceManager(printer, commandRunner, deployConfig.mode, composeFilePath)
        if (!serviceManager.stopAll()) {
            CliLogger.warn { "failed to stop services during force reinit" }
        }
        if (deployConfig.mode != DeployMode.NATIVE) {
            if (!serviceManager.composeDown()) {
                CliLogger.warn { "failed to run docker compose down during force reinit" }
            }
        }

        for (dir in listOf(configDir, dataDir, stateDir, cacheDir)) {
            if (!deleteRecursively(dir)) {
                CliLogger.warn { "failed to delete directory during force reinit" }
            }
        }
        success("Existing installation removed")
        return true
    }

    /**
     * Validates the LLM API key via a curl request to {providerUrl}/models.
     * Returns the raw JSON response if the key appears valid ("data" key present), else null.
     * Skips validation if the URL or key contain single-quote characters (injection prevention).
     */
    private fun validateApiKey(
        providerUrl: String,
        llmApiKey: String,
    ): String? {
        if ("'" in providerUrl || "'" in llmApiKey) {
            CliLogger.warn { "unsafe characters in URL or key, skipping validation" }
            printer("${AnsiColors.YELLOW}⚠ URL or key contains unsafe characters, skipping validation.${AnsiColors.RESET}")
            return null
        }
        val url = "${providerUrl.trimEnd('/')}/models"
        val cmd = "curl -s -m $MODELS_FETCH_TIMEOUT -H 'Authorization: Bearer $llmApiKey' '$url'"
        val response = commandOutput(cmd) ?: return null
        return if (response.contains("\"data\"")) {
            CliLogger.debug { "API key validation passed" }
            printer("${AnsiColors.GREEN}✓ API key valid${AnsiColors.RESET}")
            response
        } else {
            CliLogger.warn { "API key validation failed" }
            printer("${AnsiColors.YELLOW}⚠ Could not validate API key (continuing anyway).${AnsiColors.RESET}")
            null
        }
    }

    /**
     * Attempts to select a model via radio selector (if models were fetched) or text input fallback.
     * Returns null only if the user interrupts (ESC or EOF) during text input.
     */
    private fun selectModel(
        validationResponse: String?,
        providerAlias: String,
    ): String? {
        val models = parseModels(validationResponse)
        return if (models.isNotEmpty()) {
            val selectedIdx = radioSelector(models, "Select model:")
            if (selectedIdx != null) {
                "$providerAlias/${models[selectedIdx]}"
            } else {
                // Radio cancelled — fall back to text prompt
                promptModelText()
            }
        } else {
            promptModelText()
        }
    }

    private fun promptModelText(): String? {
        printer("Model ID (provider/model) [$DEFAULT_MODEL]:")
        return (readLineOrExit() ?: return null).trim().ifBlank { DEFAULT_MODEL }
    }

    /**
     * Parses model IDs from an OpenAI-compatible /models response JSON.
     * Returns empty list if json is null or malformed.
     *
     * Example: `{"data":[{"id":"glm-5"},{"id":"glm-4-plus"}]}` → `["glm-5", "glm-4-plus"]`
     */
    private fun parseModels(json: String?): List<String> {
        if (json == null) return emptyList()
        val dataStart = json.indexOf("\"data\"")
        if (dataStart < 0) return emptyList()
        val arrayStart = json.indexOf('[', dataStart)
        if (arrayStart < 0) return emptyList()
        val arrayEnd = json.indexOf(']', arrayStart)
        if (arrayEnd < 0) return emptyList()
        val arrayContent = json.substring(arrayStart + 1, arrayEnd)

        val result = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val idKey = arrayContent.indexOf("\"id\"", searchFrom)
            if (idKey < 0) break
            var i = idKey + 4
            while (i < arrayContent.length && (arrayContent[i] == ':' || arrayContent[i] == ' ')) i++
            if (i >= arrayContent.length || arrayContent[i] != '"') break
            i++ // skip opening quote
            val sb = StringBuilder()
            while (i < arrayContent.length) {
                val c = arrayContent[i]
                if (c == '\\' && i + 1 < arrayContent.length && arrayContent[i + 1] == '"') {
                    sb.append('"')
                    i += 2
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    i++
                }
            }
            if (sb.isNotEmpty()) result += sb.toString()
            searchFrom = i + 1
        }
        return result
    }

    private fun phase(
        n: Int,
        title: String,
    ) {
        printer("${AnsiColors.BOLD}● Phase $n of $TOTAL_PHASES — $title${AnsiColors.RESET}")
    }

    private fun success(message: String) {
        printer("${AnsiColors.GREEN}✓ $message${AnsiColors.RESET}")
    }

    @Suppress("LongParameterList")
    private fun printSummary(
        agentName: String,
        modelId: String,
        resolvedMode: DeployMode,
        dockerTag: String,
    ) {
        printer("")
        printer("${AnsiColors.BOLD}─── Setup complete ───${AnsiColors.RESET}")
        printer("  ${AnsiColors.CYAN}Config${AnsiColors.RESET}: $configDir")
        printer("  ${AnsiColors.CYAN}Workspace${AnsiColors.RESET}: $workspaceDir")
        printer("  ${AnsiColors.CYAN}Agent${AnsiColors.RESET}: $agentName")
        printer("  ${AnsiColors.CYAN}Model${AnsiColors.RESET}: $modelId")
        when (resolvedMode) {
            DeployMode.DOCKER -> {
                printer("")
                printer("  To run klaw commands:")
                printer("    docker run -it --rm \\")
                printer("      -v /var/run/docker.sock:/var/run/docker.sock \\")
                printer("      -v klaw-config:/root/.config/klaw \\")
                printer("      -v klaw-state:/root/.local/state/klaw \\")
                printer("      -v klaw-data:/root/.local/share/klaw \\")
                printer("      -v klaw-workspace:/workspace \\")
                printer("      ghcr.io/sickfar/klaw-cli:$dockerTag [command]")
                printer("")
                printer("  Or install the klaw wrapper (adds 'klaw' to PATH):")
                printer("    bash <(curl -sSL https://raw.githubusercontent.com/sickfar/klaw/main/scripts/get-klaw.sh) install")
            }

            DeployMode.HYBRID -> {
                printer("")
                printer("  ${AnsiColors.CYAN}Compose${AnsiColors.RESET}: $configDir/docker-compose.json")
                printer("  Engine and Gateway run in Docker; CLI runs natively.")
            }

            DeployMode.NATIVE -> {
                // No additional summary for native mode
            }
        }
    }

    private fun extractJsonField(
        json: String,
        field: String,
    ): String? {
        val keyPattern = """"$field""""
        var i = json.indexOf(keyPattern)
        if (i < 0) return null
        i += keyPattern.length
        // skip whitespace and colon
        while (i < json.length && (json[i] == ':' || json[i] == ' ')) i++
        if (i >= json.length || json[i] != '"') return null
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }

                    '"' -> {
                        sb.append('"')
                        i += 2
                    }

                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }

                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }

                    't' -> {
                        sb.append('\t')
                        i += 2
                    }

                    else -> {
                        sb.append(json[i + 1])
                        i += 2
                    }
                }
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
