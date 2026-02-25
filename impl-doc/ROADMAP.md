# Klaw — Roadmap

> Roadmap для разработки Klaw с нуля. Составлен на основе [design v0.4](design/klaw-design-v0_4.md).

---

## Milestone: MVP

**Цель**: работающий агент — Telegram → Engine → LLM → ответ, с надёжным планировщиком, семантической памятью и базовым CLI.
**Оценка**: ~5 900 строк кода, 2–3 недели разработки.

---

## Фазы разработки

```
Phase 0  ──────► Phase 1  ──────► Phase 2  ──────► Phase 3
Project          Common           LLM               IPC
Setup            Module           Abstraction       Protocol
                   │                │                  │
                   └────────────────┴──────────────────┘
                                        │
                                        ▼
                                     Phase 4
                                   Engine Core
                                   (loop, context,
                                    debounce, cmds)
                                        │
                          ┌─────────────┴──────────────┐
                          ▼                             ▼
                       Phase 5                      Phase 6
                     Built-in Tools             Memory System
                    (memory, file,             (sqlite-vec, ONNX,
                     docs, skills,             FTS5, hybrid search,
                     schedule, etc.)            core memory, chunks)
                          │                             │
                          └─────────────┬───────────────┘
                                        ▼
                                     Phase 7
                                 Scheduler & Workspace
                                 (Quartz+SQLite, HEARTBEAT.md,
                                  OpenClaw workspace loader)
                                        │
                          ┌─────────────┴──────────────┐
                          ▼                             ▼
                       Phase 8a                     Phase 8b
                    Gateway: Telegram                CLI Binary
                   (Micronaut, TelegramBotAPI,     (Kotlin/Native,
                    JSONL write, buffer)            local + delegated)
                          │                             │
                          └─────────────┬───────────────┘
                                        ▼
                                     Phase 9
                                 Code Execution
                                 (Docker sandbox,
                                  keep-alive container)
                                        │
                                        ▼
                                ══════ MVP ══════
```

---

## Детали фаз

### Phase 0 — Project Setup
**Задача**: [TASK-001](tasks/TASK-001-project-setup.md)
**Приоритет**: P0
**Зависимости**: нет

- Gradle multi-module: `common`, `gateway`, `engine`, `cli`
- Kotlin/JVM + KMP + Native targets
- ktlint, detekt
- Тестовые зависимости: kotlin.test, MockK, WireMock, Testcontainers
- GitHub Actions / CI

---

### Phase 1 — Common Module (KMP)
**Задача**: [TASK-002](tasks/TASK-002-common-module.md)
**Приоритет**: P0
**Зависимости**: Phase 0
**Оценка**: ~500 строк

- Модели данных: `LlmRequest`, `LlmResponse`, `ToolCall`, `ToolResult`, `LlmMessage`
- Модели сообщений: `IncomingMessage`, `OutgoingMessage`, `SocketMessage` (JSONL-протокол)
- Модели конфигурации: `GatewayConfig`, `EngineConfig`, `ProviderConfig`, `ModelRef`
- Модели данных БД: `MessageRecord`, `SessionRecord`
- JSONL сериализация / десериализация (kotlinx.serialization)
- YAML парсинг конфигов (kaml)
- Утилиты: приближённый подсчёт токенов, XDG пути, форматирование дат
- Типы ошибок

---

### Phase 2 — LLM Abstraction Layer
**Задача**: [TASK-003](tasks/TASK-003-llm-abstraction.md)
**Приоритет**: P0
**Зависимости**: Phase 1
**Оценка**: ~550 строк

- Интерфейс `LlmClient` (chat, chatStream)
- `OpenAiCompatibleClient` — POST /chat/completions, tool calling, retry, backoff
- `LlmRouter` — маппинг `provider/model-id` → client + config, fallback chain
- LLM contract tests (WireMock фикстуры: GLM-5, DeepSeek, Qwen, Ollama)

---

### Phase 3 — IPC: Unix Socket + JSONL Protocol
**Задача**: [TASK-004](tasks/TASK-004-ipc-protocol.md)
**Приоритет**: P0
**Зависимости**: Phase 1
**Оценка**: ~500 строк

- Unix domain socket сервер (Engine)
- Persistent JSONL client (Gateway → Engine + ответы ← Engine)
- CLI short-lived request-response client
- Registration handshake (`{"type":"register","client":"gateway"}`)
- `gateway-buffer.jsonl` — буфер при недоступности Engine, drain при реконнекте
- Socket security: `chmod 600 engine.sock`
- Protocol tests (loopback)

---

### Phase 4 — Engine Core
**Задача**: [TASK-005](tasks/TASK-005-engine-core.md)
**Приоритет**: P0
**Зависимости**: Phase 1, 2, 3
**Оценка**: ~900 строк

