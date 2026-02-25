# TASK-003 — LLM Abstraction Layer

**Phase**: 2
**Priority**: P0
**Dependencies**: TASK-002
**Est. LOC**: ~550
**Design refs**: [§8.2 LLM Abstraction](../design/klaw-design-v0_4.md#82-llm-абстракция-client--provider--router), [§9.2 Engine Config](../design/klaw-design-v0_4.md#92-два-файла-gateway-и-engine), [§12 Risks](../design/klaw-design-v0_4.md#12-риски-и-митигация)

---

## Summary

Реализовать LLM абстракцию: интерфейс `LlmClient`, `OpenAiCompatibleClient`, `LlmRouter` с fallback chain. Всё тестируется через WireMock с записанными fixture-ответами для GLM-5, DeepSeek, Qwen, Ollama. Реальные API не вызываются в тестах.

---

## Goals

1. `LlmClient` interface (chat + chatStream)
2. `OpenAiCompatibleClient` — единый клиент для GLM-5, DeepSeek, Qwen, Ollama, OpenRouter
3. `LlmRouter` — маппинг `"provider/model-id"` → client + ProviderConfig, fallback chain
4. Retry + exponential backoff (configurable: maxRetries, initialBackoffMs, backoffMultiplier)
5. Request timeout (`requestTimeoutMs: 60000`)
6. LLM contract tests для каждого провайдера (WireMock fixtures)

---

## Implementation Details

### LlmClient Interface (`engine/` — JVM only, Micronaut HTTP Client)

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/llm/

interface LlmClient {
    suspend fun chat(
        request: LlmRequest,
        provider: ProviderConfig,
        model: ModelRef,
    ): LlmResponse

    fun chatStream(
        request: LlmRequest,
        provider: ProviderConfig,
        model: ModelRef,
    ): Flow<LlmChunk>  // Post-MVP: SSE streaming
}
```

**Примечание**: `LlmClient` и реализации — в `engine` модуле (JVM-only), не в `common`. Интерфейс и data models — в `common`. Это потому что Micronaut HTTP Client — JVM-only.

### OpenAiCompatibleClient

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/llm/

@Singleton
class OpenAiCompatibleClient(
    private val httpClient: HttpClient,  // Micronaut HTTP Client
    private val config: EngineConfig,
) : LlmClient {

    override suspend fun chat(
        request: LlmRequest,
        provider: ProviderConfig,
        model: ModelRef,
    ): LlmResponse {
        // Маппинг LlmRequest → OpenAI API format
        // POST {provider.endpoint}/chat/completions
        // Authorization: Bearer {provider.apiKey}
        // Retry с exponential backoff
        // Десериализация ответа → LlmResponse
        // Маппинг tool_calls из ответа
    }
}
```

**Маппинг запроса**:
```json
{
  "model": "glm-5",
  "messages": [...],
  "tools": [...],
  "max_tokens": 4096,
  "temperature": 0.7
}
```

**Маппинг ответа**:
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "...",
      "tool_calls": [{"id": "...", "function": {"name": "...", "arguments": "..."}}]
    },
    "finish_reason": "tool_calls"
  }],
  "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
}
```

### LlmRouter

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/llm/

@Singleton
class LlmRouter(
    private val config: EngineConfig,
    private val openAiClient: OpenAiCompatibleClient,
) {
    // Разрешает "glm/glm-5" → ProviderConfig("glm") + ModelRef("glm", "glm-5")
    fun resolve(fullModelId: String): Pair<ProviderConfig, ModelRef>

    // chat с fallback chain из routing.fallback
    suspend fun chat(request: LlmRequest, modelId: String): LlmResponse

    // Определяет нужный client по ProviderConfig.type
    private fun clientFor(provider: ProviderConfig): LlmClient
}
```

**Fallback chain** (из `engine.yaml`):
```yaml
routing:
  default: glm/glm-5
  fallback: [deepseek/deepseek-chat, ollama/qwen3:8b]
```
При ошибке основной модели → пробуем fallback'и по порядку. Логируем каждую попытку.

### Retry Logic

```kotlin
// Configurable через engine.yaml:
// llm.maxRetries: 2
// llm.requestTimeoutMs: 60000
// llm.initialBackoffMs: 1000
// llm.backoffMultiplier: 2.0

suspend fun <T> withRetry(
    maxRetries: Int,
    initialBackoffMs: Long,
    multiplier: Double,
    block: suspend () -> T,
): T
```

Retry при: `429 Too Many Requests`, `502/503/504`, `IOException`.
**Не retry** при: `400 Bad Request`, `401 Unauthorized`, `context_length_exceeded`.

---

## TDD Approach

Тесты **до** реализации.

### Test Suite (`engine/src/test/`)

