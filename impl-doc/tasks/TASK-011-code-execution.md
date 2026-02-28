# TASK-011 — Code Execution: Docker Sandbox + Host Exec

**Phase**: 9
**Priority**: P0
**Dependencies**: TASK-005, TASK-009 (approval UI в Gateway для host_exec)
**Est. LOC**: ~550
**Design refs**: [§6.5 Code Execution](../design/klaw-design-v0_4.md#65-исполнение-кода-docker-sandbox), [§9.2 codeExecution config](../design/klaw-design-v0_4.md#92-два-файла-gateway-и-engine), [§12 Docker overhead risk](../design/klaw-design-v0_4.md#12-риски-и-митигация), [v0.4→v0.5 changes](../design/klaw-v0_4-to-v0_5-changes.md)

---

## Summary

Два tool-а для выполнения кода:

1. **`sandbox_exec`** — выполнение Python и bash кода в изолированных Docker-контейнерах. Keep-alive режим для производительности на Pi 5: `docker exec` вместо `docker run --rm` (~100ms vs 2–5s).
2. **`host_exec`** — выполнение команд на хосте с четырёхступенчатым approval-контролем (allowList → notifyList → LLM pre-validation → ask user).

---

## Goals

### sandbox_exec

1. `sandbox_exec` tool: `language: "python"|"bash"`, `code: string`, `timeout?: int`
2. Keep-alive режим: `docker exec` (default, `keepAlive: true`)
3. One-shot режим: `docker run --rm` (для максимальной изоляции, `keepAlive: false`)
4. Ограничения: `--memory 256m`, `--cpus 1.0`, `--read-only`, `--network bridge/none`
5. Пересоздание контейнера: каждые 50 вызовов или 10 мин бездействия
6. Volume mounts: `$KLAW_WORKSPACE:/workspace:rw`, `/tmp/klaw-sandbox:rw`
7. `--privileged` запрещён (hardcoded, не конфигурируемо)
8. Таймаут: 30 секунд (configurable), hard limit

### host_exec

9. `host_exec` tool: `command: string` — выполнение команды на хосте
10. Четырёхступенчатый каскад approval: allowList → notifyList → LLM pre-validation → ask user
11. Конфигурация в `engine.json`: `hostExecution.enabled`, `allowList`, `notifyList`, `preValidation`, `askTimeoutMin`
12. Approval протокол через Engine↔Gateway socket (`approval_request`/`approval_response`)
13. Fallback: если LLM недоступен или таймаут pre-validation — всегда ask

---

## Implementation Details

### sandbox_exec Tool

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/tools/

@Singleton
class SandboxExecTool(
    private val sandboxManager: SandboxManager,
    private val config: CodeExecutionConfig,
) {
    // sandbox_exec: запустить код в docker sandbox
    // Параметры: language: "python"|"bash", code: String, timeout: Int? = config.timeout
    suspend fun execute(
        language: String,
        code: String,
        timeout: Int = config.timeout,
    ): String  // stdout + stderr, truncated if too long
}
```

### Sandbox Manager (Keep-Alive)

```kotlin
@Singleton
class SandboxManager(
    private val config: CodeExecutionConfig,
    private val docker: DockerClient,
) {
    private var container: SandboxContainer? = null
    private var executionCount = 0
    private var lastExecutionTime = Instant.EPOCH

    // Получить или создать keep-alive контейнер
    private suspend fun getContainer(): SandboxContainer {
        val current = container
        val shouldRecreate = current == null
            || executionCount >= config.keepAliveMaxExecutions
            || Duration.between(lastExecutionTime, Instant.now()) > Duration.ofMinutes(config.keepAliveIdleTimeoutMin)

        if (shouldRecreate) {
            current?.stop()
            container = startContainer()
            executionCount = 0
        }
        return container!!
    }

    suspend fun execute(language: String, code: String, timeout: Int): ExecutionResult {
        return if (config.keepAlive) {
            val c = getContainer()
            executionCount++
            lastExecutionTime = Instant.now()
            c.exec(language, code, timeout)
        } else {
            runOneshotContainer(language, code, timeout)
        }
    }

    private suspend fun startContainer(): SandboxContainer {
        // docker run -d --name klaw-sandbox-{uuid}
        //   --memory {maxMemory}
        //   --cpus {maxCpus}
        //   --read-only
        //   --network {allowNetwork ? "bridge" : "none"}
        //   --tmpfs /tmp:rw,size=64m
        //   -v "$workspace:/workspace:rw"
        //   -v "/tmp/klaw-sandbox:/output:rw"
        //   {dockerImage}
        //   sleep infinity   (или tail -f /dev/null)
    }

    private suspend fun runOneshotContainer(language: String, code: String, timeout: Int): ExecutionResult {
        // docker run --rm
        //   --memory {maxMemory} --cpus {maxCpus} --read-only
        //   --network {allowNetwork ? "bridge" : "none"}
        //   --tmpfs /tmp:rw,size=64m
        //   -v "$workspace:/workspace:rw"
        //   -v "/tmp/klaw-sandbox:/output:rw"
        //   {image} timeout {timeout} python3 -c "{code}"
        //   или: bash -c "{code}"
    }

    fun shutdown() {
        container?.stop()
        container = null
    }
}
```

### Docker Client

```kotlin
// Использовать Docker Java SDK или shell exec через ProcessBuilder
// Docker daemon доступен через /var/run/docker.sock

interface DockerClient {
    suspend fun run(options: RunOptions): ContainerHandle
    suspend fun exec(containerId: String, cmd: List<String>, timeout: Int): ExecutionResult
    suspend fun stop(containerId: String)
    suspend fun rm(containerId: String)
}

data class RunOptions(
    val image: String,
    val name: String? = null,
    val memoryLimit: String,
    val cpuLimit: String,
    val readOnly: Boolean = true,
    val networkMode: String,       // "bridge" или "none"
    val tmpfs: Map<String, String> = emptyMap(),
    val volumes: List<VolumeMount> = emptyList(),
    val command: List<String> = listOf("sleep", "infinity"),
    val detach: Boolean = false,
)

data class VolumeMount(val hostPath: String, val containerPath: String, val readOnly: Boolean = false)
data class ExecutionResult(val stdout: String, val stderr: String, val exitCode: Int)
```

### Security Constraints (Hardcoded)

```kotlin
// В SandboxManager — НЕЛЬЗЯ изменить через конфиг:
const val NO_PRIVILEGED = true          // --privileged запрещён
const val NO_DOCKER_SOCKET_MOUNT = true // нельзя монтировать /var/run/docker.sock
const val NO_HOST_NETWORK = true        // --network host запрещён
const val NO_PID_NAMESPACE = true       // --pid host запрещён
```

### Output Handling

```kotlin
data class SandboxExecOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
)

