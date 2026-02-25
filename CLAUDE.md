# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full build (JVM, skips tests)
./gradlew build -x test

# Run all JVM tests (matches CI)
./gradlew :common:jvmTest :gateway:test :engine:test

# Run a single test class
./gradlew :common:jvmTest --tests io.github.klaw.common.config.ConfigParsingTest
./gradlew :engine:test --tests io.github.klaw.engine.llm.LlmRouterTest

# Code quality (ktlint + detekt)
./gradlew ktlintCheck detekt

# Compile Native targets (no cross-compiler needed for check)
./gradlew :cli:compileKotlinLinuxX64 :common:compileKotlinLinuxX64
```

## Project Architecture

Klaw is a two-process AI agent for Raspberry Pi 5 with Chinese LLM support:

- **Gateway** — Micronaut JVM service, pure message transport (Telegram, Discord). Sole writer of interactive `conversations/*/` JSONL files. Knows nothing about LLM/memory.
- **Engine** — Micronaut JVM service, LLM orchestration, memory, scheduler, tool execution. Sole owner of `klaw.db` and `scheduler.db`.
- **Common** — KMP module (JVM + linuxArm64 + linuxX64 + macosArm64/X64). Shared models, protocol, config schemas, path utilities.
- **CLI** — Kotlin/Native (linuxArm64 + linuxX64 + macosArm64/X64). Sends commands to Engine via IPC.

Gateway ↔ Engine communicate via Unix domain socket (`engine.sock`) using JSONL framing. The scheduler is an in-process module inside Engine (not a separate service).

Design document: `impl-doc/design/klaw-design-v0_4.md`. Task files: `impl-doc/tasks/TASK-001` through `TASK-012`.

## Module & Source Set Layout

```
common/src/
├── commonMain/   # Models, protocol, config schemas, KlawPaths, TokenCounter (expect)
├── jvmMain/      # kaml YAML parsing, JTokkit token counting
├── nativeMain/   # TokenCounter (actual, native stub)
├── commonTest/   # Protocol, error, model, config, path tests
└── jvmTest/      # YAML config parsing, JVM token counting tests

engine/src/main/kotlin/io/github/klaw/engine/
├── llm/          # LlmClient interface, OpenAiCompatibleClient, LlmRouter, RetryUtils, EnvVarResolver
└── util/         # VirtualThreadDispatcher (JDK 21 virtual threads, Dispatchers.VT)
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

## Hard Constraints

- Docker sandbox: `--privileged` flag is hardcoded forbidden (`CodeExecutionConfig.noPrivileged` uses `@Transient`)
- File tools: path traversal protection — agents cannot read outside `$KLAW_WORKSPACE`
- Chinese LLMs (GLM-5, DeepSeek, Qwen) connect via OpenAI-compatible API
- SQLite databases are cache/index only — source of truth is JSONL files on disk (recoverable via `klaw reindex`)

## Development Workflow

TDD on every task: write tests with edge cases first, then implement. After each task: run code-review subagent, then `lang-tools` MCP cleanup (imports + dead code scan for whole project). The lang-tools dead code detector reports ~163 false positives for public API in `common` — all expected (consumed by other modules).