**1. OpenAI format mapping tests** (unit, без HTTP):
```kotlin
class OpenAiRequestMappingTest {
    @Test fun `maps LlmRequest with tools to OpenAI format`()
    @Test fun `maps tool_calls response to LlmResponse`()
    @Test fun `maps finish_reason stop correctly`()
    @Test fun `maps finish_reason tool_calls correctly`()
    @Test fun `handles null content in response`()
    @Test fun `maps token usage correctly`()
}
```

**2. LLM contract tests** (WireMock — каждый провайдер):
```kotlin
class GlmContractTest {
    // WireMock stub с записанным ответом GLM-5
    @Test fun `GLM-5 basic chat request`()
    @Test fun `GLM-5 tool calling format`()
    @Test fun `GLM-5 tool_calls response parsing`()
}

class DeepSeekContractTest {
    @Test fun `DeepSeek basic chat request`()
    @Test fun `DeepSeek tool calling format`()
}

class OllamaContractTest {
    @Test fun `Ollama basic chat request`()
}
```

Fixture файлы с записанными ответами:
```
engine/src/test/resources/fixtures/llm/
├── glm_chat_basic_response.json
├── glm_tool_call_response.json
├── deepseek_chat_response.json
└── ollama_chat_response.json
```

**3. LlmRouter tests**:
```kotlin
class LlmRouterTest {
    @Test fun `resolves glm/glm-5 to correct provider and model`()
    @Test fun `uses fallback on primary model failure`()
    @Test fun `uses second fallback if first fails`()
    @Test fun `throws exception when all fallbacks exhausted`()
    @Test fun `interactive request uses default model`()
    @Test fun `subagent request uses tasks_subagent model`()
}
```

**4. Retry logic tests**:
```kotlin
class RetryTest {
    @Test fun `retries on 429 with exponential backoff`()
    @Test fun `retries on 503`()
    @Test fun `does NOT retry on 400`()
    @Test fun `does NOT retry on context_length_exceeded`()
    @Test fun `stops after maxRetries`()
}
```

**5. Regression: Chinese LLM function calling** (§12 Risks):
```kotlin
class ChineseLlmFunctionCallingTest {
    // Зафиксировать формат tool_call для каждой китайской модели
    // Защита от изменения формата между версиями
    @Test fun `GLM tool_call format matches fixture`()
    @Test fun `DeepSeek tool_call format matches fixture`()
}
```

---

## Acceptance Criteria

- [ ] `OpenAiCompatibleClient` работает с GLM-5, DeepSeek, Qwen, Ollama (WireMock тесты)
- [ ] Tool calling корректно маппится в обе стороны для всех провайдеров
- [ ] Fallback chain отрабатывает при ошибках провайдера
- [ ] Retry с backoff работает, не retry на 400/401
- [ ] Contract тесты зафиксированы и воспроизводимы (fixture файлы)
- [ ] Реальные LLM API **не вызываются** в тестах

---

## Constraints

- Реализация только в `engine` модуле (JVM). НЕ добавлять HTTP-клиент в `common`
- `AnthropicClient` и `GeminiClient` — Post-MVP (P1/P2). В этой задаче только `OpenAiCompatibleClient`
- Streaming (`chatStream`) — заглушка или базовая реализация. Полноценное SSE — Post-MVP (P2)
- НЕ использовать LangChain4j или Spring AI — только Micronaut HTTP Client

---

## Documentation Subtask

**File to create**: `doc/config/engine-yaml.md`

Document the LLM-related sections of `engine.yaml` that the agent needs to understand: which models are available, how routing works, and what the retry/timeout settings mean. The agent uses this to recommend model switches, understand fallback behavior, and know its own resource constraints.

**Sections to write** (English only):

- **Location** — `~/.config/klaw/engine.yaml`; read with `file_read` using the absolute path; the agent cannot write to this file (outside workspace)
- **providers** — name, type (`openai-compatible` / `anthropic` / `gemini`), endpoint, apiKey (env variable reference); one entry per LLM provider (glm, deepseek, ollama, anthropic)
- **models** — maps `provider/model-id` → `maxTokens` and `contextBudget`; `contextBudget` is the working limit, not the model's maximum window; lower budget = faster, cheaper, more predictable latency on Pi 5
- **routing** — `default` (model for new sessions), `fallback` (ordered list tried on primary failure), `tasks.subagent` (default for scheduled tasks), `tasks.summarization` (model for background summarization)
- **llm** — `maxRetries`, `requestTimeoutMs`, `initialBackoffMs`, `backoffMultiplier`; retry applies to 429/502/503/504; NOT retried on 400/401 or `context_length_exceeded`
- **How to check current model** — use `/model` slash command (returns current session model) or `/models` (lists all configured models with context budgets)
- **Switching model at runtime** — use `/model provider/model-id` slash command; the model must exist under `models:` in engine.yaml; the switch persists for the current session

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt
./gradlew engine:test
```
