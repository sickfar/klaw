# TASK-008 — Scheduler + OpenClaw Workspace Loader

**Phase**: 7
**Priority**: P0
**Dependencies**: TASK-005, TASK-007
**Est. LOC**: ~800
**Design refs**: [§2.4 Scheduler](../design/klaw-design-v0_4.md#24-описание-компонентов), [§4 OpenClaw Compatibility](../design/klaw-design-v0_4.md#4-совместимость-с-openclaw-workspace), [§11 Testing Quartz](../design/klaw-design-v0_4.md#11-тестирование), [§12 Risks Quartz+SQLite](../design/klaw-design-v0_4.md#12-риски-и-митигация)

---

## Summary

Реализовать два связанных компонента:
1. **Scheduler** — Quartz с SQLite JDBC delegate, парсер HEARTBEAT.md, persistent cron задачи
2. **OpenClaw Workspace Loader** — загрузка всех workspace файлов (SOUL/IDENTITY/AGENTS/USER.md), инициализация core memory, индексация MEMORY.md в sqlite-vec, discovery skills

Оба компонента — модули Engine, работают in-process.

---

## Goals

### Scheduler
1. Кастомный `SQLiteDelegate` (совместимость с SQLite: `BEGIN IMMEDIATE` вместо `SELECT FOR UPDATE`)
2. Quartz JDBC JobStore с `scheduler.db`, single-node режим (`isClustered=false`)
3. `ScheduledMessageJob` → вызов `engine.handleScheduledMessage()` (in-process)
4. Операции: `addSchedule`, `removeSchedule`, `listSchedules`
5. HEARTBEAT.md парсер → импорт в Quartz jobs
6. Misfire recovery при рестарте (misfire handling: `FireAndProceed`)

### Workspace Loader
1. `buildSystemPrompt`: SOUL.md + IDENTITY.md + AGENTS.md + TOOLS.md + USER.md
2. Инициализация `core_memory.json` из USER.md
3. Индексация MEMORY.md + `memory/*.md` → чанкинг → sqlite-vec (через MemoryService)
4. Skills discovery: `$KLAW_WORKSPACE/skills/` + `$XDG_DATA_HOME/klaw/skills/`

---

## Implementation Details

### SQLiteDelegate (Custom Quartz Delegate)

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/scheduler/

class SQLiteDelegate : StdJDBCDelegate() {
    // SQLite не поддерживает SELECT ... FOR UPDATE → заменить на BEGIN IMMEDIATE
    // Переопределить методы где StdJDBCDelegate использует row-level locking

    override fun selectJobForLock(conn: Connection, key: JobKey): JobDetail? {
        conn.createStatement().execute("BEGIN IMMEDIATE")
        return super.selectJobForLock(conn, key)
    }
    // ... другие переопределения
}
```

**Quartz конфигурация для SQLite**:
```properties
org.quartz.jobStore.class=org.quartz.impl.jdbcjobstore.JobStoreTX
org.quartz.jobStore.driverDelegateClass=io.github.klaw.engine.scheduler.SQLiteDelegate
org.quartz.jobStore.dataSource=klawScheduler
org.quartz.jobStore.tablePrefix=QRTZ_
org.quartz.jobStore.isClustered=false
org.quartz.dataSource.klawScheduler.driver=org.sqlite.JDBC
org.quartz.dataSource.klawScheduler.URL=jdbc:sqlite:{schedulerDbPath}
org.quartz.threadPool.threadCount=2
```

**Без `QRTZ_LOCKS`** (single-node): стандартная таблица Quartz locks не нужна в single-node режиме. Quartz автоматически создаёт все QRTZ_* таблицы при первом старте.

### ScheduledMessageJob

```kotlin
class ScheduledMessageJob : Job {
    @Inject lateinit var engine: MessageProcessor  // in-process, не через socket

    override fun execute(context: JobExecutionContext) {
        val data = context.mergedJobDataMap
        val message = ScheduledMessage(
            name = data.getString("name"),
            message = data.getString("message"),
            model = data.getString("model"),
            injectInto = data.getString("injectInto"),
        )
        // Прямой вызов Engine (in-process) — не через Unix socket
        runBlocking { engine.handleScheduledMessage(message) }
    }
}

data class ScheduledMessage(
    val name: String,
    val message: String,
    val model: String?,
    val injectInto: String?,  // chatId или null
)
```

### KlawScheduler

```kotlin
@Singleton
class KlawScheduler(
    private val quartzScheduler: Scheduler,  // Quartz
    private val config: EngineConfig,
) {
    fun start()
    fun shutdown(waitForJobsToComplete: Boolean)

    fun addSchedule(
        name: String,
        cron: String,
        message: String,
        model: String? = null,
        injectInto: String? = null,
    )

    fun removeSchedule(name: String)

    fun listSchedules(): List<ScheduleInfo>

    data class ScheduleInfo(
        val name: String,
        val cron: String,
        val message: String,
        val model: String?,
        val injectInto: String?,
        val nextFireTime: Instant?,
        val previousFireTime: Instant?,
    )
}
```

### HEARTBEAT.md Parser

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/workspace/

class HeartbeatParser {
    // Парсит HEARTBEAT.md в формате OpenClaw в список задач Quartz
    // Стандартный формат HEARTBEAT.md:
    // ```
    // ## Название задачи
    // - Cron: 0 0 9 * * ?
    // - Message: Проверь почту и сообщи о важных письмах
    // - Model: glm/glm-4-plus
    // - InjectInto: telegram_123456
    // ```
    fun parse(content: String): List<HeartbeatTask>

    data class HeartbeatTask(
        val name: String,
        val cron: String,
        val message: String,
        val model: String? = null,
        val injectInto: String? = null,
    )
}
```

### WorkspaceLoader

```kotlin
@Singleton
class WorkspaceLoader(
    private val paths: KlawPaths,
    private val memory: MemoryService,
    private val coreMemory: CoreMemoryService,
    private val scheduler: KlawScheduler,
    private val skillRegistry: SkillRegistry,
) {
    // Загружает при старте Engine (или при смене workspace)
    suspend fun load(workspacePath: Path = paths.workspace)

    // Собирает system prompt из workspace файлов
    // Порядок: SOUL.md → IDENTITY.md → AGENTS.md → TOOLS.md → USER.md
    fun buildSystemPrompt(workspacePath: Path): String

    // Инициализирует core_memory.json из USER.md
    fun initCoreMemoryFromUserMd(userMdPath: Path)

    // Индексирует MEMORY.md и memory/*.md в sqlite-vec
    suspend fun indexMemoryFiles(workspacePath: Path)

    // Импортирует HEARTBEAT.md задачи в Quartz
    fun importHeartbeat(workspacePath: Path)

    // Обнаруживает skills из workspace/skills/ и data/skills/
    fun discoverSkills(workspacePath: Path)
}
```

---

## TDD Approach

Тесты **до** реализации.

### Test Suite

**1. HEARTBEAT.md parser tests**:
```kotlin
class HeartbeatParserTest {
    @Test fun `parses basic task with cron and message`()
    @Test fun `parses task with optional model`()
    @Test fun `parses task with injectInto`()
    @Test fun `parses multiple tasks from single file`()
    @Test fun `handles empty HEARTBEAT_md`()
    @Test fun `handles malformed entry gracefully`()
    @Test fun `parses OpenClaw standard format`()
}
```

**2. Quartz + SQLite integration tests** (с реальным SQLite):
```kotlin
class QuartzSqliteTest {
    // Тесты с реальным Quartz + temp SQLite файлом