- Основной цикл обработки: receive → debounce → context → LLM → tool call loop → respond
- Debounce по `chat_id` (конфигурируемый, 1500ms по умолчанию)
- Context builder (system prompt + core memory + summary + sliding window + tools)
- Tool call loop с защитой от зацикливания (maxToolCallRounds)
- Управление сессиями (`klaw.db` таблица `sessions`)
- Rate limiting (семафор, interactive > subagent)
- Субагенты (параллельные корутины, изолированный контекст, subagentWindow)
- Command handler (`/new`, `/model`, `/models`, `/memory`, `/status`, `/help`)
- Silent-логика для субагентов (JSON `{"silent": true}`)
- Graceful shutdown

---

### Phase 5 — Built-in Tools
**Задача**: [TASK-006](tasks/TASK-006-tools-builtin.md)
**Приоритет**: P0
**Зависимости**: Phase 4
**Оценка**: ~1000 строк

| Группа | Инструменты |
|--------|-------------|
| Память | `memory_search`, `memory_save`, `memory_core_get`, `memory_core_update`, `memory_core_delete` |
| Файлы | `file_read`, `file_write`, `file_list` (sandbox: workspace only, max 1MB) |
| Документация | `docs_search`, `docs_read`, `docs_list` |
| Skills | `skill_load`, `skill_list` |
| Расписание | `schedule_list`, `schedule_add`, `schedule_remove` |
| Субагенты | `subagent_spawn` |
| Утилиты | `current_time`, `send_message` |

---

### Phase 6 — Memory System
**Задача**: [TASK-007](tasks/TASK-007-memory-system.md)
**Приоритет**: P0
**Зависимости**: Phase 1
**Оценка**: ~950 строк

- SQLite схема `klaw.db`: `messages`, `sessions`, `summaries`, `memory_chunks`, `vec_memory`, `vec_docs`, `doc_chunks`, `messages_fts`
- Загрузка sqlite-vec native extension
- ONNX embedding service (`all-MiniLM-L6-v2`, 384d, ~5–15ms/inference на Pi 5)
- Fallback: Ollama embedding через HTTP
- Markdown-aware чанкинг (~400 токенов, 80-токенное перекрытие)
- Vector search (sqlite-vec KNN)
- FTS5 полнотекстовый поиск
- Гибридный поиск (sqlite-vec + FTS5 → RRF k=60, topK=10)
- Core memory (`core_memory.json`, in-context)
- `klaw reindex`: пересборка `klaw.db` из JSONL (требует остановки Engine)

---

### Phase 7 — Scheduler & OpenClaw Workspace
**Задача**: [TASK-008](tasks/TASK-008-scheduler-workspace.md)
**Приоритет**: P0
**Зависимости**: Phase 4, 6
**Оценка**: ~800 строк

**Scheduler (модуль Engine)**:
- `SQLiteDelegate` (extends `StdJDBCDelegate`: `BEGIN IMMEDIATE` вместо `SELECT FOR UPDATE`)
- Quartz JDBC JobStore, `scheduler.db`, single-node (`isClustered=false`)
- `ScheduledMessageJob` → `engine.handleScheduledMessage()` (in-process)
- HEARTBEAT.md парсер → Quartz jobs
- Misfire recovery тесты (2+ часа простоя)

**OpenClaw Workspace Loader**:
- `buildSystemPrompt`: SOUL.md + IDENTITY.md + AGENTS.md + TOOLS.md + USER.md
- Инициализация `core_memory.json` из USER.md
- Индексация MEMORY.md + `memory/*.md` → chunking → sqlite-vec
- Skills discovery: `$KLAW_WORKSPACE/skills/` + `$XDG_DATA_HOME/klaw/skills/`

---

### Phase 8a — Gateway: Telegram
**Задача**: [TASK-009](tasks/TASK-009-gateway-telegram.md)
**Приоритет**: P0
**Зависимости**: Phase 3
**Оценка**: ~200 строк

- Micronaut app (Gateway)
- `TelegramBotAPI` (InsanusMokrassar) long polling
- `Channel` interface + `TelegramChannel`
- Нормализация входящих сообщений (`IncomingMessage` unified format)
- Bot-команды (Telegram `bot_command` entity) → `type:"command"` в Engine
- Whitelist проверка исходящих (channel + chatId)
- Запись JSONL (Gateway — единственный писатель для interactive-каналов)
- Persistent socket client к Engine + buffer drain при реконнекте

---

### Phase 8b — CLI: Kotlin/Native
**Задача**: [TASK-010](tasks/TASK-010-cli.md)
**Приоритет**: P0
**Зависимости**: Phase 3, common (Native target)
**Оценка**: ~250 строк

