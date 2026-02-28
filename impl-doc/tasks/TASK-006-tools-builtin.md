# TASK-006 — Engine: Built-in Tools

**Phase**: 5
**Priority**: P0
**Dependencies**: TASK-005
**Est. LOC**: ~1000
**Design refs**: [§6.4 Built-in Tools](../design/klaw-design-v0_4.md#64-встроенные-tools-in-process), [§6.3 Skills Progressive Disclosure](../design/klaw-design-v0_4.md#63-progressive-disclosure)

---

## Summary

Реализовать все встроенные инструменты Engine, вызываемые LLM через function calling. Инструменты выполняются in-process, имеют прямой доступ к внутренним подсистемам.

---

## Goals

Реализовать следующие tool groups:

| Группа | Инструменты |
|--------|-------------|
| Память | `memory_search`, `memory_save`, `memory_core_get`, `memory_core_update`, `memory_core_delete` |
| Файлы | `file_read`, `file_write`, `file_list` |
| Документация | `docs_search`, `docs_read`, `docs_list` |
| Skills | `skill_load`, `skill_list` |
| Расписание | `schedule_list`, `schedule_add`, `schedule_remove` |
| Субагенты | `subagent_spawn` |
| Утилиты | `current_time`, `send_message` |

---

## Implementation Details

### Tool Registry

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/tools/

@Singleton
class ToolRegistry(
    private val memoryTools: MemoryTools,
    private val fileTools: FileTools,
    private val docsTools: DocsTools,
    private val skillTools: SkillTools,
    private val scheduleTools: ScheduleTools,
    private val subagentTools: SubagentTools,
    private val utilityTools: UtilityTools,
) {
    // Возвращает список ToolDef для включения в LLM запрос
    fun getToolDefinitions(): List<ToolDef>

    // Выполняет tool call по имени
    suspend fun execute(call: ToolCall): ToolResult
}
```

### Memory Tools

```kotlin
@Singleton
class MemoryTools(
    private val memoryService: MemoryService,  // TASK-007
    private val coreMemory: CoreMemoryService,
) {
    // memory_search: гибридный поиск (sqlite-vec + FTS5)
    // Параметры: query: String, topK: Int? = 10
    // Возвращает список релевантных чанков с источником и датой
    suspend fun search(query: String, topK: Int = 10): String

    // memory_save: сохранить факт в архивную память
    // Параметры: content: String, source: String? = "manual"
    // Чанкует и индексирует через ONNX embeddings
    suspend fun save(content: String, source: String = "manual"): String

    // memory_core_get: прочитать текущее core_memory.json
    suspend fun coreGet(): String

    // memory_core_update: обновить ключ в core_memory.json
    // Параметры: section: "user"|"agent", key: String, value: String
    suspend fun coreUpdate(section: String, key: String, value: String): String

    // memory_core_delete: удалить ключ из core_memory.json
    // Параметры: section: "user"|"agent", key: String
    suspend fun coreDelete(section: String, key: String): String
}
```

### File Tools

```kotlin
@Singleton
class FileTools(
    private val paths: KlawPaths,
    private val config: FilesConfig,
) {
    // file_read: читать файл из $KLAW_WORKSPACE
    // Параметры: path: String, startLine: Int? = null, maxLines: Int? = null
    // Путь ограничен workspace (path traversal protection: не выходить за $KLAW_WORKSPACE)
    suspend fun read(path: String, startLine: Int? = null, maxLines: Int? = null): String

    // file_write: записать или дозаписать файл в $KLAW_WORKSPACE
    // Параметры: path: String, content: String, mode: "overwrite"|"append"
    // Размер ограничен maxFileSizeBytes (1MB по умолчанию)
    suspend fun write(path: String, content: String, mode: String): String

    // file_list: листинг директории в $KLAW_WORKSPACE
    // Параметры: path: String, recursive: Boolean? = false
    suspend fun list(path: String, recursive: Boolean = false): String
}
```

**Path traversal protection**:
```kotlin
fun safePath(userPath: String): Path {
    val resolved = paths.workspace.resolve(userPath).normalize()
    require(resolved.startsWith(paths.workspace)) {
        "Access denied: path outside workspace"
    }
    return resolved
}
```

### Docs Tools

```kotlin
@Singleton
class DocsTools(private val docsService: DocsService) {
    // docs_search: семантический поиск по документации Klaw (vec_docs)
    // Параметры: query: String, topK: Int? = 5
    suspend fun search(query: String, topK: Int = 5): String

    // docs_read: прочитать конкретный раздел документации
    // Параметры: path: String (e.g. "design/klaw-design-v0_4.md#section")
    suspend fun read(path: String): String

    // docs_list: структура документации
    suspend fun list(): String
}
```

### Skills Tools

```kotlin
@Singleton
class SkillTools(private val skillRegistry: SkillRegistry) {
    // skill_load: загрузить полный SKILL.md в контекст
    // Параметры: name: String
    suspend fun load(name: String): String

    // skill_list: список доступных skills с именами и description (из YAML frontmatter)
    suspend fun list(): String
}
```

**SkillRegistry** (discovery при старте Engine):
```kotlin
@Singleton
class SkillRegistry(private val paths: KlawPaths) {
    // При старте: сканировать data/skills/ и workspace/skills/
    // Для каждого skill: читать только YAML frontmatter (name + description)
    // workspace skill имеет приоритет над data skill с тем же именем
    private val skills = mutableMapOf<String, SkillMeta>()

    fun discover()  // при старте Engine
    fun getAll(): List<SkillMeta>
    fun getFullContent(name: String): String  // читает полный SKILL.md on-demand
}
```

### Schedule Tools

```kotlin
@Singleton
class ScheduleTools(private val scheduler: KlawScheduler) {  // TASK-008
    // schedule_list: показать активные cron-задачи
    suspend fun list(): String

    // schedule_add: добавить persistent cron-задачу
    // Параметры: name: String, cron: String, message: String, model: String?, injectInto: String?
    suspend fun add(name: String, cron: String, message: String, model: String?, injectInto: String?): String

    // schedule_remove: удалить задачу по имени
    // Параметры: name: String
    suspend fun remove(name: String): String
}
```

### Subagent Tools

```kotlin
@Singleton
class SubagentTools(private val processor: MessageProcessor) {
    // subagent_spawn: запустить одноразовый субагент немедленно
    // Параметры: name: String, message: String, model: String?, injectInto: String?
    // Возвращает сразу "Субагент запущен" — не ждёт завершения
    suspend fun spawn(name: String, message: String, model: String?, injectInto: String?): String
}
```

### Utility Tools

```kotlin
@Singleton
class UtilityTools(
    private val socketServer: EngineSocketServer,
    private val config: EngineConfig,
) {
    // current_time: текущая дата/время/timezone
    suspend fun currentTime(): String

    // send_message: отправить сообщение в другой канал
    // Параметры: channel: String, chatId: String, text: String
    // Проходит через Gateway (проверка whitelist)
    suspend fun sendMessage(channel: String, chatId: String, text: String): String
}
```

---

## TDD Approach

Тесты **до** реализации.

### Test Suite

**1. Memory tools tests**:
```kotlin
class MemoryToolsTest {
    @Test fun `memory_search returns relevant chunks`()
    @Test fun `memory_search with topK parameter`()
    @Test fun `memory_save stores chunk and indexes it`()
    @Test fun `memory_core_get returns core_memory json`()
    @Test fun `memory_core_update modifies user section`()
    @Test fun `memory_core_update modifies agent section`()
    @Test fun `memory_core_delete removes key`()
    @Test fun `memory_core_delete on non-existent key returns error`()
}
```

**2. File tools tests (security critical)**:
```kotlin
class FileToolsTest {
    @Test fun `file_read reads file from workspace`()
    @Test fun `file_read with startLine and maxLines`()
    @Test fun `file_read REJECTS path outside workspace (path traversal)`()
    @Test fun `file_read REJECTS absolute path outside workspace`()
    @Test fun `file_read REJECTS ../.. traversal`()
    @Test fun `file_write creates new file`()
    @Test fun `file_write append mode`()
    @Test fun `file_write overwrite mode`()
    @Test fun `file_write REJECTS file larger than maxFileSizeBytes`()
    @Test fun `file_write REJECTS path outside workspace`()
    @Test fun `file_list directory listing`()
    @Test fun `file_list recursive listing`()
}
```

**3. Skill tools tests**:
```kotlin
class SkillToolsTest {
    @Test fun `skill_load returns full SKILL_md content`()
    @Test fun `skill_load returns error for unknown skill`()
    @Test fun `skill_list returns all discovered skills`()
    @Test fun `workspace skill takes precedence over data skill`()
    @Test fun `skills discovered from both workspace and data dirs`()
}
```

**4. Schedule tools tests**:
```kotlin
class ScheduleToolsTest {
    @Test fun `schedule_add creates persistent job`()
    @Test fun `schedule_remove deletes job`()
    @Test fun `schedule_list shows all active jobs`()
    @Test fun `schedule_add with invalid cron returns error`()
    @Test fun `schedule_add duplicate name returns error`()
}
```

**5. File security integration tests**:
```kotlin
class FileSecurityTest {
    // Критично: path traversal must be prevented
    @Test fun `path_1 = workspace/../etc_passwd is rejected`()
    @Test fun `path_2 = absolute /etc/passwd is rejected`()
    @Test fun `path_3 = symlink pointing outside workspace is rejected`()
}
```

**6. Tool registry tests**:
```kotlin
class ToolRegistryTest {
    @Test fun `getToolDefinitions returns all tools with descriptions`()
    @Test fun `execute dispatches to correct tool handler`()
    @Test fun `execute unknown tool returns error result`()
    @Test fun `tool definitions are valid JSON schema`()
}
```

---

## Acceptance Criteria

- [ ] Все 17 инструментов реализованы и зарегистрированы
- [ ] `file_read`/`file_write` отклоняют пути вне workspace (path traversal protection)
- [ ] `file_write` отклоняет файлы > `maxFileSizeBytes`
- [ ] `skill_list` возвращает список с `name` + `description` из YAML frontmatter
- [ ] `skill_load` возвращает полный SKILL.md on-demand
- [ ] `memory_search` делегирует в гибридный поиск (TASK-007)
- [ ] `schedule_add`/`schedule_remove` делегирует в Quartz (TASK-008)
- [ ] `send_message` проходит через Gateway (whitelist проверка)
- [ ] Tool definitions корректные JSON Schema для LLM function calling

---

## Constraints

- **Безопасность файлов**: path traversal защита — КРИТИЧНА. Агент не должен читать произвольные файлы хоста
- `file_write` максимум `maxFileSizeBytes` (1MB) из `engine.json`
- `memory_search` и `docs_search` делегируют в Memory System (TASK-007) — в этой задаче только tool interface
- `schedule_add` делегирует в Scheduler (TASK-008) — в этой задаче только tool interface
- `subagent_spawn` не блокирует — сразу возвращает подтверждение запуска
- Все tool descriptions — на русском (основной язык агента)

---

## Documentation Subtask

**Files to create**:

1. `doc/tools/overview.md`
2. `doc/tools/memory.md` (tool interface; implementation details added in TASK-007)
3. `doc/tools/files.md`
4. `doc/tools/docs.md`
5. `doc/tools/skills.md`
6. `doc/tools/subagent.md`
7. `doc/tools/utils.md`
8. `doc/tools/schedule.md` (stub; completed in TASK-008)

All documentation in **English only**.

---

### `doc/tools/overview.md`

- **Memory tools** — `memory_search` (semantic search), `memory_save` (persist a fact), `memory_core_get` (read core memory), `memory_core_update` (write a key to core memory), `memory_core_delete` (remove a key)
- **File tools** — `file_read` (read from workspace), `file_write` (write to workspace), `file_list` (list directory); all paths restricted to `$KLAW_WORKSPACE`
- **Schedule tools** — `schedule_list`, `schedule_add`, `schedule_remove`; see `doc/tools/schedule.md`
- **Skills tools** — `skill_list` (discover), `skill_load` (load full SKILL.md on demand)
- **Code execution** — `code_execute` (Python or bash in Docker sandbox); see `doc/tools/code-execute.md`
- **Documentation tools** — `docs_search`, `docs_read`, `docs_list`
- **Subagent tools** — `subagent_spawn` (fire-and-forget parallel task)
- **Utility tools** — `current_time`, `send_message`
- **Tool call loop protection** — Engine stops after `maxToolCallRounds` (default 10) consecutive tool calls to prevent infinite loops; if a task requires many sequential steps, use `subagent_spawn` to delegate to a separate coroutine

---

### `doc/tools/memory.md`

- **memory_search** — params: `query` (string), `topK` (int, default 10); hybrid search (semantic + full-text); returns chunks with source and date; use when user references past events or requests recalled information
- **memory_save** — params: `content` (string), `source` (string, optional, default "manual"); content is chunked and embedded; use for important facts, decisions, user preferences that should survive session resets
- **memory_core_get** — no params; returns full `core_memory.json`; already in context — call only when the raw JSON format is explicitly needed
- **memory_core_update** — params: `section` ("user" or "agent"), `key` (string), `value` (string); update immediately when the user states a preference, corrects a fact, or teaches a rule
- **memory_core_delete** — params: `section` ("user" or "agent"), `key` (string); returns error if key does not exist
- **Decision guide: when to save vs. when to search** — save: user states a preference, teaches a rule, shares important personal information; search: user says "remember when…", "what did I tell you about…", or context window is insufficient
- *(Implementation details on chunking, embedding, hybrid RRF search — see TASK-007 implementation)*

---

### `doc/tools/files.md`

- **file_read** — params: `path` (string, relative to workspace root), `startLine` (int, optional), `maxLines` (int, optional); paths outside `$KLAW_WORKSPACE` are rejected with "Access denied"; `../` traversal is blocked; example: `file_read(path="notes/todo.md")`
- **file_write** — params: `path` (string), `content` (string), `mode` ("overwrite" or "append"); max file size 1 MB; parent directories are created automatically; `overwrite` replaces the entire file, `append` adds to the end
- **file_list** — params: `path` (string), `recursive` (bool, default false); call before `file_read` when unsure if a file exists
- **Workspace root** — all paths are relative to `$KLAW_WORKSPACE` (typically `~/klaw-workspace`); the agent cannot access files outside this boundary
- **Reading skill scripts** — skill scripts in `workspace/skills/*/scripts/` are readable with `file_read` after calling `skill_load` to get the skill's instructions

---

### `doc/tools/docs.md`

- **docs_search** — params: `query` (string), `topK` (int, default 5); semantic search over all documentation chunks in `vec_docs`; use when unsure about a parameter, format, or behavior; natural language queries work well
- **docs_read** — params: `path` (string, e.g. `"tools/memory.md"`); returns full content of a specific documentation file; use when a `docs_search` result is a snippet and more context is needed
- **docs_list** — no params; returns the full documentation directory structure; use to understand what documentation topics are available
- **When to use** — before using an unfamiliar tool; when a tool call returns an unexpected error; when the user asks about system capabilities; documentation reflects the currently installed version

---

### `doc/tools/skills.md`

- **skill_list** — no params; returns all discovered skills with `name` and `description` from each skill's YAML frontmatter; workspace skills (`$KLAW_WORKSPACE/skills/`) take priority over system skills with the same name
- **skill_load** — params: `name` (string); returns the full `SKILL.md` content as a tool result; follow its instructions for the current task; call only when the task matches the skill — do not preload all skills
- **When to load a skill** — when the task matches a skill's description; loaded content counts toward the context budget; unload by finishing the call (skill content is not persistent across calls)
- **Skills with scripts** — after calling `skill_load`, use `file_read` to access supporting files in `skills/{name}/scripts/` or `skills/{name}/templates/`; use `code_execute` to run them
- **Creating a skill** — create `$KLAW_WORKSPACE/skills/{name}/SKILL.md` with YAML frontmatter (`name:` and `description:` fields); skill appears in `skill_list` after engine restart

---

### `doc/tools/subagent.md`

- **subagent_spawn** — params: `name` (string), `message` (string, full instruction), `model` (string, optional), `injectInto` (string, optional, chatId like "telegram_123456"); returns immediately with confirmation — does NOT wait for the subagent to finish
- **subagent_spawn vs schedule_add** — `subagent_spawn` runs immediately and once; `schedule_add` runs on a cron schedule repeatedly; use `subagent_spawn` to delegate a task right now, `schedule_add` for recurring automation
- **Subagent context** — each subagent gets: same system prompt and core memory (read-only), last 5 messages from its own isolated history; it does NOT see the main chat's recent messages
- **Silent responses** — if the subagent finds nothing to report, it should return `{"silent": true, "reason": "..."}` — this prevents an empty notification to the user; if `injectInto` is null, the result is only logged regardless
- **Use cases** — lengthy analysis that would exceed `maxToolCallRounds`; parallel research while the main session continues; background checks that should not block the current response

---

### `doc/tools/utils.md`

- **current_time** — no params; returns current date, time, and timezone; call at the start of any scheduling or time-sensitive task
- **send_message** — params: `channel` (string, e.g. "telegram"), `chatId` (string, e.g. "telegram_123456"), `text` (string); sends a message to a specific user; the `chatId` must be in Gateway's `allowedChatIds` whitelist (see `doc/config/gateway-yaml.md`); rejected chatIds return an error as a tool result, not an exception

---

### `doc/tools/schedule.md` (stub — completed in TASK-008)

```markdown
# Schedule Tools

Schedule tools manage persistent cron tasks via the Quartz scheduler.
See `doc/scheduling/how-scheduling-works.md` for the full scheduling model.

Full documentation will be added in the scheduler implementation phase.
```

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt
./gradlew engine:test
```
