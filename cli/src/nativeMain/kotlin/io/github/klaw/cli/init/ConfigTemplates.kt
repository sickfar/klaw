package io.github.klaw.cli.init

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.AttachmentsConfig
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.ComposeConfig
import io.github.klaw.common.config.ComposeServiceConfig
import io.github.klaw.common.config.ComposeVolumeConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DiscordConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.HeartbeatConfig
import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.config.LocalWsConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.PreValidationConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.config.VisionConfig
import io.github.klaw.common.config.WebConfig
import io.github.klaw.common.config.WebSearchConfig
import io.github.klaw.common.config.encodeComposeConfig
import io.github.klaw.common.config.encodeEngineConfigMinimal
import io.github.klaw.common.config.encodeGatewayConfigMinimal
import io.github.klaw.common.registry.ProviderRegistry

internal object ConfigTemplates {
    /** Derives the env var name for a provider's API key: `zai` → `ZAI_API_KEY`. */
    fun apiKeyEnvVar(providerAlias: String): String = "${providerAlias.uppercase()}_API_KEY"

    @Suppress("LongParameterList")
    fun engineJson(
        modelId: String,
        heartbeatChannel: String? = null,
        webSearchEnabled: Boolean = false,
        webSearchProvider: String? = null,
        webSearchApiKeyEnvVar: String? = null,
        hostExecutionEnabled: Boolean = false,
        preValidationModel: String? = null,
        visionModelId: String? = null,
        attachmentsDirectory: String = "",
        workspace: String? = null,
    ): String =
        encodeEngineConfigMinimal(
            buildEngineConfig(
                modelId,
                heartbeatChannel,
                webSearchEnabled,
                webSearchProvider,
                webSearchApiKeyEnvVar,
                hostExecutionEnabled,
                preValidationModel,
                visionModelId,
                attachmentsDirectory,
                workspace,
            ),
        )

    @Suppress("LongParameterList")
    private fun buildEngineConfig(
        modelId: String,
        heartbeatChannel: String?,
        webSearchEnabled: Boolean,
        webSearchProvider: String?,
        webSearchApiKeyEnvVar: String?,
        hostExecutionEnabled: Boolean,
        preValidationModel: String?,
        visionModelId: String?,
        attachmentsDirectory: String,
        workspace: String?,
    ): EngineConfig {
        val providerName = modelId.substringBefore("/").ifBlank { "default" }
        val webSearch =
            if (webSearchEnabled && webSearchProvider != null && webSearchApiKeyEnvVar != null) {
                WebSearchConfig(
                    enabled = true,
                    provider = webSearchProvider,
                    apiKey = "\${$webSearchApiKeyEnvVar}",
                )
            } else {
                WebSearchConfig()
            }
        val vision =
            if (visionModelId != null) {
                VisionConfig(enabled = true, model = visionModelId, attachmentsDirectory = attachmentsDirectory)
            } else {
                VisionConfig()
            }
        return EngineConfig(
            workspace = workspace,
            providers = buildConfigProviders(providerName),
            models = buildConfigModels(modelId, visionModelId),
            routing = buildConfigRouting(modelId),
            context = buildConfigContext(),
            processing = buildConfigProcessing(),
            memory = buildConfigMemory(),
            hostExecution =
                if (hostExecutionEnabled) {
                    HostExecutionConfig(
                        enabled = true,
                        preValidation =
                            if (preValidationModel != null) {
                                PreValidationConfig(model = preValidationModel)
                            } else {
                                PreValidationConfig()
                            },
                    )
                } else {
                    HostExecutionConfig()
                },
            heartbeat =
                HeartbeatConfig(
                    interval = if (heartbeatChannel != null) "PT1H" else "off",
                    channel = heartbeatChannel,
                ),
            web = WebConfig(search = webSearch),
            vision = vision,
        )
    }

