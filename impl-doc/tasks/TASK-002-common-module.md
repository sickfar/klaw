# TASK-002 — Common Module (KMP)

**Phase**: 1
**Priority**: P0
**Dependencies**: TASK-001
**Est. LOC**: ~500
**Design refs**: [§8.1](../design/klaw-design-v0_4.md#81-фреймворк-micronaut), [§8.2 LLM Abstraction](../design/klaw-design-v0_4.md#82-llm-абстракция-client--provider--router), [§2.3 IPC Protocol](../design/klaw-design-v0_4.md#23-межпроцессное-взаимодействие-persistent-jsonl-socket), [§5.2 JSONL Format](../design/klaw-design-v0_4.md#52-формат-jsonl), [§9.1 XDG Paths](../design/klaw-design-v0_4.md#91-xdg-совместимые-пути)

---

## Summary

Реализовать KMP `common` модуль с моделями данных, форматами сериализации, конфигурационными моделями и утилитами. Этот модуль шарится между `gateway` (JVM), `engine` (JVM) и `cli` (Native) — поэтому только KMP-совместимые зависимости в `commonMain`.

---

## Goals

1. Модели данных LLM (в commonMain)
2. Модели JSONL-протокола (socket IPC)
3. Конфигурационные модели (`gateway.yaml`, `engine.yaml`)
4. Модели данных БД (для SqlDelight schemas)
5. JSONL сериализация/десериализация
6. YAML парсинг (kaml)
7. XDG пути (KlatPaths)
8. Утилиты: подсчёт токенов, форматирование
9. Типы ошибок

---

## Implementation Details

### LLM Models (`commonMain`)

```kotlin
// io/github/klaw/common/llm/

@Serializable
data class LlmMessage(
    val role: String,   // "system", "user", "assistant", "tool"
    val content: String?,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
)

@Serializable
data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolDef>? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
)

@Serializable
data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall>?,
    val usage: TokenUsage?,
    val finishReason: FinishReason,
)

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

@Serializable
data class ToolResult(val callId: String, val content: String)

@Serializable
data class ToolDef(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class TokenUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int)

enum class FinishReason { STOP, TOOL_CALLS, LENGTH, ERROR }
```

### Socket Protocol Models (`commonMain`)

```kotlin
// io/github/klaw/common/protocol/

@Serializable
sealed class SocketMessage {
    abstract val type: String
}

@Serializable
data class InboundSocketMessage(
    override val type: String = "inbound",
    val id: String,
    val channel: String,
    val chatId: String,
    val content: String,
    val ts: String,
) : SocketMessage()

@Serializable
data class OutboundSocketMessage(
    override val type: String = "outbound",
    val replyTo: String? = null,
    val channel: String,
    val chatId: String,
    val content: String,
    val meta: Map<String, String>? = null,
) : SocketMessage()

@Serializable
data class CommandSocketMessage(
    override val type: String = "command",
    val channel: String,
    val chatId: String,
    val command: String,
    val args: String? = null,
) : SocketMessage()

@Serializable
data class RegisterMessage(
    override val type: String = "register",
    val client: String,         // "gateway"
) : SocketMessage()

@Serializable
data class CliRequestMessage(
    val command: String,        // "status", "memory_search", "schedule_add", ...
    val params: Map<String, String> = emptyMap(),
)
```

### JSONL Conversation Models (`commonMain`)

```kotlin
// io/github/klaw/common/conversation/

@Serializable
data class ConversationMessage(
    val id: String,
    val ts: String,         // ISO-8601
    val role: String,       // "user", "assistant", "system", "tool"
    val content: String,
    val type: String? = null,   // null, "session_break", "subagent_result"
    val meta: MessageMeta? = null,
)

@Serializable
data class MessageMeta(
    val channel: String? = null,
    val chatId: String? = null,
    val model: String? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val source: String? = null,         // "scheduler", "heartbeat"
    val taskName: String? = null,
    val tool: String? = null,
)
```

### Config Models (`commonMain`)

```kotlin
// io/github/klaw/common/config/

@Serializable
data class GatewayConfig(
    val channels: ChannelsConfig,
)

@Serializable
data class ChannelsConfig(
    val telegram: TelegramConfig? = null,
    val discord: DiscordConfig? = null,
)

@Serializable
data class TelegramConfig(
    val token: String,
    val allowedChatIds: List<String> = emptyList(),
)

@Serializable
data class EngineConfig(
    val providers: Map<String, ProviderConfig>,
    val models: Map<String, ModelConfig>,
    val routing: RoutingConfig,
    val memory: MemoryConfig,
    val context: ContextConfig,
    val processing: ProcessingConfig,
    val llm: LlmRetryConfig,
    val logging: LoggingConfig,
    val codeExecution: CodeExecutionConfig,
    val files: FilesConfig,
    val commands: List<CommandConfig> = emptyList(),
)

@Serializable
data class ProviderConfig(
    val type: String,       // "openai-compatible", "anthropic", "gemini"
    val endpoint: String,
    val apiKey: String? = null,
)

@Serializable
data class ModelRef(
    val provider: String,
    val modelId: String,
    val maxTokens: Int? = null,
    val contextBudget: Int? = null,
    val temperature: Double? = null,
) {
    val fullId: String get() = "$provider/$modelId"
}
// ... остальные config data classes
```

### XDG Paths (`commonMain`)

```kotlin
// io/github/klaw/common/paths/

object KlawPaths {
    val config: Path get() = (env("XDG_CONFIG_HOME") ?: home() / ".config") / "klaw"
    val data: Path get() = (env("XDG_DATA_HOME") ?: home() / ".local/share") / "klaw"
    val state: Path get() = (env("XDG_STATE_HOME") ?: home() / ".local/state") / "klaw"
    val cache: Path get() = (env("XDG_CACHE_HOME") ?: home() / ".cache") / "klaw"
    val workspace: Path get() = env("KLAW_WORKSPACE") ?: home() / "klaw-workspace"

    val engineSocket: Path get() = state / "engine.sock"
    val gatewayBuffer: Path get() = state / "gateway-buffer.jsonl"
    val klawDb: Path get() = data / "klaw.db"
    val schedulerDb: Path get() = data / "scheduler.db"
    val conversations: Path get() = data / "conversations"
    val summaries: Path get() = data / "summaries"
    val coreMemory: Path get() = data / "memory" / "core_memory.json"
    val skills: Path get() = data / "skills"
}
```

### Token Counting Utility (`commonMain`)

```kotlin
// io/github/klaw/common/util/TokenCounter.kt
// Approximate counting: English text / 3.5, Russian/Chinese / 2
// Tech debt: replace with HuggingFace Tokenizer (Post-MVP)
fun approximateTokenCount(text: String): Int
```

---

## TDD Approach

Тесты пишутся **до** реализации:

### Test Suite (commonTest)

1. **Serialization tests**:
   - JSONL round-trip: `ConversationMessage` serialize → deserialize
   - Socket messages: каждый тип сериализуется без потерь
   - LLM models: `LlmRequest`, `LlmResponse` с tool calls
   - Null-safety: nullable поля правильно обрабатываются

2. **Config YAML parsing tests**:
   - `gateway.yaml` с Telegram-конфигом парсится в `GatewayConfig`
   - `engine.yaml` полный конфиг парсится в `EngineConfig`
   - Edge cases: опциональные поля, env variable substitution

3. **XDG Paths tests**:
   - Дефолтные пути правильно строятся из `$HOME`
   - Кастомные `XDG_*` переменные перекрывают дефолты
   - `$KLAW_WORKSPACE` используется когда задана

4. **Token counting tests**:
   - Английский текст: длина / 3.5 ± погрешность
   - Русский/китайский текст: длина / 2 ± погрешность
   - Пустая строка → 0

5. **KMP targets**: тесты запускаются на JVM **и** Native

### Test Location

```
common/src/commonTest/kotlin/io/github/klaw/common/
├── llm/LlmModelsTest.kt
├── protocol/SocketProtocolTest.kt
├── conversation/ConversationMessageTest.kt
├── config/ConfigParsingTest.kt
├── paths/KlawPathsTest.kt
└── util/TokenCounterTest.kt
```

---

## Acceptance Criteria

- [ ] Все модели сериализуются/десериализуются через kotlinx.serialization
- [ ] `gateway.yaml` и `engine.yaml` примеры из design doc парсятся без ошибок
- [ ] JSONL формат из design doc §5.2 полностью покрыт
- [ ] Socket protocol из design doc §2.3 полностью покрыт
- [ ] XDG пути корректны для Linux (Pi 5 target)
- [ ] Тесты проходят на JVM: `./gradlew common:jvmTest`
- [ ] Тесты проходят на Native: `./gradlew common:nativeTest` (linuxX64 на dev машине)
- [ ] Нет JVM-only зависимостей в `commonMain`

---

## Constraints

- В `commonMain` — **только** KMP-совместимые зависимости: `kotlinx-serialization`, `kotlinx-datetime`, `kaml`, `kotlinx-coroutines-core`
- Нет бизнес-логики LLM вызовов — только data models
- Нет файловых I/O в `commonMain` — только path construction (actual I/O — в `jvmMain`/`nativeMain`)
- `ModelRef.fullId` format: `"provider/modelId"` — строгое следование дизайну

---

## Documentation Subtask

No user-facing documentation for this task. `common` contains internal data models, serialization logic, and path utilities — none of these are directly exposed to the agent via tools. The doc files that reference these models (e.g. `doc/storage/jsonl-format.md`, `doc/config/engine-yaml.md`) are written in the tasks that implement the features using these models.

---

## Quality Check

```bash
./gradlew common:ktlintCheck common:detekt
./gradlew common:jvmTest common:nativeTest  # или linuxX64Test на macOS dev
```
