package io.github.klaw.cli.init

import io.github.klaw.cli.BuildConfig
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.InstallPaths
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.ui.RadioSelector
import io.github.klaw.cli.ui.Spinner
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.update.GitHubReleaseClientImpl
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.deleteRecursively
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.common.registry.ProviderRegistry
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

private const val TOTAL_PHASES = 10
private const val DEFAULT_MODEL = "anthropic/claude-sonnet-4-5-20250514"
private const val COMMAND_OUTPUT_BUF_SIZE = 4096
private const val LOCAL_WS_DEFAULT_PORT = 37474
private const val SPINNER_TICK_MS = 100L

// Wizard phase numbers (phase 2 = deploy mode, uses literal 2 which is in detekt ignoredNumbers)
private const val PHASE_LLM = 3
private const val PHASE_TELEGRAM = 4
private const val PHASE_DISCORD = 5
private const val PHASE_WEBSOCKET = 6
private const val PHASE_WEB_SEARCH = 7
private const val PHASE_SETUP = 8
private const val PHASE_ENGINE_START = 9
private const val PHASE_SERVICE_INSTALL = 10

internal data class LlmProvider(
    val label: String,
    val alias: String,
)

internal val ANTHROPIC_MODELS =
    listOf(
        "claude-sonnet-4-5-20250514",
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-3-5-haiku-20241022",
    )

internal val LLM_PROVIDERS =
    listOf(
        LlmProvider("Anthropic Claude", "anthropic"),
        LlmProvider("z.ai GLM", "zai"),
    )

internal data class WebSearchProvider(
    val label: String,
    val name: String,
    val envVar: String,
)

