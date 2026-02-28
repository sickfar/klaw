# TASK-012 — Post-MVP Features Backlog

**Phase**: 10
**Priority**: P1 / P2 / P3
**Dependencies**: MVP complete (TASK-001 through TASK-011)
**Design refs**: [§10 Post-MVP Estimates](../design/klaw-design-v0_4.md#10-оценка-объёма-работ)

---

## Summary

Бэклог пост-MVP функциональности. Каждый пункт — отдельная задача, реализуется после выпуска MVP.

---

## P1 — High Priority

### P1.1 Gateway: Discord
**Design**: [§2.4 Gateway](../design/klaw-design-v0_4.md#24-описание-компонентов)

- Добавить `DiscordChannel` реализацию `Channel` interface
- Библиотека: `Kord` (Kotlin WebSocket Discord клиент)
- Конфиг: `gateway.json channels.discord.enabled = true`
- Нормализация Discord сообщений в unified format
- Тесты: Discord message mock

---

### P1.2 Фоновая суммаризация
**Design**: [§3.4 Background Summarization](../design/klaw-design-v0_4.md#34-фоновая-суммаризация)

- Quartz задача: триггер каждые N сообщений (или по cron)
- Engine берёт блок непокрытых сообщений
- Вызывает дешёвую модель (`routing.tasks.summarization: ollama/qwen3:8b`)
- Саммари сохраняется как Markdown: `summaries/{chatId}/YYYY-MM-DD_msg_001_050.md`
- Индексируется в sqlite-vec
- Тесты: суммаризация + восстановление через memory_search

---

### P1.3 OpenClaw Import (`klaw import`)
**Design**: [§4 OpenClaw Compatibility](../design/klaw-design-v0_4.md#4-совместимость-с-openclaw-workspace)

- CLI команда: `klaw import [--workspace PATH]` (локальная)
- Копирует OpenClaw workspace в `$KLAW_WORKSPACE`
- Конвертирует OPENCLAW conversation format в Klaw JSONL
- Импортирует MEMORY.md в sqlite-vec
- Тесты: round-trip import

---

### P1.4 OpenClaw Write-back
**Design**: [§4.6 Write-back](../design/klaw-design-v0_4.md#4-совместимость-с-openclaw-workspace)

- Периодически записывает MEMORY.md из `core_memory.json`
- Записывает daily logs из conversation JSONL
- Нужно для экосистемы OpenClaw (community tools ожидают эти файлы)

---

### P1.5 BOOT.md Execution
**Design**: [§4.2 BOOT.md](../design/klaw-design-v0_4.md#42-маппинг-файлов-openclaw--klaw)

- Engine выполняет BOOT.md при первом запуске сессии с chat_id
- Парсит BOOT.md как набор инструкций для LLM
- Позволяет агенту выполнить "ритуал старта": проверить почту, посмотреть расписание

---

### P1.6 AnthropicClient (~150 строк)
**Design**: [§8.2 LLM Abstraction](../design/klaw-design-v0_4.md#82-llm-абстракция-client--provider--router)

- Реализует `LlmClient` для Anthropic API
- Маппинг `LlmRequest` → Anthropic Messages API format
- Tool calling: Anthropic `tool_use` формат
- WireMock contract тесты

---

### P1.7 Skill: code-execute (SKILL.md)

- Создать `$KLAW_WORKSPACE/skills/code-execute/SKILL.md`
- Best practices для использования `code_execute` tool
- Инструкции: когда использовать, форматы вывода, примеры Python/bash

---

## P2 — Medium Priority

### P2.1 GeminiClient (~150 строк)

- Реализует `LlmClient` для Google Gemini API
- Маппинг `LlmRequest` → Gemini generateContent format
- Tool calling: Gemini function calling format

---

### P2.2 LLM Streaming (SSE)
**Design**: [§8.2](../design/klaw-design-v0_4.md#82-llm-абстракция-client--provider--router)

- `LlmClient.chatStream()` полная реализация через Micronaut SSE
- Gateway: стриминг ответов в Telegram (partial messages)
- Польза: пользователь видит ответ по мере генерации

---

### P2.3 Skill: web-search (SearXNG)
**Design**: [§6.2 SKILL.md format](../design/klaw-design-v0_4.md#62-формат-skillmd)

- `$KLAW_WORKSPACE/skills/web-search/SKILL.md`
- Инструкции: SearXNG API, curl команды, парсинг JSON ответа
- Настройка локального SearXNG на Pi 5 (Docker)

---

### P2.4 BOOTSTRAP.md Onboarding
**Design**: [§4.2](../design/klaw-design-v0_4.md#42-маппинг-файлов-openclaw--klaw)

- `klaw init [--from-openclaw PATH]` — интерактивное создание workspace
- Генерирует SOUL.md, IDENTITY.md, AGENTS.md, USER.md из ответов пользователя
- Или импортирует из OpenClaw

---

### P2.5 CLI: Additional Commands

- `klaw import` — импорт OpenClaw workspace
- `klaw export [--format json|md] CHAT` — экспорт разговора
- `klaw doctor` — расширенная диагностика (уже частично в TASK-010)
- `klaw init [--from-openclaw PATH]`

---

### P2.6 Temporal Decay for Memory Search

- Более новые чанки получают бонус к score при поиске
- `score_adjusted = score * (1 + decay_factor * recency)`
- Конфигурируемо: `memory.search.temporalDecayFactor`

---

## P3 — Low Priority

### P3.1 Soul Spec v0.5 (soul.json)

- Поддержка `soul.json` формата (структурированная версия SOUL.md)
- Маппинг полей soul.json в system prompt

---

### P3.2 Metrics & Monitoring

- Prometheus метрики: token usage, LLM latency, queue depth, memory usage
- Micronaut Micrometer integration
- Grafana dashboard для Pi 5 (опционально)

---

## Порядок реализации P1

Рекомендуемый порядок P1 задач:

1. **P1.1 Discord** — расширяет аудиторию без изменения архитектуры
2. **P1.5 BOOT.md** — простая фича, высокая ценность для персоны агента
3. **P1.6 AnthropicClient** — добавляет Claude как fallback, высокое качество FC
4. **P1.2 Background Summarization** — улучшает качество памяти при долгих разговорах
5. **P1.3 OpenClaw Import** — migration path для пользователей OpenClaw
6. **P1.4 OpenClaw Write-back** — обратная совместимость

---

## Каждая P1/P2 задача должна следовать тому же workflow:

1. Создать отдельный TASK файл в `doc/tasks/`
2. TDD: тесты перед реализацией
3. Code review subagent
4. Quality check: lang-tools cleanup + dead code analysis
5. Компиляция + тесты