- Kotlin/Native binary: `linuxArm64`, `linuxX64`
- Зависит от `common` (Native target)
- **Локальные команды** (без Engine): `logs`, `memory show/edit`, `doctor`, `reindex`
- **Делегированные** (через Engine socket): `status`, `schedule *`, `memory search`, `sessions`
- Понятные ошибки если Engine не запущен: `systemctl start klaw-engine`
- Стартап < 50ms

---

### Phase 9 — Code Execution (Docker Sandbox)
**Задача**: [TASK-011](tasks/TASK-011-code-execution.md)
**Приоритет**: P0
**Зависимости**: Phase 4
**Оценка**: ~300 строк

- `code_execute` tool: `language: "python"|"bash"`, `code: string`, `timeout?: int`
- Keep-alive режим: `docker exec` вместо `docker run --rm` (~100ms vs 2–5s)
- Пересоздание контейнера: каждые 50 вызовов или 10 мин бездействия
- Ограничения: `--memory 256m`, `--cpus 1.0`, `--read-only`, `--network bridge/none`
- Volume mounts: `$KLAW_WORKSPACE/skills:ro`, `/tmp/klaw-sandbox:rw`
- Запрет `--privileged` (hardcoded)
- Docker sandbox image: `klaw-sandbox:latest` (Python, bash, curl)

---

## ══════ MVP ══════

После Phase 9 — рабочий агент, готовый к использованию на Raspberry Pi 5.

---

## Phase 10 — Post-MVP

**Задача**: [TASK-012](tasks/TASK-012-post-mvp.md)

| Компонент | Приоритет |
|-----------|-----------|
| Gateway: Discord (Kord) | P1 |
| Фоновая суммаризация (Background Summarization) | P1 |
| OpenClaw import (`klaw import`) | P1 |
| OpenClaw write-back (MEMORY.md, daily logs) | P1 |
| BOOT.md — выполнение при старте сессии | P1 |
| Skill: code-execute (SKILL.md best practices) | P1 |
| `AnthropicClient` (~150 строк) | P1 |
| `GeminiClient` (~150 строк) | P2 |
| LLM streaming через SSE (Micronaut HTTP Client) | P2 |
| Skill: web-search (SearXNG) | P2 |
| BOOTSTRAP.md — интерактивный онбординг | P2 |
| CLI: import, export, doctor, init | P2 |
| Temporal decay для поиска (свежее = релевантнее) | P2 |
| Soul Spec v0.5 (soul.json) | P3 |
| Метрики и мониторинг | P3 |

---

## Оценка объёма MVP

| Фаза | Компонент | Оценка строк |
|------|-----------|-------------|
| 0 | Project Setup | ~100 |
| 1 | Common Module | ~500 |
| 2 | LLM Abstraction | ~550 |
| 3 | IPC Protocol | ~500 |
| 4 | Engine Core | ~900 |
| 5 | Built-in Tools | ~1000 |
| 6 | Memory System | ~950 |
| 7 | Scheduler + Workspace | ~800 |
| 8a | Gateway: Telegram | ~200 |
| 8b | CLI | ~250 |
| 9 | Code Execution | ~300 |
| **Итого** | | **~6050** |

---

## Принципы разработки

1. **TDD**: тесты перед реализацией на каждой фазе — unit + integration
2. **Файлы > базы данных**: JSONL — source of truth, SQLite — кэш/индекс
3. **Append-only**: сообщения никогда не удаляются
4. **Изолированные процессы**: Gateway и Engine не делят состояние
5. **Восстанавливаемость**: `klaw reindex` пересобирает `klaw.db` из JSONL
6. **Convention over Configuration**: XDG пути, разумные дефолты
7. **Quality check**: lang-tools (cleanup imports, dead code) после каждой задачи

---

## Ссылки

- [Design Document v0.4](design/klaw-design-v0_4.md)
- [TASK-001: Project Setup](tasks/TASK-001-project-setup.md)
- [TASK-002: Common Module](tasks/TASK-002-common-module.md)
- [TASK-003: LLM Abstraction](tasks/TASK-003-llm-abstraction.md)
- [TASK-004: IPC Protocol](tasks/TASK-004-ipc-protocol.md)
- [TASK-005: Engine Core](tasks/TASK-005-engine-core.md)
- [TASK-006: Built-in Tools](tasks/TASK-006-tools-builtin.md)
- [TASK-007: Memory System](tasks/TASK-007-memory-system.md)
- [TASK-008: Scheduler + Workspace](tasks/TASK-008-scheduler-workspace.md)
- [TASK-009: Gateway Telegram](tasks/TASK-009-gateway-telegram.md)
- [TASK-010: CLI](tasks/TASK-010-cli.md)
- [TASK-011: Code Execution](tasks/TASK-011-code-execution.md)
- [TASK-012: Post-MVP](tasks/TASK-012-post-mvp.md)