internal val WEB_SEARCH_PROVIDERS =
    listOf(
        WebSearchProvider("Brave Search (brave.com)", "brave", "BRAVE_SEARCH_API_KEY"),
        WebSearchProvider("Tavily (tavily.com)", "tavily", "TAVILY_API_KEY"),
    )

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@Suppress("LongParameterList", "TooManyFunctions")
internal class InitWizard(
    private val configDir: String = KlawPaths.config,
    private var workspaceDir: String = KlawPaths.workspace,
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
            val buf = ByteArray(COMMAND_OUTPUT_BUF_SIZE)
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
    private val workspacePath: String? = null,
    private val releaseClient: GitHubReleaseClient = GitHubReleaseClientImpl(),
    private val jarDir: String = InstallPaths.installDir,
    private val binDir: String = InstallPaths.installDir,
) {
    private val apiKeyValidator by lazy {
        ApiKeyValidator(commandOutput = commandOutput, printer = printer)
    }

    private val nativeInstaller by lazy {
        NativeInstaller(
            commandRunner = commandRunner,
            commandOutput = commandOutput,
            printer = printer,
            successPrinter = ::success,
            releaseClient = releaseClient,
            jarDir = jarDir,
            binDir = binDir,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun run() {
        // ── Collection phases (1–6): gather all user input before touching disk ──

        // Snapshot workspace state before any disk operations — used later to decide identity hatching
        val workspaceExistedWithContent = isDirectory(workspaceDir) && listDirectory(workspaceDir).isNotEmpty()

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

        // ── Pre-flight checks right after mode selection ──
        if (resolvedMode == DeployMode.NATIVE) {
            nativeInstaller.printJavaCheck()
            if (!nativeInstaller.prefetchRelease()) {
                printer(
                    "${AnsiColors.RED}✗ Cannot fetch release v${BuildConfig.VERSION} from GitHub.${AnsiColors.RESET}",
                )
                printer("  Check your network connection and try again.")
                printer("  Or download JARs manually and run 'klaw init' again.")
                return
            }
        }
        if (resolvedMode == DeployMode.HYBRID || resolvedMode == DeployMode.DOCKER) {
            if (!nativeInstaller.isDockerAvailable()) {
                printer(
                    "${AnsiColors.RED}✗ Docker is not available.${AnsiColors.RESET}",
                )
                printer("  Install Docker and try again.")
                return
            }
        }

        // ── Workspace path: ask or use --workspace flag ──
        if (workspacePath != null) {
            workspaceDir = workspacePath
            CliLogger.info { "workspace path from flag: $workspaceDir" }
        } else {
            printer("Workspace directory [$workspaceDir]:")
            val input = readLineOrExit() ?: return
            val trimmed = input.trim()
            if (trimmed.isNotBlank()) {
                workspaceDir = trimmed
            }
        }
        if (!isDirectory(workspaceDir)) {
            commandRunner("mkdir -p '$workspaceDir'")
        }

        CliLogger.info { "phase 3: LLM provider setup" }
        CliLogger.debug { "resolved deploy mode=${resolvedMode.configName}" }
        phase(PHASE_LLM, "LLM provider setup")
        val providerIdx = radioSelector(LLM_PROVIDERS.map { it.label }, "LLM provider:")
        val defaultAlias: String
        if (providerIdx != null && providerIdx in LLM_PROVIDERS.indices) {
            defaultAlias = LLM_PROVIDERS[providerIdx].alias
        } else {
            printer("Interrupted.")
            return
        }
        val registryDefaults = ProviderRegistry.get(defaultAlias)
        val providerUrl = registryDefaults?.endpoint ?: error("Unknown provider: $defaultAlias")
        val providerType = registryDefaults.type
        var llmApiKey: String
        var validationResponse: String?
        while (true) {
            printer("LLM API key:")
            val rawKey = readLineOrExit() ?: return
            llmApiKey = rawKey.trim()
            if (llmApiKey.isEmpty()) {
                printer("${AnsiColors.YELLOW}⚠ API key cannot be empty.${AnsiColors.RESET}")
                continue
            }
            validationResponse = apiKeyValidator.validateApiKey(providerUrl, llmApiKey, providerType)
            if (validationResponse != null) {
                break
            }
            printer("Please enter a valid API key or press Ctrl+C to abort.")
        }

        // Fetch models or fall back to text input
        val modelId = selectModel(validationResponse, defaultAlias, providerType)
        if (modelId == null) {
            printer("Interrupted.")
            return
        }

        // Vision model configuration — only ask if main model doesn't support images
        val visionModelId =
            if (ModelRegistry.supportsImage(modelId)) {
                null
            } else {
                askVisionModelHelper(
                    validationResponse = validationResponse,
                    providerAlias = defaultAlias,
                    providerType = providerType,
                    readLineOrExit = ::readLineOrExit,
                    printer = printer,
                    radioSelector = radioSelector,
                )
            }
        if (visionModelId != null) {
            success("Vision: $visionModelId")
        }

        CliLogger.info { "phase 4: Telegram setup" }
        phase(PHASE_TELEGRAM, "Telegram setup")
        printer("Configure Telegram bot? [Y/n]:")
        val telegramAnswer = readLineOrExit() ?: return
        val configureTelegram = telegramAnswer.trim().lowercase() != "n"
        val telegramToken: String
        val chatIds: List<String>
        if (configureTelegram) {
            printer("Telegram bot token:")
            telegramToken = (readLineOrExit() ?: return).trim()
            printer("Allowed chat IDs (comma-separated, empty for pairing later):")
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

        CliLogger.info { "phase 5: Discord setup" }
        phase(PHASE_DISCORD, "Discord setup")
        printer("Configure Discord bot? [y/N]:")
        val discordAnswer = readLineOrExit() ?: return
        val configureDiscord = discordAnswer.trim().lowercase() == "y"
        val discordToken: String
        val discordGuildIds: List<String>
        if (configureDiscord) {
            printer("Discord bot token:")
            discordToken = (readLineOrExit() ?: return).trim()
            printer("Allowed guild (server) IDs (comma-separated, empty for pairing later):")
            val rawGuildIds = readLineOrExit()?.trim().orEmpty()
            discordGuildIds =
                if (rawGuildIds.isBlank()) {
                    emptyList()
                } else {
                    rawGuildIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
        } else {
            discordToken = ""
            discordGuildIds = emptyList()
        }

        CliLogger.info { "phase 6: WebSocket chat setup" }
        phase(PHASE_WEBSOCKET, "WebSocket chat setup")
        printer("Enable WebSocket chat for klaw chat and future web UI? [y/N]:")
        val enableLocalWs = readLineOrExit()?.trim()?.lowercase() == "y"
        var localWsPort = LOCAL_WS_DEFAULT_PORT
        if (enableLocalWs) {
            printer("Gateway WebSocket port [$LOCAL_WS_DEFAULT_PORT]:")
            localWsPort = readLineOrExit()?.trim()?.toIntOrNull() ?: LOCAL_WS_DEFAULT_PORT
            success("WebSocket chat enabled on port $localWsPort")
        } else {
            printer("WebSocket chat disabled (can be enabled later in gateway.json)")
        }

        CliLogger.info { "phase 7: Web search setup" }
        phase(PHASE_WEB_SEARCH, "Web search setup")
        printer("Enable web search? (y/n) [n]:")
        val enableWebSearch = readLineOrExit()?.trim()?.lowercase() == "y"
        var webSearchProvider: WebSearchProvider? = null
        var webSearchApiKey = ""
        if (enableWebSearch) {
            val providerLabels = WEB_SEARCH_PROVIDERS.map { it.label }
            val searchProviderIdx = radioSelector(providerLabels, "Search provider:")
            if (searchProviderIdx != null && searchProviderIdx in WEB_SEARCH_PROVIDERS.indices) {
                webSearchProvider = WEB_SEARCH_PROVIDERS[searchProviderIdx]
            } else {
                printer("Interrupted.")
                return
            }
            while (true) {
                printer("${webSearchProvider.label} API key:")
                val rawKey = readLineOrExit() ?: return
                webSearchApiKey = rawKey.trim()
                if (webSearchApiKey.isEmpty()) {
                    printer("${AnsiColors.YELLOW}⚠ API key cannot be empty.${AnsiColors.RESET}")
                    continue
                }
                if (apiKeyValidator.validateSearchApiKey(webSearchProvider.name, webSearchApiKey)) {
                    break
                }
                printer("Please enter a valid API key or press Ctrl+C to abort.")
            }
            success("Web search enabled with ${webSearchProvider.label}")
        } else {
            printer("Web search disabled (can be enabled later in engine.json)")
        }

        // ── Native: Docker check + host exec prompt ──
        var dockerAvailable = true
        var enableHostExec = false
        var preValidationModel: String? = null
        if (resolvedMode == DeployMode.NATIVE) {
            dockerAvailable = nativeInstaller.isDockerAvailable()
            if (!dockerAvailable) {
                printer(
                    "${AnsiColors.YELLOW}⚠ Docker not found. Sandbox code execution will be unavailable. " +
                        "Install Docker to enable it.${AnsiColors.RESET}",
                )
            } else {
                success("Docker detected")
            }
            printer("In native mode, the agent can execute commands on the host system.")
            if (!dockerAvailable) {
                printer("Docker sandbox is not available, so host execution is the only way to run code.")
            }
            printer("Enable host command execution? [Y/n]:")
            val hostExecAnswer = readLineOrExit() ?: return
            enableHostExec = hostExecAnswer.trim().lowercase() != "n"
            if (enableHostExec) {
                success("Host execution enabled")
                preValidationModel =
                    askPreApproval(
                        validationResponse,
                        defaultAlias,
                        providerType,
                        ::readLineOrExit,
                        printer,
                        radioSelector,
                        ::success,
                    )
            } else {
                printer("Host execution disabled (can be enabled later in engine.json)")
            }
        }

        // ── Action phases (8–10): create directories, write configs, start services ──

        val composeFilePath =
            when (resolvedMode) {
                DeployMode.HYBRID -> "$configDir/docker-compose.json"
                DeployMode.DOCKER -> "/app/docker-compose.json"
                DeployMode.NATIVE -> ""
            }

        CliLogger.info { "phase 8: setup" }
        phase(PHASE_SETUP, "Setup")
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
        if (visionModelId != null) {
            val attachDir = "$dataDir/attachments"
            if (!fileExists(attachDir)) {
                mkdirMode755(attachDir)
            }
        }
        // ── Native: download JARs, create wrapper scripts, write service files ──
        val canUseNativeService =
            resolvedMode == DeployMode.NATIVE &&
                (Platform.osFamily == OsFamily.MACOSX || nativeInstaller.isSystemdAvailable())
        if (resolvedMode == DeployMode.NATIVE) {
            nativeInstaller.ensureJars()
            nativeInstaller.createWrapperScripts()
            val engineBin = "$binDir/klaw-engine"
            val gatewayBin = "$binDir/klaw-gateway"
            val envFile = "$configDir/.env"
            if (canUseNativeService) {
                val serviceInstaller =
                    ServiceInstaller(
                        outputDir = serviceOutputDir,
                        commandRunner = { cmd ->
                            val rc = commandRunner(cmd)
                            if (rc != 0) {
                                CliLogger.warn { "service command failed (rc=$rc): $cmd" }
                            }
                        },
                    )
                serviceInstaller.installWithoutStart(engineBin, gatewayBin, envFile)
                success("Service files written")
            }
        }
        // Broaden permissions so the container's klaw user (UID 10001) can write to host-owned dirs.
        // 0777 is acceptable: Raspberry Pi is single-user; multi-user hosts should use group-based ACLs.
        if (resolvedMode == DeployMode.HYBRID || resolvedMode == DeployMode.DOCKER) {
            chmodWorldRwx(stateDir)
            chmodWorldRwx(dataDir)
        }
        CliLogger.debug { "writing config files to $configDir" }
        // Derive heartbeat channel from first configured channel.
        // injectInto (chatId) is set later via /use-for-heartbeat command.
        val heartbeatChannel =
            when {
                configureTelegram -> "telegram"
                configureDiscord -> "discord"
                enableLocalWs -> "local_ws"
                else -> null
            }
        val attachmentsDirectory = if (visionModelId != null) "$dataDir/attachments" else ""
        writeFileText(
            "$configDir/engine.json",
            ConfigTemplates.engineJson(
                modelId = modelId,
                heartbeatChannel = heartbeatChannel,
                webSearchEnabled = enableWebSearch,
                webSearchProvider = webSearchProvider?.name,
                webSearchApiKeyEnvVar = webSearchProvider?.envVar,
                hostExecutionEnabled = enableHostExec,
                preValidationModel = preValidationModel,
                visionModelId = visionModelId,
                attachmentsDirectory = attachmentsDirectory,
                workspace = "\${KLAW_WORKSPACE}",
            ),
        )
        writeFileText(
            "$configDir/gateway.json",
            ConfigTemplates.gatewayJson(
                telegramEnabled = configureTelegram,
                allowedChats =
                    chatIds.map {
                        io.github.klaw.common.config
                            .AllowedChat(chatId = it)
                    },
                discordEnabled = configureDiscord,
                discordAllowedGuilds = discordGuildIds,
                enableLocalWs = enableLocalWs,
                localWsPort = localWsPort,
                attachmentsDirectory = attachmentsDirectory,
            ),
        )
        val apiKeyEnvVar = ConfigTemplates.apiKeyEnvVar(defaultAlias)
        val envEntries =
            mutableMapOf(
                apiKeyEnvVar to llmApiKey,
                "KLAW_TELEGRAM_TOKEN" to telegramToken,
                "KLAW_WORKSPACE" to workspaceDir,
            )
        if (configureDiscord && discordToken.isNotBlank()) {
            envEntries["KLAW_DISCORD_TOKEN"] = discordToken
        }
        if (webSearchProvider != null) {
            envEntries[webSearchProvider.envVar] = webSearchApiKey
        }
        EnvWriter.write("$configDir/.env", envEntries)
        writeDeployConf(configDir, DeployConfig(resolvedMode, dockerTag))
        if (resolvedMode == DeployMode.HYBRID) {
            writeFileText(
                "$configDir/docker-compose.json",
                ConfigTemplates.dockerComposeJson(
                    stateDir,
                    dataDir,
                    configDir,
                    workspaceDir,
                    dockerTag,
                    enableLocalWs,
                    localWsPort,
                ),
            )
        }
        success("Directories and configuration written")

        CliLogger.info { "phase 9: engine auto-start" }
        phase(PHASE_ENGINE_START, "Engine auto-start")
        startEngine(resolvedMode, canUseNativeService, composeFilePath)

        CliLogger.info { "phase 10: service/container startup" }
        when (resolvedMode) {
            DeployMode.DOCKER -> {
                phase(PHASE_SERVICE_INSTALL, "Container startup")
                val dockerInstaller = DockerComposeInstaller(composeFilePath, printer, commandRunner)
                if (!dockerInstaller.installServices()) {
                    printer("${AnsiColors.YELLOW}⚠ docker compose up failed.${AnsiColors.RESET}")
                    printer("  Start manually: docker compose up -d engine gateway")
                } else {
                    success("Engine and Gateway containers started")
                }
            }

            DeployMode.HYBRID -> {
                phase(PHASE_SERVICE_INSTALL, "Container startup")
                val dockerInstaller = DockerComposeInstaller(composeFilePath, printer, commandRunner)
                if (!dockerInstaller.installServices()) {
                    printer("${AnsiColors.YELLOW}⚠ docker compose up failed.${AnsiColors.RESET}")
                    printer("  Start manually: docker compose -f '$composeFilePath' up -d engine gateway")
                } else {
                    success("Engine and Gateway containers started")
                }
            }

            DeployMode.NATIVE -> {
                phase(PHASE_SERVICE_INSTALL, "Gateway startup")
                val gatewayBin = "$binDir/klaw-gateway"
                if (canUseNativeService) {
                    val startGatewayCmd =
                        when (Platform.osFamily) {
                            OsFamily.MACOSX -> {
                                "launchctl load -w $serviceOutputDir/io.github.klaw.gateway.plist"
                            }

                            else -> {
                                "systemctl --user start klaw-gateway"
                            }
                        }
                    val rc = commandRunner(startGatewayCmd)
                    if (rc == 0) {
                        success("Gateway started")
                    } else {
                        printer(
                            "${AnsiColors.YELLOW}⚠ Gateway start failed. " +
                                "Start manually: $startGatewayCmd${AnsiColors.RESET}",
                        )
                    }
                } else {
                    printer(
                        "${AnsiColors.YELLOW}⚠ systemd not found. " +
                            "Service auto-start will not be configured.${AnsiColors.RESET}",
                    )
                    printer("  Start services manually:")
                    printer("    $binDir/klaw-engine")
                    printer("    $gatewayBin")
                }
            }
        }

        // ── Identity: skip hatching if workspace already has identity files ──

        val hasIdentityFiles = fileExists("$workspaceDir/SOUL.md") && fileExists("$workspaceDir/IDENTITY.md")
        val agentName: String
        if (hasIdentityFiles || (force && workspaceExistedWithContent)) {
            CliLogger.info { "workspace has identity files or is non-empty on reinit, skipping hatching" }
            success("Using existing workspace files")
            agentName = "Klaw"
        } else {
            printer("")
            printer("Agent name [Klaw]:")
            agentName = readLineOrExit()?.trim().orEmpty().ifBlank { "Klaw" }
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
                                delay(SPINNER_TICK_MS)
                            }
                        }
                    val result =
                        runCatching {
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
                        }.getOrElse { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            CliLogger.error { "identity generation failed: ${e::class.simpleName}" }
                            """{"error":"${e::class.simpleName}"}"""
                        }
                    spinnerJob.cancel()
                    result
                }
            val errorMsg = extractJsonField(identityJson, "error")
            if (errorMsg != null) {
                identitySpinner.fail("Identity generation failed: $errorMsg")
                printer("  Stub files written. Edit IDENTITY.md and USER.md in the workspace to update later.")
                CliLogger.warn { "identity generation failed, writing stubs" }
            } else {
                identitySpinner.done("Identity generated")
            }
            val identity = extractJsonField(identityJson, "identity") ?: "# Identity\n\n$agentName"
            val user = extractJsonField(identityJson, "user") ?: "# User\n\n$userInfo"
            writeFileText("$workspaceDir/IDENTITY.md", identity)
            writeFileText("$workspaceDir/USER.md", user)
        }

        printSummary(printer, configDir, workspaceDir, agentName, modelId, resolvedMode, dockerTag)
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

    private fun startEngine(
        resolvedMode: DeployMode,
        canUseNativeService: Boolean,
        composeFilePath: String,
    ) {
        // Skip engine start for NATIVE when no service manager is available (no systemd/launchd)
        if (resolvedMode == DeployMode.NATIVE && !canUseNativeService) {
            printer(
                "${AnsiColors.YELLOW}⚠ No service manager available. " +
                    "Engine auto-start skipped.${AnsiColors.RESET}",
            )
            printer("  Start engine manually: $binDir/klaw-engine")
            return
        }
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
     * Attempts to select a model via radio selector (if models were fetched) or text input fallback.
     * For Anthropic, uses a hardcoded model list (no /models endpoint).
     * Returns null only if the user interrupts (ESC or EOF) during text input.
     */
    private fun selectModel(
        validationResponse: String?,
        providerAlias: String,
        providerType: String = "openai-compatible",
    ): String? {
        val models =
            if (providerType == "anthropic") {
                ANTHROPIC_MODELS
            } else {
                parseModels(validationResponse)
            }
        return if (models.isNotEmpty()) {
            val selectedIdx = radioSelector(models, "Select model:")
            if (selectedIdx != null && selectedIdx in models.indices) {
                "$providerAlias/${models[selectedIdx]}"
            } else {
                // Radio cancelled or out of bounds — fall back to text prompt
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

    private fun phase(
        n: Int,
        title: String,
    ) {
        printer("${AnsiColors.BOLD}● Phase $n of $TOTAL_PHASES — $title${AnsiColors.RESET}")
    }

    private fun success(message: String) {
        printer("${AnsiColors.GREEN}✓ $message${AnsiColors.RESET}")
    }
}

@Suppress("LongParameterList")
private fun printSummary(
    printer: (String) -> Unit,
    configDir: String,
    workspaceDir: String,
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
            printer("      -v klaw-config:/home/klaw/.config/klaw \\")
            printer("      -v klaw-state:/home/klaw/.local/state/klaw \\")
            printer("      -v klaw-data:/home/klaw/.local/share/klaw \\")
            printer("      -v klaw-workspace:/workspace \\")
            printer("      ghcr.io/sickfar/klaw-cli:$dockerTag [command]")
            printer("")
            printer("  Or install the klaw wrapper (adds 'klaw' to PATH):")
            @Suppress("MaxLineLength")
            printer(
                "    bash <(curl -sSL https://raw.githubusercontent.com/sickfar/klaw/main/scripts/get-klaw.sh) install",
            )
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

@Suppress("LongParameterList")
internal fun askPreApproval(
    validationResponse: String?,
    providerAlias: String,
    providerType: String,
    readLineOrExit: () -> String?,
    printer: (String) -> Unit,
    radioSelector: (List<String>, String) -> Int?,
    success: (String) -> Unit,
): String? {
    printer("Enable LLM pre-approval for commands? [Y/n]:")
    val answer = readLineOrExit() ?: return null
    if (answer.trim().lowercase() == "n") {
        printer("LLM pre-approval disabled (can be enabled later in engine.json)")
        return null
    }
    val models = if (providerType == "anthropic") ANTHROPIC_MODELS else parseModels(validationResponse)
    var selectedModel: String? = null
    if (models.isNotEmpty()) {
        val selectedIdx = radioSelector(models, "Select pre-approval model:")
        if (selectedIdx != null && selectedIdx in models.indices) {
            selectedModel = "$providerAlias/${models[selectedIdx]}"
        }
    }
    // Fall back to text input if radio was cancelled or model list was empty
    if (selectedModel == null) {
        printer("Model ID for pre-approval (provider/model):")
        val typed = readLineOrExit() ?: return null
        val trimmed = typed.trim()
        if (trimmed.isNotEmpty()) {
            selectedModel = trimmed
        }
    }
    if (selectedModel != null) {
        success("LLM pre-approval enabled with $selectedModel")
    } else {
        success("LLM pre-approval enabled (configure model in engine.json)")
    }
    return selectedModel
}

@Suppress("LongParameterList")
internal fun askVisionModelHelper(
    validationResponse: String?,
    providerAlias: String,
    providerType: String,
    readLineOrExit: () -> String?,
    printer: (String) -> Unit,
    radioSelector: (List<String>, String) -> Int?,
): String? {
    printer("Enable vision (image analysis)? [y/N]:")
    val answer = readLineOrExit() ?: return null
    if (answer.trim().lowercase() != "y") return null
    return selectVisionModel(validationResponse, providerAlias, providerType, readLineOrExit, printer, radioSelector)
}

@Suppress("LongParameterList")
private fun selectVisionModel(
    validationResponse: String?,
    providerAlias: String,
    providerType: String,
    readLineOrExit: () -> String?,
    printer: (String) -> Unit,
    radioSelector: (List<String>, String) -> Int?,
): String? {
    val allModels =
        if (providerType == "anthropic") {
            ANTHROPIC_MODELS
        } else {
            parseModels(validationResponse)
        }
    val visionModels = allModels.filter { ModelRegistry.supportsImage(it) }
    return if (visionModels.isNotEmpty()) {
        val selectedIdx = radioSelector(visionModels, "Select vision model:")
        if (selectedIdx != null && selectedIdx in visionModels.indices) {
            "$providerAlias/${visionModels[selectedIdx]}"
        } else {
            promptVisionModelText(providerAlias, readLineOrExit, printer)
        }
    } else {
        promptVisionModelText(providerAlias, readLineOrExit, printer)
    }
}

private fun promptVisionModelText(
    providerAlias: String,
    readLineOrExit: () -> String?,
    printer: (String) -> Unit,
): String? {
    printer("Vision model ID (e.g. $providerAlias/model-name):")
    val input = (readLineOrExit() ?: return null).trim()
    if (input.isBlank()) {
        printer("${AnsiColors.YELLOW}⚠ Vision skipped (no model specified).${AnsiColors.RESET}")
        return null
    }
    return input
}
