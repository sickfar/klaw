package io.github.klaw.cli.init

internal object ConfigTemplates {
    fun engineYaml(
        providerUrl: String,
        modelId: String,
    ): String {
        val providerName = modelId.substringBefore("/").ifBlank { "default" }
        val modelName = modelId.substringAfter("/")
        return """
providers:
  $providerName:
    type: openai-compatible
    baseUrl: "$providerUrl"
    apiKey: "${'$'}{KLAW_LLM_API_KEY}"
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

    fun gatewayYaml(allowedChatIds: List<String> = emptyList()): String {
        val chatIdsYaml = if (allowedChatIds.isEmpty()) "" else allowedChatIds.joinToString(", ")
        return """
channels:
  telegram:
    token: "${'$'}{KLAW_TELEGRAM_TOKEN}"
    allowedChatIds: [$chatIdsYaml]
            """.trimIndent()
    }
}