fun formatForLlm(output: SandboxExecOutput): String {
    // Truncate если слишком длинный (> 10000 символов)
    // Включить exit code если != 0
    // Форматировать stderr отдельно
}
```

---

## host_exec Tool

### Четырёхступенчатый каскад approval

```
Команда от LLM
  │
  ▼
1. allowList (glob) → совпало? → ВЫПОЛНИТЬ
  │ нет
  ▼
2. notifyList (glob) → совпало? → ВЫПОЛНИТЬ + уведомить пользователя
  │ нет
  ▼
3. LLM pre-validation (быстрая модель, ноль контекста)
  │  → оценка риска 0–10
  │  → ниже порога → ВЫПОЛНИТЬ
  │  → выше порога ↓
  ▼
4. Спросить пользователя → да → ВЫПОЛНИТЬ
                          → нет / таймаут → ОТКЛОНИТЬ
```

| Ступень | Поведение | Пример |
|---------|-----------|--------|
| `allowList` | Выполняется сразу | `df -h`, `systemctl status *` |
| `notifyList` | Выполняется, пользователь получает уведомление | `systemctl restart klaw-*` |
| LLM risk < порога | Выполняется — команда безобидная, но не в whitelist | `cat /var/log/syslog` |
| LLM risk ≥ порога | Спрашивает пользователя | `apt upgrade -y` |

### LLM pre-validation

Быстрая и дешёвая модель (Haiku-класс) оценивает риск команды без контекста разговора:

```
Оцени риск выполнения shell-команды от 0 до 10.
0 — только чтение, никаких побочных эффектов (cat, grep, ls, head, sed -n, awk, wc).
10 — необратимое разрушительное действие (rm -rf, mkfs, dd if=/dev/zero).

Модифицирующие команды (запись в файлы, перезапуск сервисов, установка пакетов) —
всегда 6 и выше, даже если кажутся безобидными.

