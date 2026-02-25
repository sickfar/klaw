# TASK-005 — Engine Core: Main Loop & Context Builder

**Phase**: 4
**Priority**: P0
**Dependencies**: TASK-002, TASK-003, TASK-004
**Est. LOC**: ~900
**Design refs**: [§2.4 Engine](../design/klaw-design-v0_4.md#24-описание-компонентов), [§3.2 Session Model](../design/klaw-design-v0_4.md#32-модель-данных-chat--segment--session), [§3.3 Context Window](../design/klaw-design-v0_4.md#33-сборка-контекстного-окна), [§6.6 Commands](../design/klaw-design-v0_4.md#66-команды)

---

## Summary

Реализовать основной цикл обработки сообщений Engine: приём, debounce, сборка контекста, LLM вызов, tool call loop, управление сессиями, команды. Это сердце системы — все остальные компоненты оркестрируются здесь.

---

## Goals

1. Основной цикл: receive → debounce → context → LLM → tool call loop → respond
2. Debounce per `chat_id` (configurable `processing.debounceMs: 1500`)
3. Context builder: system prompt + core memory + summary + sliding window + tools
4. Tool call loop с защитой от зацикливания (`maxToolCallRounds: 10`)
5. Session management (`klaw.db` таблица `sessions`, восстановление из JSONL)
6. Rate limiting: семафор `maxConcurrentLlm: 2`, interactive > subagent priority
7. Субагенты: параллельные корутины, изолированный контекст, `subagentWindow`
8. Command handler: `/new`, `/model`, `/models`, `/memory`, `/status`, `/help`
9. Silent-логика для субагентов (`{"silent": true}`)
10. Graceful shutdown

---

## Implementation Details

### Message Processing Pipeline

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/

@Singleton
class MessageProcessor(
    private val sessionManager: SessionManager,
    private val contextBuilder: ContextBuilder,
    private val llmRouter: LlmRouter,
    private val toolExecutor: ToolExecutor,
    private val socketServer: EngineSocketServer,
    private val config: EngineConfig,
) {
    private val debounceBuffers = ConcurrentHashMap<String, DebounceBuffer>()
    private val llmSemaphore = Semaphore(config.processing.maxConcurrentLlm)

    // Вызывается из SocketServer при получении сообщения от Gateway
    suspend fun handleInbound(message: InboundSocketMessage)

    // Вызывается из Scheduler (in-process)
    suspend fun handleScheduledMessage(message: ScheduledMessage)

    // Основной pipeline (после debounce)
    private suspend fun processMessage(
        chatId: String,
        messages: List<InboundSocketMessage>,
        session: Session,
        isSubagent: Boolean,
    ): ProcessResult
}
```

### Debounce

```kotlin
class DebounceBuffer(
    private val debounceMs: Long,
    private val onFlush: suspend (List<InboundSocketMessage>) -> Unit,
) {
    // Принимает сообщение, запускает/рестартует таймер
    // По истечении debounceMs — вызывает onFlush со всеми накопленными сообщениями
    suspend fun add(message: InboundSocketMessage)
}
```

**Поведение debounce** (из дизайна §2.4):
```
1. Сообщение → записать в klaw.db → debounce timer start/restart для chat_id
2. Пока идёт debounce → новые сообщения накапливаются в буфере
3. Timer истёк (1500ms без новых от chat_id) → склеить → в очередь на LLM-слот
4. Interactive > subagent приоритет в очереди
```

### Context Builder

```kotlin
@Singleton
class ContextBuilder(
    private val workspaceLoader: WorkspaceLoader,
    private val coreMemory: CoreMemoryService,
    private val messageRepository: MessageRepository,
    private val summaryService: SummaryService,
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry,
    private val config: EngineConfig,
) {
    // Собирает контекст по дизайну §3.3:
    // 1. System prompt (~500 tokens)
    // 2. Core Memory (~500 tokens)
    // 3. Last summary (~500 tokens)
    // 4. Sliding window last N messages (remaining budget)
    // 5. Available tools/skills (~500 tokens)
    suspend fun buildContext(
        session: Session,
        pendingMessages: List<String>,
        isSubagent: Boolean,
    ): List<LlmMessage>
}
```

**Context budget calculation**:
```
contextBudget = models[session.model].contextBudget ?: context.defaultBudgetTokens
fixedTokens = systemPrompt + coreMemory + summary + tools  // ~2000 tokens
remaining = contextBudget * 0.9 - fixedTokens  // 10% safety margin
slidingWindowN = min(context.slidingWindow, messagesfitting(remaining))
```

### Session Manager

```kotlin
@Singleton
class SessionManager(private val db: KlawDatabase) {
    // Создаёт или загружает сессию для chat_id
    suspend fun getOrCreate(chatId: String, defaultModel: String): Session

    // Обновляет модель сессии (/model команда)
    suspend fun updateModel(chatId: String, model: String)

    // Сбрасывает сегмент (/new — записывает session_break маркер)
    suspend fun resetSegment(chatId: String)
}

data class Session(
    val chatId: String,
    val model: String,
    val segmentStart: String,  // id первого сообщения текущего сегмента
    val createdAt: Instant,
)
```

### Tool Call Loop

```kotlin
private suspend fun toolCallLoop(
    context: MutableList<LlmMessage>,
    session: Session,
): LlmResponse {
    var rounds = 0
    while (rounds < config.processing.maxToolCallRounds) {
        val response = llmRouter.chat(LlmRequest(messages = context), session.model)
        rounds++
        if (response.toolCalls.isNullOrEmpty()) return response
        // Выполнить все tool calls, добавить результаты в контекст
        val results = toolExecutor.executeAll(response.toolCalls!!)
        context.add(LlmMessage(role = "assistant", content = null, toolCalls = response.toolCalls))
        results.forEach { context.add(LlmMessage(role = "tool", content = it.content, toolCallId = it.callId)) }
    }
    // Защита от зацикливания
    throw ToolCallLoopException("Достигнут лимит вызовов инструментов ($maxToolCallRounds)")
}
```

### Command Handler

```kotlin
@Singleton
class CommandHandler(
    private val sessionManager: SessionManager,
    private val coreMemory: CoreMemoryService,
    private val config: EngineConfig,
) {
    suspend fun handle(message: CommandSocketMessage, session: Session): String {
        return when (message.command) {
            "new" -> handleNew(message.chatId, session)
            "model" -> handleModel(message.chatId, message.args, session)
            "models" -> listModels()
            "memory" -> showMemory()
            "status" -> showStatus()
            "help" -> showHelp()
            else -> "Неизвестная команда: /${message.command}"
        }
    }
}
```

### Subagent Spawning

```kotlin
// Запускается из tool_executor при вызове subagent_spawn tool
// Или из Scheduler (handleScheduledMessage)
suspend fun spawnSubagent(
    name: String,
    message: String,
    model: String?,
    injectInto: String?,
) {
    coroutineScope {
        launch {
            // Изолированный контекст: subagentWindow вместо slidingWindow
            // Собственная модель из Quartz JobData или routing.tasks.subagent
            // Silent логика: парсим JSON с "silent" полем
            // Если !silent && injectInto != null → отправить в Gateway
        }
    }
}
```

### Graceful Shutdown

```kotlin
// При systemctl stop klaw-engine:
// 1. Quartz shutdown(waitForJobsToComplete=true)
// 2. Перестать принимать новые сообщения
// 3. Отправить {"type":"shutdown"} в Gateway
// 4. Ждать завершения текущих LLM вызовов (timeout: 60s)
// 5. Закрыть SQLite соединения
// 6. Удалить engine.sock
```

---

## TDD Approach

Тесты **до** реализации. Используем mock LLM (WireMock или MockK для LlmRouter).

### Test Suite

**1. Debounce tests**:
```kotlin
class DebounceTest {
    @Test fun `single message processed after debounceMs`()
    @Test fun `multiple messages from same chatId merged`()
    @Test fun `messages from different chatIds processed independently`()
    @Test fun `timer restarts on new message before timeout`()
    @Test fun `messages processed in order`()
}
```

**2. Context builder tests**:
```kotlin
class ContextBuilderTest {
    @Test fun `context includes system prompt + core memory + sliding window`()
    @Test fun `sliding window respects contextBudget`()
    @Test fun `sliding window only shows messages from current segment (after session_break)`()
    @Test fun `subagent uses subagentWindow instead of slidingWindow`()
    @Test fun `10% safety margin applied to contextBudget`()
    @Test fun `summary included when available`()
    @Test fun `tools descriptions included in context`()
}
```

**3. Tool call loop tests**:
```kotlin
class ToolCallLoopTest {
    @Test fun `single tool call processed correctly`()
    @Test fun `multiple sequential tool calls`()
    @Test fun `loop stops when LLM returns no tool calls`()
    @Test fun `ToolCallLoopException thrown after maxToolCallRounds`()
    @Test fun `tool results added to context for next round`()
}
```

**4. Session management tests**:
```kotlin
class SessionManagerTest {
    @Test fun `creates session with routing_default model`()
    @Test fun `returns existing session for known chatId`()
    @Test fun `updateModel changes session model`()
    @Test fun `resetSegment writes session_break marker`()
    @Test fun `new segment starts after resetSegment`()
}
```

**5. Command handler tests**:
```kotlin
class CommandHandlerTest {
    @Test fun `slash_new resets segment and returns confirmation`()
    @Test fun `slash_model without args shows current model`()
    @Test fun `slash_model with valid model switches model`()
    @Test fun `slash_model with invalid model returns error`()
    @Test fun `slash_models lists all configured models`()
    @Test fun `slash_status returns uptime and session info`()
}
```

**6. Silent subagent logic**:
```kotlin
class SubagentSilentLogicTest {
    @Test fun `response with silent=true not sent to Gateway`()
    @Test fun `response without silent field sent to Gateway`()
    @Test fun `response with silent=false sent to Gateway`()
    @Test fun `non-JSON response treated as not-silent (safe default)`()
    @Test fun `parse failure treated as not-silent`()
}
```

**7. Rate limiting tests**:
```kotlin
class RateLimitingTest {
    @Test fun `interactive requests get priority over subagents`()
    @Test fun `maxConcurrentLlm limit respected`()
}
```

**8. Integration test (mock LLM)**:
```kotlin
class MessageProcessorIntegrationTest {
    // Full pipeline: inbound → context → mock LLM → tool call → outbound
    @Test fun `simple message processed end-to-end`()
    @Test fun `tool call returned by LLM is executed`()
    @Test fun `response sent to Gateway via socket`()
}
```

---

## Acceptance Criteria

- [ ] Debounce склеивает сообщения от одного chat_id за 1500ms
- [ ] Контекст собирается по схеме §3.3 с правильными budget расчётами
- [ ] Tool call loop обрабатывает инструменты и защищает от зацикливания
- [ ] `/new` создаёт `session_break` маркер, новая сессия не видит старые сообщения
- [ ] Субагент использует `subagentWindow` и изолированный контекст
- [ ] Silent субагенты не отправляют сообщения в Gateway
- [ ] Graceful shutdown: ожидает завершение текущих LLM вызовов

---

## Constraints

- Engine — **единственный владелец** `klaw.db` и `scheduler.db`
- Scheduler вызывает Engine **in-process** (не через socket)
- Rate limiting: interactive > subagent — через PriorityChannel или приоритетную очередь
- Не добавлять бизнес-логику tools в этот файл — только оркестрация
- `maxConcurrentLlm: 2` — не прерывать mid-flight LLM вызов при достижении лимита

---

## Documentation Subtask

**Files to create**:

1. `doc/index.md` — complete version (replaces TASK-001 stub)
2. `doc/getting-started.md`
3. `doc/commands/slash-commands.md`
4. `doc/memory/how-memory-works.md`
5. `doc/memory/recall-memory.md`

All documentation in **English only**.

---

### `doc/index.md`

- **What is Klaw** — lightweight AI agent on Raspberry Pi 5; two-process architecture (Gateway handles messaging, Engine handles LLM, memory, scheduling); tools-based operation
- **How to use these docs** — `docs_search "query"` for any operational question; `docs_list` to browse all topics; `docs_read "path/file.md"` for a full file; docs are indexed semantically — natural language queries work well
- **What I can do** — numbered list: (1) remember facts across sessions via memory tools, (2) read and write files in workspace, (3) run code in Docker sandbox, (4) schedule recurring tasks, (5) load skill documentation on demand, (6) spawn subagents for parallel work, (7) send messages to other channels
- **When to consult docs** — before using a tool with unfamiliar parameters; when a tool call returns an error; when the user asks about a capability; when setting up a scheduled task

---

### `doc/getting-started.md`

- **My identity** — loaded from workspace: `SOUL.md` (character), `IDENTITY.md` (name and tone), `AGENTS.md` (instructions); already in system prompt — no action needed to load them
- **What is in my context right now** — five layers assembled before every LLM call: (1) system prompt ~500 tokens, (2) core memory ~500 tokens, (3) last summary ~500 tokens, (4) sliding window of recent messages ~3000 tokens, (5) tool descriptions ~500 tokens
- **What persists between conversations** — core memory (always loaded), archival memory (searchable via `memory_search`), conversation log (automatic), scheduled tasks (Quartz)
- **What resets with /new** — sliding window and last summary reset; core memory, archival memory, current model, and full conversation log are NOT affected
- **Recommended first actions** — call `memory_core_get` to review known user facts; call `schedule_list` to see active cron tasks; call `skill_list` to see available skills

---

### `doc/commands/slash-commands.md`

- **How commands arrive** — slash commands arrive as `type: "command"` messages; the Engine handles them directly without LLM; the agent does not respond to commands — the Engine already replied; the agent only sees the resulting state change (e.g. new session segment after `/new`)
- **/new** — resets sliding window and last summary; starts a new segment; core memory, archival memory, current model, and the full conversation log are preserved; session log is append-only and never erased
- **/model** — without argument: shows current session model; with argument `provider/model-id` (e.g. `/model deepseek/deepseek-chat`): switches model for this session; model must be defined in `engine.yaml` under `models:`
- **/models** — lists all models configured in `engine.yaml` with their `contextBudget` values; useful before recommending a model switch to the user
- **/memory** — displays `core_memory.json` to the user; agent can also call `memory_core_get` programmatically
- **/status** — shows uptime, current chat model, segment start, and LLM queue depth
- **/help** — lists all available commands as configured in `engine.yaml` under `commands:`

---

### `doc/memory/how-memory-works.md`

- **Three memory tiers** — Core Memory (always in context, structured JSON), Archival Memory (on-demand via `memory_search`, semantic chunks in sqlite-vec), Recall Memory (automatic sliding window of recent messages)
- **Context assembly for every LLM call** — five layers in order: system prompt (~500 tokens), core memory (~500 tokens), last summary (~500 tokens), sliding window last N messages (~3000 tokens), tool descriptions (~500 tokens); total ~5000 tokens with default settings
- **Context budget** — each model has `contextBudget` in `engine.yaml`; Engine uses 90% of budget (safety margin for approximate token counting); sliding window shrinks to fit remaining budget after fixed layers are placed
- **Why archival memory is on-demand** — pre-loading all archival results every call costs ~1000 extra tokens even for irrelevant queries; the agent calls `memory_search` only when needed, leaving more space for recent messages
- **Segments and /new** — a segment is the conversation portion since the last `/new`; sliding window shows only messages from the current segment; `memory_search` can still retrieve facts from any segment

---

### `doc/memory/recall-memory.md`

- **What recall memory is** — the last N messages from the current segment, automatically included in every context; N is `slidingWindow` from `engine.yaml` (default: 20 messages)
- **Segment boundary** — only messages since the last `/new` command are in the sliding window; messages before `/new` are in the JSONL log but not in context
- **What counts as a message** — user messages, assistant responses, tool calls, and tool results all count as individual messages; a single tool call + result pair uses two window slots
- **When the window shrinks** — if 20 messages exceed the remaining context budget after fixed sections, the window shrinks silently to fit; no notification is sent to the user
- **Accessing messages outside the window** — use `memory_search` to retrieve content from older messages; conversation summaries (when generated) are also indexed in archival memory and findable via `memory_search`

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt
./gradlew engine:test
```