    @Suppress("LongParameterList")
    fun gatewayJson(
        telegramEnabled: Boolean = true,
        allowedChats: List<AllowedChat> = emptyList(),
        discordEnabled: Boolean = false,
        discordAllowedGuilds: List<String> = emptyList(),
        enableLocalWs: Boolean = false,
        localWsPort: Int = 37474,
        attachmentsDirectory: String = "",
    ): String {
        val telegram =
            if (telegramEnabled) {
                TelegramConfig(
                    token = "\${KLAW_TELEGRAM_TOKEN}",
                    allowedChats = allowedChats,
                )
            } else {
                null
            }
        val discord =
            if (discordEnabled) {
                DiscordConfig(
                    enabled = true,
                    token = "\${KLAW_DISCORD_TOKEN}",
                    allowedGuilds = discordAllowedGuilds.map { AllowedGuild(guildId = it) },
                )
            } else {
                null
            }
        val localWs =
            if (enableLocalWs) {
                LocalWsConfig(
                    enabled = true,
                    port = localWsPort,
                )
            } else {
                null
            }
        val attachments =
            if (attachmentsDirectory.isNotBlank()) {
                AttachmentsConfig(directory = attachmentsDirectory)
            } else {
                AttachmentsConfig()
            }
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        telegram = telegram,
                        discord = discord,
                        localWs = localWs,
                    ),
                attachments = attachments,
            )
        return encodeGatewayConfigMinimal(config)
    }

    @Suppress("LongParameterList")
    fun dockerComposeJson(
        statePath: String,
        dataPath: String,
        configPath: String,
        workspacePath: String,
        imageTag: String,
        enableLocalWs: Boolean = false,
        localWsPort: Int = 37474,
    ): String {
        val engine = hybridEngineService(imageTag, statePath, dataPath, configPath, workspacePath)
        val gateway = hybridGatewayService(imageTag, statePath, dataPath, configPath, enableLocalWs, localWsPort)
        val config =
            ComposeConfig(
                services = mapOf("engine" to engine, "gateway" to gateway),
                volumes = mapOf("klaw-cache" to ComposeVolumeConfig(name = "klaw-cache")),
            )
        return encodeComposeConfig(config)
    }

    @Suppress("LongParameterList")
    private fun hybridGatewayService(
        imageTag: String,
        statePath: String,
        dataPath: String,
        configPath: String,
        enableLocalWs: Boolean,
        localWsPort: Int,
    ): ComposeServiceConfig =
        ComposeServiceConfig(
            image = "ghcr.io/sickfar/klaw-gateway:$imageTag",
            restart = "unless-stopped",
            envFile = ".env",
            dependsOn = listOf("engine"),
            environment = mapOf("HOME" to "/home/klaw", "KLAW_ENGINE_HOST" to "engine"),
            volumes =
                listOf(
                    "$statePath:/home/klaw/.local/state/klaw",
                    "$dataPath:/home/klaw/.local/share/klaw",
                    "$configPath:/home/klaw/.config/klaw",
                ),
            ports = if (enableLocalWs) listOf("127.0.0.1:$localWsPort:$localWsPort") else null,
        )

    private fun hybridEngineService(
        imageTag: String,
        statePath: String,
        dataPath: String,
        configPath: String,
        workspacePath: String,
    ): ComposeServiceConfig =
        ComposeServiceConfig(
            image = "ghcr.io/sickfar/klaw-engine:$imageTag",
            restart = "unless-stopped",
            envFile = ".env",
            environment =
                mapOf(
                    "HOME" to "/home/klaw",
                    "KLAW_WORKSPACE" to "/workspace",
                    "KLAW_HOST_WORKSPACE" to workspacePath,
                    "KLAW_ENGINE_BIND" to "0.0.0.0",
                ),
            volumes =
                listOf(
                    "$statePath:/home/klaw/.local/state/klaw",
                    "$dataPath:/home/klaw/.local/share/klaw",
                    "$configPath:/home/klaw/.config/klaw",
                    "$workspacePath:/workspace",
                    "klaw-cache:/home/klaw/.cache/klaw",
                    "/var/run/docker.sock:/var/run/docker.sock",
                ),
            ports = listOf("127.0.0.1:7470:7470"),
        )

    fun dockerComposeProd(imageTag: String = "latest"): String {
        val config =
            ComposeConfig(
                services =
                    mapOf(
                        "engine" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-engine:$imageTag",
                                restart = "unless-stopped",
                                environment =
                                    mapOf(
                                        "HOME" to "/home/klaw",
                                        "KLAW_WORKSPACE" to "/workspace",
                                        "KLAW_ENGINE_BIND" to "0.0.0.0",
                                    ),
                                volumes =
                                    listOf(
                                        "klaw-state:/home/klaw/.local/state/klaw",
                                        "klaw-data:/home/klaw/.local/share/klaw",
                                        "klaw-cache:/home/klaw/.cache/klaw",
                                        "klaw-workspace:/workspace",
                                        "klaw-config:/home/klaw/.config/klaw",
                                        "/var/run/docker.sock:/var/run/docker.sock",
                                    ),
                            ),
                        "gateway" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-gateway:$imageTag",
                                restart = "unless-stopped",
                                dependsOn = listOf("engine"),
                                environment =
                                    mapOf(
                                        "HOME" to "/home/klaw",
                                        "KLAW_ENGINE_HOST" to "engine",
                                    ),
                                volumes =
                                    listOf(
                                        "klaw-state:/home/klaw/.local/state/klaw",
                                        "klaw-data:/home/klaw/.local/share/klaw",
                                        "klaw-config:/home/klaw/.config/klaw",
                                    ),
                            ),
                    ),
                volumes =
                    mapOf(
                        "klaw-state" to ComposeVolumeConfig(name = "klaw-state"),
                        "klaw-data" to ComposeVolumeConfig(name = "klaw-data"),
                        "klaw-cache" to ComposeVolumeConfig(name = "klaw-cache"),
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

private fun buildConfigProviders(providerName: String): Map<String, ProviderConfig> {
    val isKnown = ProviderRegistry.isKnown(providerName)
    return mapOf(
        providerName to
            if (isKnown) {
                ProviderConfig(apiKey = "\${${ConfigTemplates.apiKeyEnvVar(providerName)}}")
            } else {
                ProviderConfig(
                    type = "openai-compatible",
                    endpoint = "http://localhost:8080/v1",
                    apiKey = "\${${ConfigTemplates.apiKeyEnvVar(providerName)}}",
                )
            },
    )
}

private fun buildConfigModels(
    modelId: String,
    visionModelId: String? = null,
): Map<String, ModelConfig> =
    buildMap {
        put(modelId, ModelConfig())
        if (visionModelId != null) {
            put(visionModelId, ModelConfig())
        }
    }

private fun buildConfigRouting(modelId: String): RoutingConfig =
    RoutingConfig(
        default = modelId,
        fallback = emptyList(),
        tasks = TaskRoutingConfig(summarization = modelId, subagent = modelId),
    )

private fun buildConfigContext(): ContextConfig =
    ContextConfig(
        defaultBudgetTokens = 100_000,
        subagentHistory = 10,
    )

private fun buildConfigProcessing(): ProcessingConfig =
    ProcessingConfig(
        debounceMs = 800,
        maxConcurrentLlm = 3,
        maxToolCallRounds = 50,
    )

private fun buildConfigMemory(): MemoryConfig =
    MemoryConfig(
        embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
        chunking = ChunkingConfig(size = 512, overlap = 64),
        search = SearchConfig(topK = 10),
    )
