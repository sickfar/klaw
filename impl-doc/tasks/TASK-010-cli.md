# TASK-010 — CLI: Kotlin/Native Binary

**Phase**: 8b
**Priority**: P0
**Dependencies**: TASK-004, TASK-002 (Native target)
**Est. LOC**: ~250
**Design refs**: [§7 CLI](../design/klaw-design-v0_4.md#7-cli), [§7.2 Two Modes](../design/klaw-design-v0_4.md#72-архитектура-два-режима-работы), [§7.3 Implementation](../design/klaw-design-v0_4.md#73-реализация)

---

## Summary

Реализовать нативный CLI бинарник (`klaw`) скомпилированный через Kotlin/Native. Старт < 50ms. Команды делятся на локальные (работают с файлами без запущенного Engine) и делегированные (через Engine Unix socket).

---

## Goals

1. Kotlin/Native binary: `linuxArm64` (Pi 5) + `linuxX64` (dev машина)
2. Зависит только от `common` (Native target) — никаких JVM зависимостей
3. **Локальные команды** (без Engine): `logs`, `memory show/edit`, `doctor`
4. **Делегированные команды** (через Engine socket): `status`, `schedule *`, `memory search`, `sessions`, `reindex`
5. Понятные ошибки если Engine не запущен
6. Clikt или kotlinx-cli для аргументов (Native-совместимый)

---

## Implementation Details

### CLI Entry Point

```kotlin
// cli/src/nativeMain/kotlin/io/github/klaw/cli/

fun main(args: Array<String>) = KlawCli().main(args)

class KlawCli : CliktCommand(name = "klaw") {
    init {
        subcommands(
            StatusCommand(),
            LogsCommand(),
            ScheduleCommand(),
            MemoryCommand(),
            SessionsCommand(),
            ReindexCommand(),
            DoctorCommand(),
        )
    }
    override fun run() = Unit
}
```

### Local Commands

```kotlin
// klaw logs [--follow] [--chat CHAT_ID]
class LogsCommand : CliktCommand(name = "logs") {
    private val follow by option("--follow", "-f").flag()
    private val chatId by option("--chat")

    override fun run() {
        // Читает JSONL файлы напрямую
        // Если --follow: tail -f аналог (inotify на Linux)
        // Не требует запущенного Engine
        val paths = KlawPaths  // из common (Native target)
        val conversationsDir = paths.conversations
        // найти файлы для chatId или все, вывести последние строки
    }
}

// klaw memory show
// klaw memory edit
class MemoryCommand : CliktCommand(name = "memory") {
    init {
        subcommands(MemoryShowCommand(), MemoryEditCommand(), MemorySearchCommand())
    }
    override fun run() = Unit
}

class MemoryShowCommand : CliktCommand(name = "show") {
    override fun run() {
        // Читает core_memory.json напрямую (локальный файл)
        // Форматирует и выводит в stdout
        val content = readFile(KlawPaths.coreMemory)
        echo(content)
    }
}

class MemoryEditCommand : CliktCommand(name = "edit") {
    override fun run() {
        // Открывает $EDITOR core_memory.json
        val editor = getenv("EDITOR") ?: "vi"
        execProcess(editor, KlawPaths.coreMemory.toString())
    }
}

// klaw doctor — проверка конфигов и зависимостей
class DoctorCommand : CliktCommand(name = "doctor") {
    override fun run() {
        // Проверяет:
        // - gateway.yaml существует и парсится
        // - engine.yaml существует и парсится
        // - $KLAW_WORKSPACE директория существует
        // - engine.sock: есть / нет (Engine запущен или нет)
        // - ONNX модель скачана ($XDG_CACHE_HOME/klaw/models/)
        // - sqlite-vec .so доступен
        // Выводит: ✓ / ✗ для каждой проверки
    }
}
```

### Delegated Commands (via Engine Socket)

```kotlin
// klaw status
class StatusCommand : CliktCommand(name = "status") {
    override fun run() {
        val result = engineRequest("status")
        echo(result)
    }
}

// klaw schedule list
// klaw schedule add NAME CRON MESSAGE
// klaw schedule remove NAME
class ScheduleCommand : CliktCommand(name = "schedule") {
    init {
        subcommands(ScheduleListCommand(), ScheduleAddCommand(), ScheduleRemoveCommand())
    }
    override fun run() = Unit
}

// klaw memory search QUERY (делегированный — нужен ONNX embedding)
class MemorySearchCommand : CliktCommand(name = "search") {
    private val query by argument()
    override fun run() {
        val result = engineRequest("memory_search", mapOf("query" to query))
        echo(result)
    }
}

// klaw sessions
class SessionsCommand : CliktCommand(name = "sessions") {
    override fun run() {
        val result = engineRequest("sessions")
        echo(result)
    }
}

// klaw reindex (делегированный — Engine проверяет что он остановлен)
class ReindexCommand : CliktCommand(name = "reindex") {
    override fun run() {
        // Требует остановки Engine: systemctl stop klaw-engine
        // Engine выполняет reindex и возвращает прогресс
        echo("Убедитесь что Engine остановлен: systemctl stop klaw-engine")
        echo("Запуск переиндексации...")
        val result = engineRequest("reindex")
        echo(result)
    }
}
```

### Engine Socket Client (Native)

```kotlin
// cli/src/nativeMain/kotlin/io/github/klaw/cli/socket/

object EngineClient {
    fun request(command: String, params: Map<String, String> = emptyMap()): String {
        val socketPath = KlawPaths.engineSocket.toString()
        try {
            // POSIX socket connect → send JSON → read response → close
            // Kotlin/Native: использовать posix socket interop
            val json = Json.encodeToString(CliRequestMessage(command, params))
            // ... connect, write, read, close
            return responseJson
        } catch (e: Exception) {
            // Socket не существует или Connection refused
            throw EngineNotRunningException()
        }
    }
}

fun engineRequest(command: String, params: Map<String, String> = emptyMap()): String {
    return try {
        EngineClient.request(command, params)
    } catch (e: EngineNotRunningException) {
        echo("Engine не запущен. Запустите: systemctl start klaw-engine", err = true)
        exitProcess(1)
    }
}
```

---

## TDD Approach

Тесты **до** реализации. CLI тесты — unit тесты с mock файловой системой.

### Test Suite

**1. Local command tests** (с temp directories):
```kotlin
class LogsCommandTest {
    @Test fun `logs shows recent messages from JSONL`()
    @Test fun `logs --chat filters by chat_id`()
    @Test fun `logs gracefully handles empty conversations dir`()
}

class MemoryShowCommandTest {
    @Test fun `memory show prints core_memory_json contents`()
    @Test fun `memory show handles missing core_memory_json`()
}

class DoctorCommandTest {
    @Test fun `doctor reports missing gateway_yaml`()
    @Test fun `doctor reports engine running when socket exists`()
    @Test fun `doctor reports engine stopped when no socket`()
    @Test fun `doctor reports all OK when setup is correct`()
}
```

**2. Engine-not-running error tests**:
```kotlin
class EngineNotRunningTest {
    @Test fun `status returns helpful error when engine not running`()
    @Test fun `schedule list returns helpful error when engine not running`()
    @Test fun `memory search returns helpful error when engine not running`()
    @Test fun `error message includes systemctl command`()
}
```

**3. Argument parsing tests**:
```kotlin
class ArgParsingTest {
    @Test fun `klaw logs --follow parsed correctly`()
    @Test fun `klaw logs --chat telegram_123456`()
    @Test fun `klaw schedule add NAME CRON MESSAGE`()
    @Test fun `klaw schedule add with optional model`()
    @Test fun `klaw memory show`()
    @Test fun `klaw unknown_command shows help`()
}
```

**4. Socket client tests** (loopback с mock server):
```kotlin
class CliSocketClientTest {
    @Test fun `sends command and receives response`()
    @Test fun `throws EngineNotRunningException when socket unavailable`()
    @Test fun `connection closed after request-response`()
}
```

---

## Acceptance Criteria

- [ ] `klaw` компилируется для `linuxArm64` и `linuxX64`
- [ ] Стартап < 50ms (нативный бинарник, не JVM)
- [ ] Локальные команды работают без запущенного Engine
- [ ] Делегированные команды возвращают понятную ошибку если Engine не запущен
- [ ] `klaw doctor` проверяет конфиги и зависимости
- [ ] `klaw logs --follow` работает как tail -f для JSONL
- [ ] Аргументы корректно парсятся (Clikt или kotlinx-cli)

---

## Constraints

- CLI зависит **только** от `common` (Native target) — никаких JVM библиотек
- Аргумент-парсер должен быть KMP/Native-совместимым: Clikt поддерживает Native, kotlinx-cli тоже
- POSIX socket interop для Kotlin/Native: `platform.posix.*`
- Никакой бизнес-логики в CLI — только форматирование вывода и делегация
- `klaw reindex` — делегированный, но предупреждает что Engine должен быть остановлен

---

## Documentation Subtask

**File to create**: `doc/commands/cli.md`

Document all `klaw` CLI commands so the agent can tell users how to administer the system and so the agent itself can recommend the right command when diagnosing issues.

All documentation in **English only**.

---

### `doc/commands/cli.md`

- **What the CLI is** — the `klaw` native binary for administering Gateway and Engine processes; starts in < 50 ms; works even when Engine is stopped (for local commands)
- **Two command modes** — *Local* commands work directly with files without a running Engine; *Delegated* commands require Engine to be running and communicate via Unix socket
- **klaw status** *(delegated)* — shows uptime, active sessions (chat ID, model), and LLM queue depth
- **klaw logs** *(local)* — prints recent messages from JSONL files; `--follow` / `-f` for live tailing; `--chat {chatId}` to filter by chat (e.g. `--chat telegram_123456`)
- **klaw schedule list** *(delegated)* — lists all active Quartz jobs with cron expression and next fire time
- **klaw schedule add NAME CRON MESSAGE** *(delegated)* — adds a persistent cron task; optional flags: `--model provider/model-id`, `--inject-into chatId`
- **klaw schedule remove NAME** *(delegated)* — removes a named task
- **klaw memory show** *(local)* — prints `core_memory.json` to stdout
- **klaw memory edit** *(local)* — opens `core_memory.json` in `$EDITOR`
- **klaw memory search QUERY** *(delegated)* — runs semantic search; requires Engine (ONNX embedding)
- **klaw sessions** *(delegated)* — lists active sessions with model and segment start
- **klaw reindex** *(delegated, requires Engine stopped)* — rebuilds `klaw.db` from JSONL source files; always stop the Engine first with `systemctl stop klaw-engine` before running
- **klaw doctor** *(local)* — checks configuration files exist and parse correctly; verifies workspace directory; reports whether Engine socket is present; checks ONNX model cache
- **When Engine is not running** — delegated commands print: `"Engine is not running. Start it with: systemctl start klaw-engine"` and exit with a non-zero code

---

## Quality Check

```bash
./gradlew cli:ktlintCheck
# detekt может не поддерживать Native — проверить и настроить если нужно
./gradlew cli:linuxX64Test
./gradlew cli:linkReleaseExecutableLinuxX64  # или linuxArm64 на Pi 5
```