Команда: {command}
Ответь только числом.
```

### Конфигурация

```yaml
# engine.json
hostExecution:
  enabled: true
  allowList:
    - "vcgencmd measure_temp"
    - "df -h"
    - "free -m"
    - "uptime"
    - "systemctl status *"
    - "docker ps"
    - "ls *"
  notifyList:
    - "systemctl restart klaw-*"
    - "docker restart *"
  preValidation:
    enabled: true                    # false → всегда ask для команд не в allowList/notifyList
    model: anthropic/claude-haiku    # быстрая модель для оценки риска
    riskThreshold: 5                 # 0–5 выполняем, 6–10 спрашиваем
    timeoutMs: 5000                  # таймаут на оценку, при превышении — ask
  askTimeoutMin: 5                   # таймаут ожидания ответа пользователя
```

Паттерны в `allowList` и `notifyList` — glob. При `preValidation.enabled: false`, `preValidation.model` не задан или LLM недоступен — fallback на `ask` (безопасный дефолт).

### Approval протокол (Engine ↔ Gateway)

Engine отправляет `approval_request` в Gateway socket:

```json
{"type":"approval_request","id":"apr_001","chatId":"telegram_123456","command":"apt upgrade -y","riskScore":8,"timeout":300}
```

Gateway показывает inline keyboard (Telegram) или ActionRow (Discord) и ждёт ответа. При получении:

```json
{"type":"approval_response","id":"apr_001","approved":true}
```

### Жизненный цикл approval в Engine

```
LLM вызывает host_exec("apt upgrade -y")
  │
  ▼
Engine: команда не в allowList, не в notifyList
  │
  ▼
Engine: LLM pre-validation → risk 8 (≥ порога 5)
  │
  ▼
Engine: отправить approval_request в Gateway socket
  │  Tool call loop SUSPEND (корутина ждёт CompletableDeferred)
  ▼
Gateway: показать inline keyboard пользователю
  │
  ▼
Пользователь нажимает кнопку (или таймаут)
  │
  ▼
Gateway: отправить approval_response в Engine socket
  │
  ▼
Engine: CompletableDeferred.complete(approved)
  │  Tool call loop RESUME
  ▼
approved=true  → выполнить команду, вернуть результат в LLM
approved=false → вернуть tool error: "Команда отклонена пользователем"
таймаут        → вернуть tool error: "Таймаут ожидания подтверждения"
```

### Реализация host_exec

```kotlin
@Singleton
class HostExecTool(
    private val config: HostExecutionConfig,
    private val llmRouter: LlmRouter,              // для pre-validation
    private val approvalService: ApprovalService,   // отправка approval_request, ожидание ответа
) {
    suspend fun execute(command: String, chatId: String): String {
        // 1. allowList check (glob match)
        if (matchesGlob(command, config.allowList)) {
            return runCommand(command)
        }

        // 2. notifyList check (glob match)
        if (matchesGlob(command, config.notifyList)) {
            approvalService.notify(chatId, command)
            return runCommand(command)
        }

        // 3. LLM pre-validation (skip if disabled)
        if (!config.preValidation.enabled) {
            return requestApprovalOrReject(chatId, command, riskScore = -1)
        }
        val risk = try {
            withTimeout(config.preValidation.timeoutMs) {
                evaluateRisk(command)
            }
        } catch (_: Exception) {
            config.preValidation.riskThreshold // fallback: treat as risky → ask
        }

        if (risk < config.preValidation.riskThreshold) {
            return runCommand(command)
        }

        // 4. Ask user
        val approved = approvalService.requestApproval(chatId, command, risk, config.askTimeoutMin)
        if (!approved) {
            return "Команда отклонена пользователем"
        }
        return runCommand(command)
    }

    private suspend fun runCommand(command: String): String {
        // ProcessBuilder + withContext(Dispatchers.VT)
    }
}
```

### ApprovalService

```kotlin
@Singleton
class ApprovalService(
    private val socketServer: EngineSocketServer,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun requestApproval(chatId: String, command: String, riskScore: Int, timeoutMin: Int): Boolean {
        val id = "apr_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred

        socketServer.send(ApprovalRequestMessage(id, chatId, command, riskScore, timeoutMin * 60))

        return try {
            withTimeout(timeoutMin.minutes) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            pending.remove(id)
        }
    }

    fun handleResponse(response: ApprovalResponseMessage) {
        pending[response.id]?.complete(response.approved)
    }

    suspend fun notify(chatId: String, command: String) {
        socketServer.send(OutboundMessage(chatId, "ℹ️ Выполняю: `$command`"))
    }
}
```

---

## TDD Approach

Тесты **до** реализации. Docker integration тесты требуют запущенного Docker daemon.

### Test Suite

**1. Unit tests — sandbox_exec (без Docker)**:
```kotlin
class SandboxManagerUnitTest {
    @Test fun `keepAlive=false uses docker_run_rm for each execution`()
    @Test fun `keepAlive=true reuses container for executions`()
    @Test fun `container recreated after keepAliveMaxExecutions`()
    @Test fun `container recreated after keepAliveIdleTimeoutMin`()
    @Test fun `shutdown stops running container`()
}

