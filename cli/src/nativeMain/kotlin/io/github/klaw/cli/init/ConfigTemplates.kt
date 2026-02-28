package io.github.klaw.cli.init

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.ComposeConfig
import io.github.klaw.common.config.ComposeServiceConfig
import io.github.klaw.common.config.ComposeVolumeConfig
import io.github.klaw.common.config.ConsoleConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.config.encodeComposeConfig
import io.github.klaw.common.config.encodeEngineConfig
import io.github.klaw.common.config.encodeGatewayConfig

internal object ConfigTemplates {
    /** Derives the env var name for a provider's API key: `zai` → `ZAI_API_KEY`. */
    fun apiKeyEnvVar(providerAlias: String): String = "${providerAlias.uppercase()}_API_KEY"

    fun engineJson(
        providerUrl: String,
        modelId: String,
    ): String {
        val providerName = modelId.substringBefore("/").ifBlank { "default" }
        val apiKeyEnvVar = apiKeyEnvVar(providerName)
        val config =
            EngineConfig(
                providers =
                    mapOf(
                        providerName to
                            ProviderConfig(
                                type = "openai-compatible",
                                endpoint = providerUrl,
                                apiKey = "\${$apiKeyEnvVar}",
                            ),
                    ),
                models =
                    mapOf(
                        modelId to
                            ModelConfig(
                                maxTokens = 8192,
                                contextBudget = 16384,
                            ),
                    ),
                routing =
                    RoutingConfig(
                        default = modelId,
                        fallback = emptyList(),
                        tasks =
                            TaskRoutingConfig(
                                summarization = modelId,
                                subagent = modelId,
                            ),
                    ),
                context =
                    ContextConfig(
                        slidingWindow = 20,
                        defaultBudgetTokens = 4096,
                        subagentHistory = 10,
                    ),
                processing =
                    ProcessingConfig(
                        debounceMs = 800,
                        maxConcurrentLlm = 3,
                        maxToolCallRounds = 10,
                    ),
                memory =
                    MemoryConfig(
                        embedding =
                            EmbeddingConfig(
                                type = "onnx",
                                model = "all-MiniLM-L6-v2",
                            ),
                        chunking =
                            ChunkingConfig(
                                size = 512,
                                overlap = 64,
                            ),
                        search =
                            SearchConfig(
                                topK = 10,
                            ),
                    ),
            )
        return encodeEngineConfig(config)
    }

    @Suppress("LongParameterList")
    fun gatewayJson(
        telegramEnabled: Boolean = true,
        allowedChatIds: List<String> = emptyList(),
        enableConsole: Boolean = false,
        consolePort: Int = 37474,
    ): String {
        val telegram =
            if (telegramEnabled) {
                TelegramConfig(
                    token = "\${KLAW_TELEGRAM_TOKEN}",
                    allowedChatIds = allowedChatIds,
                )
            } else {
                null
            }
        val console =
            if (enableConsole) {
                ConsoleConfig(
                    enabled = true,
                    port = consolePort,
                )
            } else {
                null
            }
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram = telegram,
                        console = console,
                    ),
            )
        return encodeGatewayConfig(config)
    }

    @Suppress("LongParameterList")
    fun dockerComposeJson(
        statePath: String,
        dataPath: String,
        configPath: String,
        workspacePath: String,
        imageTag: String,
    ): String {
        val config =
            ComposeConfig(
                services =
                    mapOf(
                        "engine" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-engine:$imageTag",
                                restart = "unless-stopped",
                                envFile = ".env",
                                environment =
                                    mapOf(
                                        "HOME" to "/home/klaw",
                                        "KLAW_WORKSPACE" to "/workspace",
                                        "KLAW_SOCKET_PATH" to "/home/klaw/.local/state/klaw/run/engine.sock",
                                        "KLAW_SOCKET_PERMS" to "rw-rw-rw-",
                                    ),
                                volumes =
                                    listOf(
                                        "$statePath:/home/klaw/.local/state/klaw",
                                        "klaw-run:/home/klaw/.local/state/klaw/run",
                                        "$dataPath:/home/klaw/.local/share/klaw",
                                        "$configPath:/home/klaw/.config/klaw:ro",
                                        "$workspacePath:/workspace",
                                    ),
                            ),
                        "gateway" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-gateway:$imageTag",
                                restart = "unless-stopped",
                                envFile = ".env",
                                dependsOn = listOf("engine"),
                                environment =
                                    mapOf(
                                        "HOME" to "/home/klaw",
                                        "KLAW_SOCKET_PATH" to "/home/klaw/.local/state/klaw/run/engine.sock",
                                    ),
                                volumes =
                                    listOf(
                                        "$statePath:/home/klaw/.local/state/klaw",
                                        "klaw-run:/home/klaw/.local/state/klaw/run",
                                        "$dataPath:/home/klaw/.local/share/klaw",
                                        "$configPath:/home/klaw/.config/klaw:ro",
                                    ),
                            ),
                    ),
                volumes = mapOf("klaw-run" to ComposeVolumeConfig(name = "klaw-run")),
            )
        return encodeComposeConfig(config)
    }

    fun dockerComposeProd(imageTag: String = "latest"): String {
        val config =
            ComposeConfig(
                services =
                    mapOf(
                        "engine" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-engine:$imageTag",
                                restart = "unless-stopped",
                                environment = mapOf("KLAW_WORKSPACE" to "/workspace"),
                                volumes =
                                    listOf(
                                        "klaw-state:/root/.local/state/klaw",
                                        "klaw-data:/root/.local/share/klaw",
                                        "klaw-workspace:/workspace",
                                        "klaw-config:/root/.config/klaw:ro",
                                    ),
                            ),
                        "gateway" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-gateway:$imageTag",
                                restart = "unless-stopped",
                                dependsOn = listOf("engine"),
                                volumes =
                                    listOf(
                                        "klaw-state:/root/.local/state/klaw",
                                        "klaw-data:/root/.local/share/klaw",
                                        "klaw-config:/root/.config/klaw:ro",
                                    ),
                            ),
                    ),
                volumes =
                    mapOf(
                        "klaw-state" to ComposeVolumeConfig(name = "klaw-state"),
                        "klaw-data" to ComposeVolumeConfig(name = "klaw-data"),
                        "klaw-workspace" to ComposeVolumeConfig(name = "klaw-workspace"),
                        "klaw-config" to ComposeVolumeConfig(name = "klaw-config"),
                    ),
            )
        return encodeComposeConfig(config)
    }

    fun deployConf(
        mode: DeployMode,
        dockerTag: String,
    ): String = "mode=${mode.configName}\ndocker_tag=$dockerTag\n"
}
