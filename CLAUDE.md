# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full build (JVM, skips tests)
./gradlew build -x test

# Run all JVM tests (matches CI)
./gradlew :common:jvmTest :gateway:test :engine:test

# Run CLI native tests (macOS dev machine)
./gradlew :cli:macosArm64Test

# Run a single test class
./gradlew :common:jvmTest --tests io.github.klaw.common.config.ConfigParsingTest
./gradlew :engine:test --tests io.github.klaw.engine.llm.LlmRouterTest

# Code quality (ktlint + detekt)
./gradlew ktlintCheck detekt

# Compile Native targets (no cross-compiler needed for check)
./gradlew :cli:compileKotlinLinuxX64 :common:compileKotlinLinuxX64

# Assemble distribution artifacts (JARs + native CLI binaries)
./gradlew assembleDist          # full build for current OS
./gradlew assembleCliMacos      # macOS CLI binaries only (for macOS CI)
```

## Project Architecture

Klaw is a two-process AI agent for Raspberry Pi 5 with Chinese LLM support:

- **Gateway** — Micronaut JVM service, pure message transport (Telegram, Discord). Sole writer of interactive `conversations/*/` JSONL files. Knows nothing about LLM/memory.
- **Engine** — Micronaut JVM service, LLM orchestration, memory, scheduler, tool execution. Sole owner of `klaw.db` and `scheduler.db`.
- **Common** — KMP module (JVM + linuxArm64 + linuxX64 + macosArm64/X64). Shared models, protocol, config schemas, path utilities.
- **CLI** — Kotlin/Native (linuxArm64 + linuxX64 + macosArm64/X64). Sends commands to Engine via IPC.

Gateway ↔ Engine communicate via Unix domain socket (`engine.sock`) using JSONL framing. The scheduler is an in-process module inside Engine (not a separate service).

Design document: `impl-doc/design/klaw-design-v0_4.md`. Task files: `impl-doc/tasks/TASK-001` through `TASK-012`. User-facing docs: `doc/` (commands, config, deployment, tools, memory, etc.).

## Module & Source Set Layout

```
common/src/
├── commonMain/   # Models, protocol, config schemas, KlawPaths, TokenCounter (expect)
├── jvmMain/      # kaml YAML parsing, JTokkit token counting
├── nativeMain/   # TokenCounter (actual, native stub)
├── commonTest/   # Protocol, error, model, config, path tests
└── jvmTest/      # YAML config parsing, JVM token counting tests

engine/src/main/kotlin/io/github/klaw/engine/
├── llm/          # LlmClient, OpenAiCompatibleClient, LlmRouter, RetryUtils, EnvVarResolver
├── context/      # ContextBuilder, SubagentHistoryLoader, SkillRegistry, WorkspaceLoader
├── message/      # MessageProcessor, DebounceBuffer, ToolCallLoopRunner, MessageRepository, MessageEmbeddingService
├── memory/       # MemoryService, AutoRagService, EmbeddingService (ONNX + Ollama), MarkdownChunker, RrfMerge
├── tools/        # ToolExecutor, FileTools, MemoryTools, ScheduleTools, SkillTools, SubagentTools, DocsTools
├── scheduler/    # KlawScheduler, KlawSchedulerImpl, MicronautJobFactory (Quartz + SQLiteDelegate)
├── workspace/    # HeartbeatImporter, HeartbeatParser
├── db/           # DatabaseFactory, SqliteVecLoader, VirtualTableSetup
├── docs/         # DocsService
├── init/         # InitCliHandler
├── maintenance/  # ReindexService
└── util/         # VirtualThreadDispatcher (JDK 21 virtual threads, Dispatchers.VT)

cli/src/nativeMain/kotlin/io/github/klaw/cli/
├── command/      # StatusCommand, StopCommand, LogsCommand, MemoryCommand, ScheduleCommand, SessionsCommand,
│                 #   ConfigCommand, DoctorCommand, EngineCommand, GatewayCommand, IdentityCommand,
│                 #   InitCommand, ReindexCommand
├── init/         # InitWizard, WorkspaceInitializer, ServiceInstaller, ServiceManager, DockerComposeInstaller,
│                 #   DockerEnvironment, EngineStarter, EnvWriter, ConfigTemplates, PlatformIO (expect/actual)
├── socket/       # EngineSocketClient, SockAddrBuilder (expect/actual for Linux/macOS)
└── ui/           # AnsiColors, Spinner
```

## Key Implementation Details

**HTTP client in Engine:** `java.net.http.HttpClient` (JDK 21) — NOT Micronaut HttpClient. Blocking `send()` wrapped in `withContext(Dispatchers.VT)`.

**Serialization:** `SocketMessage` sealed class uses `@JsonClassDiscriminator("type")` + `@SerialName`. Do NOT add `override val type: String` to subclasses (duplicate field conflict). `CliRequestMessage` is intentionally NOT a `SocketMessage` subclass.

**kaml (YAML):** JVM-only — must NOT be added to `commonMain`. Always set `strictMode = false` to allow unknown keys for forward compatibility.

**KMP token counter:** `TokenCounter.kt` in `commonMain` holds only `expect fun`. Logic lives in `TokenCounterHelpers.kt` (avoids JVM class name duplication). JTokkit `countTokensOrdinary` does not include special tokens.

**Native tests:** Use `assertTrue()` not `assert()` (the latter requires `@OptIn(ExperimentalNativeApi::class)`).

**KAPT:** Applied via `apply(plugin = "org.jetbrains.kotlin.kapt")` in engine/gateway modules. `micronaut-inject-java` must be explicitly versioned. Use string notation `"kapt"(...)` for kapt/kaptTest configs.

**WireMock:** Use `wiremock-standalone` (not `wiremock`) to avoid Jetty BOM conflicts with Micronaut.

**`assertFailsWith`** (kotlin.test) is NOT suspend-aware — use try-catch inside `runTest` for coroutine exception assertions.

**EnvVarResolver:** Only resolves `${UPPERCASE_VAR}` patterns; lowercase is returned as literals by design.

## Technology Stack

- Kotlin 2.3.10, Micronaut 4.10.7, Java 21
- kotlinx-serialization (JSONL), kaml (YAML, JVM only), kotlinx-datetime, kotlinx-coroutines
- SqlDelight 2.x (KMP SQLite), sqlite-vec (native C extension, engine only)
- ONNX Runtime + DJL HuggingFace Tokenizers (engine embeddings)
- Quartz 2.x (scheduler, engine only — custom `SQLiteDelegate`)
- Clikt (CLI argument parsing, Native-compatible)
- TelegramBotAPI InsanusMokrassar (gateway)

**Not used:** Spring Boot, LangChain4j, Spring AI, external vector DBs (pgvector, Letta).

## Docker Compose Deployment

`docker-compose.yml` at the repo root defines three services: `engine`, `gateway`, `cli` (cli has `profiles: [cli]`). The `klaw` shell script at repo root wraps `docker compose run --rm cli`.

Containers run as non-root `klaw` user (UID 10001, GID 10001). All mount paths use `/home/klaw/...` inside containers. Host dirs need `o+rwx` (set by `klaw init` for hybrid/docker mode) so the container user can write.

**Environment variables for containers:**
- `HOME=/home/klaw` — so `KlawPaths` resolves XDG dirs correctly inside containers
- `KLAW_SOCKET_PATH=/home/klaw/.local/state/klaw/run/engine.sock` — socket in separate `run/` mount
- `KLAW_SOCKET_PERMS=rw-rw-rw-` (engine only) — 666 perms so host CLI can connect to container socket

Socket is isolated in `$stateDir/run/` (mounted separately from state dir) with 666 permissions. Native mode is unchanged: socket at `$state/engine.sock` with 600 permissions.

Config is bind-mounted read-only from `./config/`. Dockerfiles are in `docker/{engine,gateway,cli}/`.

The `klaw init` command (CLI) runs an interactive wizard that detects Docker vs. native environment and sets up workspace, config, and service installation accordingly. For hybrid/docker mode, it creates `$stateDir/run/` and sets 0777 on state/data dirs.

Deployment scripts in `scripts/`: `build.sh` (runs `assembleDist`), `deploy.sh`, `install.sh`, `install-klaw.sh`, `get-klaw.sh` (curl-install). Systemd unit files in `deploy/`.

## Hard Constraints

- Docker sandbox: `--privileged` flag is hardcoded forbidden (`CodeExecutionConfig.noPrivileged` uses `@Transient`)
- File tools: path traversal protection — agents cannot read outside `$KLAW_WORKSPACE`
- Chinese LLMs (GLM-5, DeepSeek, Qwen) connect via OpenAI-compatible API
- SQLite databases are cache/index only — source of truth is JSONL files on disk (recoverable via `klaw reindex`)

## Logging Constraints

**Library:** `io.github.oshai:kotlin-logging-jvm` (`KotlinLogging.logger {}`). Never use raw `org.slf4j.LoggerFactory`. Never use `System.err.println`.

**Declaration:** File-level `private val logger = KotlinLogging.logger {}` for top-level functions; field-level inside classes.

**Syntax:** Always use lambda form — `logger.debug { "..." }` — for all levels. The deprecated positional-arg form `logger.debug("msg {}", arg)` must NOT be used (kotlin-logging 7.x).

**Log levels:**
- `TRACE` — every individual micro-action (line received, message dispatched, embedding computed, buffer drained, retry attempt number). TRACE should make it possible to trace every step of execution.
- `DEBUG` — flow-level events (session resumed/created, connection established, buffer drain count, LLM request/response summary, search result counts)
- `INFO` — lifecycle events only (server started/stopped, socket connected/disconnected, session created for first time)
- `WARN` — recoverable anomalies (oversized message, idle timeout, retry triggered, capacity limit hit)
- `ERROR` — unexpected failures with attached throwable via `logger.error(e) { "..." }`

**What MUST NOT be logged (ever, at any level):**
- API keys, Bearer tokens, Authorization headers
- LLM request body or response body content
- Raw socket message content (the `line` string) — log only `line.length` or `msg::class.simpleName`
- User chat message text
- Memory chunk text or search query strings
- Tool execution output or exception messages that may embed tool output (`e::class.simpleName` is safe; `e.message` is not)
- When attaching a throwable via `logger.error(e) { }`, do NOT also include `${e.message}` in the lambda — it is redundant and leaks exception content

**What is safe to log:** class names, byte/char counts, result counts, HTTP status codes, model IDs, endpoint URLs (no auth), chatIds (platform identifiers), source names, chunk indices, token counts.

**Configuration:**
- Production `logback.xml`: console + rolling file appender (`~/.local/state/klaw/logs/`), `io.github.klaw` at DEBUG, root at INFO. Overridable via `-Dklaw.log.dir=...`.
- Test `logback-test.xml`: console only, `io.github.klaw` at TRACE (enables full trace output in tests).
- Common module (KMP) has no SLF4J access — do NOT add logging to `common`.

## Development Workflow

TDD on every task: write tests with edge cases first, then implement. After each task: run code-review subagent, then `lang-tools` MCP cleanup (imports + dead code scan for whole project). The lang-tools dead code detector reports ~163 false positives for public API in `common` — all expected (consumed by other modules).