class SecurityConstraintsTest {
    @Test fun `privileged flag never added to docker run options`()
    @Test fun `docker socket never mounted`()
    @Test fun `host network never used`()
    @Test fun `read-only rootfs always set`()
}

class OutputFormattingTest {
    @Test fun `stdout truncated at max length`()
    @Test fun `stderr included when non-empty`()
    @Test fun `non-zero exit code reported`()
    @Test fun `timeout flagged in output`()
}
```

**2. Unit tests — host_exec**:
```kotlin
class HostExecToolTest {
    @Test fun `command in allowList executes immediately`()
    @Test fun `command in notifyList executes and sends notification`()
    @Test fun `allowList uses glob matching`()
    @Test fun `notifyList uses glob matching`()
    @Test fun `LLM risk below threshold executes without asking`()
    @Test fun `LLM risk at or above threshold triggers approval request`()
    @Test fun `LLM pre-validation timeout falls back to ask`()
    @Test fun `LLM unavailable falls back to ask`()
    @Test fun `user approves command — command executes`()
    @Test fun `user denies command — returns rejection error`()
    @Test fun `approval timeout — returns timeout error`()
    @Test fun `preValidation disabled — skips LLM and goes straight to ask`()
    @Test fun `host_exec disabled — returns error`()
}

class ApprovalServiceTest {
    @Test fun `requestApproval sends approval_request and waits`()
    @Test fun `handleResponse completes pending deferred`()
    @Test fun `timeout completes with false`()
    @Test fun `notify sends outbound message`()
    @Test fun `unknown approval response id is ignored`()
}
```

**3. Integration tests (требуют Docker daemon)**:
```kotlin
@Tag("integration")
class DockerSandboxIntegrationTest {
    @Test fun `python code executes and returns output`()
    @Test fun `bash code executes and returns output`()
    @Test fun `timeout enforced for infinite loop`()
    @Test fun `stdout and stderr captured correctly`()
    @Test fun `memory limit enforced`()
    @Test fun `no access to host filesystem outside workspace`()
    @Test fun `workspace readable and writable at /workspace`()
}
```

**4. Pi 5 performance test (manual/benchmark)**:
```
keepAlive=false (docker run --rm): ~2-5 секунды старт
keepAlive=true  (docker exec):     ~100ms
```

---

## Acceptance Criteria

### sandbox_exec
- [ ] `sandbox_exec` запускает Python и bash код
- [ ] Keep-alive режим: повторные вызовы используют `docker exec` (~100ms)
- [ ] One-shot режим: `docker run --rm` при `keepAlive: false`
- [ ] Контейнер пересоздаётся после `keepAliveMaxExecutions` вызовов
- [ ] Контейнер пересоздаётся после `keepAliveIdleTimeoutMin` бездействия
- [ ] `--privileged` **невозможен** (hardcoded)
- [ ] Workspace монтируется как `$KLAW_WORKSPACE:/workspace:rw`
- [ ] Таймаут прерывает зависший код
- [ ] Output корректно захватывается и форматируется для LLM

### host_exec
- [ ] `host_exec` выполняет команды из `allowList` немедленно
- [ ] `host_exec` выполняет команды из `notifyList` с уведомлением пользователя
- [ ] LLM pre-validation: risk < порога → выполняется, risk ≥ порога → ask
- [ ] Approval запрос отображается пользователю через Gateway (inline keyboard / ActionRow)
- [ ] Пользователь может одобрить или отклонить команду
- [ ] Таймаут approval = отклонение
- [ ] Fallback на ask при недоступности LLM pre-validation
- [ ] `hostExecution.enabled: false` полностью отключает tool

---

## Constraints

- Docker daemon доступен через `/var/run/docker.sock`. На Pi 5 — через remote Docker context (см. CLAUDE.md: Docker команды выполняются на Pi)
- `--privileged` — hardcoded запрет, не конфигурируемо
- `/var/run/docker.sock` внутри контейнера — запрещён (нельзя Docker-in-Docker)
- `--network host` запрещён (только `bridge` или `none`)
- Volume mounts только через конфиг (`volumeMounts`), не произвольные
- Тесты с Docker помечать `@Tag("integration")` и запускать отдельно от unit тестов
- Docker image `klaw-sandbox:latest` должен быть собран заранее (Python, bash, curl)
- На dev машине (macOS) — Docker Desktop. На Pi 5 — нативный Docker
- `host_exec` approval протокол зависит от Gateway (TASK-009) — нужны `approval_request`/`approval_response` message types в `SocketMessage`
- LLM pre-validation использует быструю модель (Haiku-класс), отдельную от основной LLM разговора
- Модифицирующие команды в LLM pre-validation всегда получают высокий балл (≥ 6), даже если выглядят безобидно

---

## Docker Sandbox Image

Нужно создать `Dockerfile` для `klaw-sandbox` образа:
```dockerfile
FROM python:3.12-slim
RUN apt-get update && apt-get install -y bash curl && rm -rf /var/lib/apt/lists/*
# Без shell как PID 1 для exec режима
CMD ["sleep", "infinity"]
```

Файл `docker/klaw-sandbox/Dockerfile` — в репозитории.

---

## Documentation Subtask

**Files to create**: `doc/tools/sandbox-exec.md`, `doc/tools/host-exec.md`

All documentation in **English only**.

---

### `doc/tools/sandbox-exec.md`

- **sandbox_exec** — params: `language` (`"python"` or `"bash"`), `code` (string), `timeout` (int, optional, default 30 seconds); executes code in an isolated Docker container; returns combined stdout and stderr; non-zero exit codes are reported in the output
- **Sandbox constraints** — memory: 256 MB; CPU: 1.0 core; read-only root filesystem; network: bridge by default (`allowNetwork: true` in `engine.json`), can be disabled; no privileged access; no access to host filesystem except allowed mounts
- **What is available in the sandbox** — Python 3.12, bash, curl; entire workspace mounted read-write at `/workspace`; write temporary output to `/tmp/klaw-sandbox` (writable)
- **Performance on Pi 5** — keep-alive mode (default, `keepAlive: true`): ~100 ms per call via `docker exec` on a persistent container; one-shot mode (`keepAlive: false`): 2–5 seconds per call via `docker run --rm`; prefer multiple small calls over one large blocking script
- **Container lifecycle** — keep-alive container is automatically recreated after 50 executions or 10 minutes of inactivity; state (variables, installed packages, temp files) does NOT persist between recreations; store results via `memory_save` or `file_write` if needed across calls
- **Output truncation** — output over 10,000 characters is truncated; for large outputs write to `/tmp/klaw-sandbox/output.txt` and use `file_read` to retrieve relevant sections (note: `/tmp/klaw-sandbox` maps to the host `/tmp/klaw-sandbox` directory which IS readable via `file_read`)
- **Common patterns**:
  - Fetch JSON from an API: `language="bash", code="curl -s 'https://api.example.com/data'"`
  - Process data with Python: `language="python", code="import json; data = ...; print(json.dumps(result))"`
  - Read/write workspace files: files at `/workspace/` are directly accessible for read and write
  - Run a skill script: first `file_read("skills/{name}/scripts/script.sh")` to see it, then `sandbox_exec(language="bash", code=script_content)`
- **Security limits (non-configurable)** — `--privileged` is never allowed; Docker socket cannot be mounted inside the container; `--network host` is never used; volume mounts are limited to those in the `codeExecution.volumeMounts` config

### `doc/tools/host-exec.md`

- **host_exec** — params: `command` (string); executes a shell command directly on the host OS (not in Docker sandbox); returns stdout + stderr; subject to approval control
- **Approval cascade** — commands are checked in order: (1) `allowList` glob match → execute immediately; (2) `notifyList` glob match → execute and notify user; (3) LLM pre-validation risk score → low risk executes, high risk asks user; (4) user approval via chat inline buttons
- **Use cases** — system monitoring (`df -h`, `free -m`, `uptime`), service management (`systemctl status/restart`), reading host logs, checking hardware sensors (`vcgencmd measure_temp`)
- **What is NOT allowed** — commands rejected by user or timed out; when `hostExecution.enabled: false`, the tool is completely disabled
- **Configuration** — `allowList` and `notifyList` patterns use glob syntax; `preValidation.enabled` (default true) toggles LLM risk assessment — when false, all commands not in allowList/notifyList go straight to user approval; `preValidation.riskThreshold` (default 5) controls the cutoff between auto-execute and ask; `askTimeoutMin` (default 5) controls how long to wait for user response

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt
./gradlew engine:test  # unit tests только
# Docker integration tests:
./gradlew engine:test -Pintegration  # или отдельный gradle task
```
