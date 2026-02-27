package io.github.klaw.cli.init

internal object ConfigTemplates {
    /** Derives the env var name for a provider's API key: `zai` → `ZAI_API_KEY`. */
    fun apiKeyEnvVar(providerAlias: String): String = "${providerAlias.uppercase()}_API_KEY"

    fun engineYaml(
        providerUrl: String,
        modelId: String,
    ): String {
        val providerName = modelId.substringBefore("/").ifBlank { "default" }
        val modelName = modelId.substringAfter("/")
        val apiKeyEnvVar = apiKeyEnvVar(providerName)
        return """
providers:
  $providerName:
    type: openai-compatible
    endpoint: "$providerUrl"
    apiKey: "${'$'}{$apiKeyEnvVar}"
models:
  $modelId:
    provider: $providerName
    modelId: $modelName
    maxTokens: 8192
    contextBudget: 16384
routing:
  default: $modelId
  tasks:
    summarization: $modelId
    subagent: $modelId
  fallback: []
context:
  slidingWindow: 20
  defaultBudgetTokens: 4096
  subagentHistory: 10
processing:
  debounceMs: 800
  maxConcurrentLlm: 3
  maxToolCallRounds: 10
memory:
  embedding:
    type: onnx
    model: all-MiniLM-L6-v2
  chunking:
    size: 512
    overlap: 64
  search:
    topK: 10
            """.trimIndent()
    }

    @Suppress("LongParameterList")
    fun gatewayYaml(
        telegramEnabled: Boolean = true,
        allowedChatIds: List<String> = emptyList(),
        enableConsole: Boolean = false,
        consolePort: Int = 37474,
    ): String {
        val chatIdsYaml = if (allowedChatIds.isEmpty()) "" else allowedChatIds.joinToString(", ")
        val telegramSection =
            if (telegramEnabled) {
                """
  telegram:
    token: "${'$'}{KLAW_TELEGRAM_TOKEN}"
    allowedChatIds: [$chatIdsYaml]"""
            } else {
                ""
            }
        val consoleSection =
            if (enableConsole) {
                """
  console:
    enabled: true
    port: $consolePort"""
            } else {
                ""
            }
        return "channels:$telegramSection$consoleSection".trimEnd()
    }

    @Suppress("LongParameterList")
    fun dockerComposeHybrid(
        statePath: String,
        dataPath: String,
        configPath: String,
        workspacePath: String,
        imageTag: String,
    ): String =
        """
services:
  engine:
    image: ghcr.io/sickfar/klaw-engine:$imageTag
    restart: unless-stopped
    env_file: .env
    environment:
      KLAW_WORKSPACE: /workspace
    volumes:
      - $statePath:/root/.local/state/klaw
      - $dataPath:/root/.local/share/klaw
      - $configPath:/root/.config/klaw:ro
      - $workspacePath:/workspace
  gateway:
    image: ghcr.io/sickfar/klaw-gateway:$imageTag
    restart: unless-stopped
    env_file: .env
    depends_on:
      - engine
    volumes:
      - $statePath:/root/.local/state/klaw
      - $dataPath:/root/.local/share/klaw
      - $configPath:/root/.config/klaw:ro
        """.trimIndent().trimEnd()

    fun deployConf(
        mode: DeployMode,
        dockerTag: String,
    ): String = "mode=${mode.configName}\ndocker_tag=$dockerTag\n"
}
