package io.github.klaw.cli.init

import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.ui.Spinner
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.paths.KlawPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val TOTAL_PHASES = 9

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
    private val engineSocketPath: String = KlawPaths.engineSocket,
    private val requestFn: EngineRequest,
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
    /** Factory that receives an `onTick` callback used to drive a spinner during polling. */
    private val engineStarterFactory: (onTick: () -> Unit) -> EngineStarter =
        { onTick ->
            EngineStarter(
                engineSocketPath = engineSocketPath,
                commandRunner = commandRunner,
                onTick = onTick,
            )
        },
) {
    fun run() {
        phase(1, "Pre-check")
        if (fileExists("$configDir/engine.yaml")) {
            printer("Already initialized. Use: klaw config set to modify settings.")
            return
        }

        phase(2, "Directory setup")
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
        success("Directories created")

        phase(3, "LLM provider setup")
        printer("LLM provider base URL [https://open.bigmodel.cn/api/paas/v4]:")
        val providerUrl = readLine()?.trim().orEmpty().ifBlank { "https://open.bigmodel.cn/api/paas/v4" }
        printer("LLM API key:")
        val llmApiKey = readLine()?.trim().orEmpty()
        printer("Model ID [glm/glm-4-plus]:")
        val modelId = readLine()?.trim().orEmpty().ifBlank { "glm/glm-4-plus" }

        phase(4, "Telegram setup")
        printer("Telegram bot token:")
        val telegramToken = readLine()?.trim().orEmpty()
        printer("Allowed chat IDs (comma-separated, empty = allow all):")
        val rawChatIds = readLine()?.trim().orEmpty()
        val chatIds =
            if (rawChatIds.isBlank()) {
                emptyList()
            } else {
                rawChatIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

        phase(5, "Config generation")
        writeFileText("$configDir/engine.yaml", ConfigTemplates.engineYaml(providerUrl, modelId))
        writeFileText("$configDir/gateway.yaml", ConfigTemplates.gatewayYaml(chatIds))
        EnvWriter.write(
            "$configDir/.env",
            mapOf(
                "KLAW_LLM_API_KEY" to llmApiKey,
                "KLAW_TELEGRAM_TOKEN" to telegramToken,
            ),
        )
        success("Configuration written")

        phase(6, "Engine auto-start")
        val spinner = Spinner("Starting Engine...")
        val engineStarter = engineStarterFactory { spinner.tick() }
        val engineStarted = engineStarter.startAndWait()
        if (!engineStarted) {
            printer("${AnsiColors.YELLOW}⚠ Engine did not start automatically.${AnsiColors.RESET}")
            printer("  Start it manually: systemctl --user start klaw-engine")
            printer("  Identity generation will proceed and may fail if engine is not running.")
        } else {
            spinner.done("Engine started")
        }

        phase(7, "Identity Q&A")
        printer("Agent name [Klaw]:")
        val agentName = readLine()?.trim().orEmpty().ifBlank { "Klaw" }
        printer("Personality traits (e.g. curious, analytical, warm):")
        val personality = readLine()?.trim().orEmpty()
        printer("Primary role (e.g. personal assistant, coding helper):")
        val role = readLine()?.trim().orEmpty()
        printer("Tell me about the user who will work with this agent:")
        val userInfo = readLine()?.trim().orEmpty()
        printer("Specialized domains or expertise (optional):")
        val domain = readLine()?.trim().orEmpty()

        phase(8, "Identity generation")
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
                                    "personality" to personality,
                                    "role" to role,
                                    "user_info" to userInfo,
                                    "domain" to domain,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        """{"error":"${e::class.simpleName}"}"""
                    }
                spinnerJob.cancel()
                result
            }
        identitySpinner.done("Identity generated")
        val soul = extractJsonField(identityJson, "soul") ?: "# Soul\n\nBe helpful and curious."
        val identity = extractJsonField(identityJson, "identity") ?: "# Identity\n\n$agentName"
        val agents = extractJsonField(identityJson, "agents") ?: "# Agents\n\nHelp the user effectively."
        val user = extractJsonField(identityJson, "user") ?: "# User\n\n$userInfo"
        writeFileText("$workspaceDir/SOUL.md", soul)
        writeFileText("$workspaceDir/IDENTITY.md", identity)
        writeFileText("$workspaceDir/AGENTS.md", agents)
        writeFileText("$workspaceDir/USER.md", user)

        phase(9, "Service setup")
        val envFile = "$configDir/.env"
        val engineBin = "/usr/local/bin/klaw-engine"
        val gatewayBin = "/usr/local/bin/klaw-gateway"
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

        printSummary(agentName, modelId)
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

    private fun printSummary(
        agentName: String,
        modelId: String,
    ) {
        printer("")
        printer("${AnsiColors.BOLD}─── Setup complete ───${AnsiColors.RESET}")
        printer("  ${AnsiColors.CYAN}Config${AnsiColors.RESET}: $configDir")
        printer("  ${AnsiColors.CYAN}Workspace${AnsiColors.RESET}: $workspaceDir")
        printer("  ${AnsiColors.CYAN}Agent${AnsiColors.RESET}: $agentName")
        printer("  ${AnsiColors.CYAN}Model${AnsiColors.RESET}: $modelId")
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
