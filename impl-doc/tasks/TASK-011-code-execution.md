# TASK-011 — Code Execution: Docker Sandbox

**Phase**: 9
**Priority**: P0
**Dependencies**: TASK-005
**Est. LOC**: ~300
**Design refs**: [§6.5 Code Execution](../design/klaw-design-v0_4.md#65-исполнение-кода-docker-sandbox), [§9.2 codeExecution config](../design/klaw-design-v0_4.md#92-два-файла-gateway-и-engine), [§12 Docker overhead risk](../design/klaw-design-v0_4.md#12-риски-и-митигация)

---

## Summary

Реализовать `code_execute` tool для безопасного выполнения Python и bash кода в изолированных Docker-контейнерах. Keep-alive режим для производительности на Pi 5: `docker exec` вместо `docker run --rm` (~100ms vs 2–5s).

---

## Goals

1. `code_execute` tool: `language: "python"|"bash"`, `code: string`, `timeout?: int`
2. Keep-alive режим: `docker exec` (default, `keepAlive: true`)
3. One-shot режим: `docker run --rm` (для максимальной изоляции, `keepAlive: false`)
4. Ограничения: `--memory 256m`, `--cpus 1.0`, `--read-only`, `--network bridge/none`
5. Пересоздание контейнера: каждые 50 вызовов или 10 мин бездействия
6. Volume mounts: `$KLAW_WORKSPACE/skills:ro`, `/tmp/klaw-sandbox:rw`
7. `--privileged` запрещён (hardcoded, не конфигурируемо)
8. Таймаут: 30 секунд (configurable), hard limit

---

## Implementation Details

### Code Execution Tool

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/tools/

@Singleton
class CodeExecutionTool(
    private val sandboxManager: SandboxManager,
    private val config: CodeExecutionConfig,
) {
    // code_execute: запустить код в docker sandbox
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
        //   -v "$workspace/skills:/skills:ro"
        //   -v "/tmp/klaw-sandbox:/output:rw"
        //   {dockerImage}
        //   sleep infinity   (или tail -f /dev/null)
    }

    private suspend fun runOneshotContainer(language: String, code: String, timeout: Int): ExecutionResult {
        // docker run --rm ... {image} timeout {timeout} python3 -c "{code}"
        // или: bash -c "{code}"
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
data class CodeExecutionOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
)

fun formatForLlm(output: CodeExecutionOutput): String {
    // Truncate если слишком длинный (> 10000 символов)
    // Включить exit code если != 0
    // Форматировать stderr отдельно
}
```

---

## TDD Approach

Тесты **до** реализации. Docker integration тесты требуют запущенного Docker daemon.

### Test Suite

**1. Unit tests (без Docker)**:
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

**2. Integration tests (требуют Docker daemon)**:
```kotlin
@Tag("integration")
class DockerSandboxIntegrationTest {
    // Требуют Docker daemon — запускаются отдельно от unit tests

    @Test fun `python code executes and returns output`()
    @Test fun `bash code executes and returns output`()
    @Test fun `timeout enforced for infinite loop`()
    @Test fun `stdout and stderr captured correctly`()
    @Test fun `memory limit enforced`()
    @Test fun `no access to host filesystem`()
    @Test fun `workspace skills readable as ro mount`()
}
```

**3. Pi 5 performance test (manual/benchmark)**:
```
keepAlive=false (docker run --rm): ~2-5 секунды старт
keepAlive=true  (docker exec):     ~100ms
```

---

## Acceptance Criteria

- [ ] `code_execute` запускает Python и bash код
- [ ] Keep-alive режим: повторные вызовы используют `docker exec` (~100ms)
- [ ] One-shot режим: `docker run --rm` при `keepAlive: false`
- [ ] Контейнер пересоздаётся после `keepAliveMaxExecutions` вызовов
- [ ] Контейнер пересоздаётся после `keepAliveIdleTimeoutMin` бездействия
- [ ] `--privileged` **невозможен** (hardcoded)
- [ ] Таймаут прерывает зависший код
- [ ] Output корректно захватывается и форматируется для LLM

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

**File to create**: `doc/tools/code-execute.md`

Document the `code_execute` tool so the agent knows exactly what it can run, what constraints apply, and how to use it effectively on Pi 5.

All documentation in **English only**.

---

### `doc/tools/code-execute.md`

- **code_execute** — params: `language` (`"python"` or `"bash"`), `code` (string), `timeout` (int, optional, default 30 seconds); executes code in an isolated Docker container; returns combined stdout and stderr; non-zero exit codes are reported in the output
- **Sandbox constraints** — memory: 256 MB; CPU: 1.0 core; read-only root filesystem; network: bridge by default (`allowNetwork: true` in `engine.yaml`), can be disabled; no privileged access; no access to host filesystem except allowed mounts
- **What is available in the sandbox** — Python 3.12, bash, curl; workspace `skills/` directory mounted read-only at `/skills`; write temporary output to `/tmp/klaw-sandbox` (writable)
- **Performance on Pi 5** — keep-alive mode (default, `keepAlive: true`): ~100 ms per call via `docker exec` on a persistent container; one-shot mode (`keepAlive: false`): 2–5 seconds per call via `docker run --rm`; prefer multiple small calls over one large blocking script
- **Container lifecycle** — keep-alive container is automatically recreated after 50 executions or 10 minutes of inactivity; state (variables, installed packages, temp files) does NOT persist between recreations; store results via `memory_save` or `file_write` if needed across calls
- **Output truncation** — output over 10,000 characters is truncated; for large outputs write to `/tmp/klaw-sandbox/output.txt` and use `file_read` to retrieve relevant sections (note: `/tmp/klaw-sandbox` maps to the host `/tmp/klaw-sandbox` directory which IS readable via `file_read`)
- **Common patterns**:
  - Fetch JSON from an API: `language="bash", code="curl -s 'https://api.example.com/data'"`
  - Process data with Python: `language="python", code="import json; data = ...; print(json.dumps(result))"`
  - Run a skill script: first `file_read("skills/{name}/scripts/script.sh")` to see it, then `code_execute(language="bash", code=script_content)`
- **Security limits (non-configurable)** — `--privileged` is never allowed; Docker socket cannot be mounted inside the container; `--network host` is never used; volume mounts are limited to those in the `codeExecution.volumeMounts` config

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt
./gradlew engine:test  # unit tests только
# Docker integration tests:
./gradlew engine:test -Pintegration  # или отдельный gradle task
```