    @Test fun `job scheduled and executes at expected time`()
    @Test fun `job persists after scheduler restart`()
    @Test fun `addSchedule creates persistent job`()
    @Test fun `removeSchedule deletes job`()
    @Test fun `listSchedules returns all active jobs`()
    @Test fun `duplicate name throws exception`()
}
```

**3. Misfire recovery tests (критично — §11)**:
```kotlin
class MisfireRecoveryTest {
    // FIRE_AND_PROCEED: если пропустили N тригеров — выполнить один раз
    @Test fun `misfire after 2 hours still fires once on recovery`()
    @Test fun `misfire after 4 scheduled triggers fires once`()
    @Test fun `no misfire if within misfire threshold`()
}
```

**4. SQLiteDelegate tests**:
```kotlin
class SQLiteDelegateTest {
    @Test fun `BEGIN IMMEDIATE used instead of SELECT FOR UPDATE`()
    @Test fun `concurrent access doesn't deadlock`()
    @Test fun `transaction rollback on error`()
}
```

**5. WorkspaceLoader tests**:
```kotlin
class WorkspaceLoaderTest {
    @Test fun `buildSystemPrompt includes SOUL_md content`()
    @Test fun `buildSystemPrompt includes IDENTITY_md content`()
    @Test fun `buildSystemPrompt includes AGENTS_md content`()
    @Test fun `buildSystemPrompt handles missing optional files`()
    @Test fun `initCoreMemoryFromUserMd populates user section`()
    @Test fun `importHeartbeat creates Quartz jobs from HEARTBEAT_md`()
    @Test fun `discoverSkills finds workspace and data skills`()
    @Test fun `workspace skill overrides data skill with same name (logs warning)`()
    @Test fun `missing workspace dir handled gracefully`()
}
```

**6. Memory indexing tests**:
```kotlin
class WorkspaceMemoryIndexingTest {
    @Test fun `MEMORY_md chunked and indexed in sqlite-vec`()
    @Test fun `memory daily logs indexed`()
    @Test fun `reindexing clears old chunks before reindexing`()
}
```

**7. System prompt order test**:
```kotlin
class SystemPromptOrderTest {
    @Test fun `SOUL section appears before IDENTITY`()
    @Test fun `IDENTITY appears before AGENTS`()
    @Test fun `AGENTS appears before USER`()
    @Test fun `user facts from core_memory_json take precedence over USER_md on conflict`()
}
```

---

## Acceptance Criteria

- [ ] Quartz JDBC JobStore работает с `scheduler.db` (SQLite)
- [ ] `SQLiteDelegate` предотвращает deadlock при single-node операциях
- [ ] Задачи переживают рестарт Engine (persistent в `scheduler.db`)
- [ ] Misfire recovery: пропущенные задачи выполняются один раз при рестарте
- [ ] HEARTBEAT.md парсится в Quartz jobs корректно
- [ ] `buildSystemPrompt` выдаёт файлы в правильном порядке
- [ ] MEMORY.md и daily logs индексируются в sqlite-vec при старте
- [ ] Workspace skills discovery работает из обеих директорий

---

## Constraints

- Scheduler — **модуль Engine**, не отдельный процесс. Вызов Engine через internal function calls
- Engine — **единственный владелец** `scheduler.db`
- `isClustered=false` — single-node Quartz, без `QRTZ_LOCKS` таблицы
- Quartz threads (2) для job execution (threadPool.threadCount=2)
- HEARTBEAT.md — парсим без модификации файла (read-only для OpenClaw совместимости)
- Workspace Loader запускается при старте Engine, не при каждом сообщении (system prompt кэшируется)

---

## Documentation Subtask

**Files to create / complete**:

1. `doc/tools/schedule.md` — complete version (replaces TASK-006 stub)
2. `doc/scheduling/how-scheduling-works.md`
3. `doc/scheduling/heartbeat.md`
4. `doc/scheduling/cron-format.md`
5. `doc/workspace/workspace-files.md`
6. `doc/workspace/openclaw-compatibility.md`

All documentation in **English only**.

---

### `doc/tools/schedule.md` — complete version

- **schedule_list** — no params; returns all active Quartz jobs: name, cron expression, message, model, inject_into, next fire time, previous fire time
- **schedule_add** — params: `name` (string, unique), `cron` (string, Quartz 7-field format), `message` (string, instruction sent to the subagent), `model` (string, optional), `injectInto` (string, optional, chatId); tasks persist across engine restarts; duplicate names return an error; see `doc/scheduling/cron-format.md` for cron syntax
- **schedule_remove** — params: `name` (string); permanently removes the task from Quartz
- **inject_into explained** — if set to a chatId (e.g. `"telegram_123456"`), the subagent result is sent to that user via Gateway; if the result JSON contains `{"silent": true}`, it is logged but NOT sent; if `injectInto` is null, the result is only logged
- **Model selection** — if `model` is omitted, the task uses `routing.tasks.subagent` from `engine.json` (default: `glm/glm-4-plus`); use cheaper or local models (e.g. `ollama/qwen3:8b`) for routine checks to save cost

---

### `doc/scheduling/how-scheduling-works.md`

- **Architecture** — Quartz runs inside the Engine process (not separate); tasks stored in `scheduler.db` (SQLite); tasks survive engine restarts (JDBC persistence); no separate scheduler process to manage
- **What happens when a task fires** — Quartz triggers the job; Engine spawns a subagent coroutine with isolated context; the subagent runs the configured message as an LLM call with the configured model; result is optionally delivered to a user via `injectInto`
- **Subagent context for scheduled tasks** — gets: shared system prompt, shared core memory (read-only), last 5 messages from its own scheduler channel log; does NOT see the main chat's recent messages; this is intentional — heartbeat tasks should not depend on conversation state
- **Delivering results to the user** — if `injectInto` is set and the result does not contain `{"silent": true}`, Engine sends the result to Gateway which delivers it to the user; silent results are only logged
- **Misfire recovery** — if Engine was stopped and missed scheduled fires, Quartz fires the task once on startup (FireAndProceed policy); it does not replay all missed triggers
- **Managing tasks** — add via `schedule_add` tool or `klaw schedule add` CLI; remove via `schedule_remove` or `klaw schedule remove`; view with `schedule_list`; tasks can also be imported from `HEARTBEAT.md` at startup

---

### `doc/scheduling/heartbeat.md`

- **What HEARTBEAT.md is** — workspace file at `$KLAW_WORKSPACE/HEARTBEAT.md`; parsed at engine startup and imported as Quartz jobs; OpenClaw-compatible format; read-only — Klaw does not modify it
- **Format** — each task is a level-2 Markdown heading with bullet-point fields:
  ```markdown
  ## Task Name
  - Cron: 0 0 9 * * ?
  - Message: Check email and report important messages
  - Model: glm/glm-4-plus
  - InjectInto: telegram_123456
  ```
  `Model` and `InjectInto` are optional.
- **Import behavior** — parsed once at startup; changes to the file do NOT take effect until engine restart; to add tasks immediately without restart, use `schedule_add` tool
- **HEARTBEAT.md vs schedule_add** — `HEARTBEAT.md` is the declarative config (can be under version control with the workspace); `schedule_add` is the runtime API; both produce the same Quartz jobs
- **Updating a HEARTBEAT.md task at runtime** — call `schedule_remove(name)` then `schedule_add(...)` with updated parameters; or edit `HEARTBEAT.md` and restart the engine
- **Silent pattern** — include in the `Message` field: `"If nothing requires user attention, respond with JSON: {\"silent\": true, \"reason\": \"what was checked\"}"`; this prevents empty notifications when the heartbeat finds nothing

---

### `doc/scheduling/cron-format.md`

- **Quartz cron — 7 fields** — `seconds minutes hours day-of-month month day-of-week [year]`; different from standard Unix cron (which has 5 fields and no seconds field); all 7 fields required except year
- **Field ranges** — seconds: 0–59, minutes: 0–59, hours: 0–23, day-of-month: 1–31, month: 1–12 or JAN–DEC, day-of-week: 1–7 or SUN–SAT (1=Sunday), year: optional 1970–2099
- **Special characters** — `*` any value; `?` no specific value (use in day-of-month OR day-of-week, not both); `/` step (e.g. `0/30` = every 30 starting at 0); `,` list; `-` range
- **Common examples**:
  - Every hour: `0 0 * * * ?`
  - Every 30 minutes: `0 0/30 * * * ?`
  - Daily at 9 AM: `0 0 9 * * ?`
  - Weekdays at 8 AM: `0 0 8 ? * MON-FRI`
  - Every Sunday midnight: `0 0 0 ? * SUN`
  - First of month at noon: `0 0 12 1 * ?`
- **Validation** — invalid cron expressions cause `schedule_add` to return an error; verify with `schedule_list` after adding to confirm the next fire time is correct

---

### `doc/workspace/workspace-files.md`

- **SOUL.md** — agent's philosophy, values, and character; loaded into system prompt under `## Soul`; defines the agent's fundamental identity
- **IDENTITY.md** — agent's name, visual presentation, and tone; loaded into system prompt under `## Identity`
- **AGENTS.md** — operational instructions: priorities, rules, behavioral boundaries, memory usage guidance; loaded under `## Instructions`; the most important file for determining behavior
- **TOOLS.md** — environment notes: SSH host addresses, local service URLs, API quirks; loaded under `## Environment Notes`; not for tool definitions (tools are built-in)
- **USER.md** — information about the user; on first startup populates `core_memory.json["user"]`; after that `core_memory.json` is the source of truth; runtime updates via `memory_core_update` are not written back to `USER.md`
- **MEMORY.md** — long-term memory notes; chunked and indexed in sqlite-vec at startup; becomes searchable via `memory_search`; append new facts here for durable long-term storage across restarts
- **HEARTBEAT.md** — defines recurring scheduled tasks; parsed into Quartz jobs at startup; see `doc/scheduling/heartbeat.md` for format
- **memory/ directory** — daily memory log files `YYYY-MM-DD.md`; indexed in sqlite-vec alongside `MEMORY.md`; write with `file_write` to persist knowledge tied to a specific date

---

### `doc/workspace/openclaw-compatibility.md`

- **Drop-in compatibility** — set `KLAW_WORKSPACE` to an existing OpenClaw workspace; Klaw reads all standard files (SOUL.md, IDENTITY.md, AGENTS.md, USER.md, TOOLS.md, MEMORY.md, HEARTBEAT.md) without modification
- **Key differences from OpenClaw** — `USER.md` is imported once into `core_memory.json`; `MEMORY.md` is chunked and indexed rather than loaded as one block; HEARTBEAT tasks run as isolated subagents (not in the main session context)
- **Heartbeat efficiency** — OpenClaw heartbeat uses 170k–210k tokens per run in the main session; Klaw heartbeat runs as an isolated subagent using ~2–3k tokens; subagents do not see the main chat's recent messages (this is intentional)
- **Core memory vs USER.md** — after first import, `core_memory.json` is the authoritative source; changes via `memory_core_update` are NOT written back to `USER.md`
- **Skills** — same `SKILL.md` format; workspace skills override system skills with the same name

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt
./gradlew engine:test
```
