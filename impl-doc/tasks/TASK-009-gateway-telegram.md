# TASK-009 — Gateway: Telegram

**Phase**: 8a
**Priority**: P0
**Dependencies**: TASK-004
**Est. LOC**: ~200
**Design refs**: [§2.4 Gateway](../design/klaw-design-v0_4.md#24-описание-компонентов), [§9.2 Gateway Config](../design/klaw-design-v0_4.md#92-два-файла-gateway-и-engine)

---

## Summary

Реализовать Gateway как Micronaut приложение с Telegram-каналом через TelegramBotAPI (InsanusMokrassar), persistent socket-клиентом к Engine, записью JSONL и буферизацией при недоступности Engine. Gateway — чистый транспорт, не знает про LLM или бизнес-логику.

---

## Goals

1. Micronaut Gateway приложение
2. `Channel` interface + `TelegramChannel` (long polling)
3. Нормализация входящих сообщений (unified format)
4. Bot-команды: Telegram `bot_command` entity → `type:"command"` в Engine
5. Persistent socket client к Engine (из TASK-004)
6. Whitelist проверка исходящих (channel + chatId из `gateway.json`)
7. JSONL logging: Gateway — единственный писатель для interactive-каналов
8. `gateway-buffer.jsonl`: буферизация при недоступном Engine, drain при реконнекте
9. `setMyCommands` в Telegram при старте (регистрация bot-команд из engine.json)

---

## Implementation Details

### Channel Interface

```kotlin
// gateway/src/main/kotlin/io/github/klaw/gateway/channel/

interface Channel {
    val name: String   // "telegram", "discord"
    suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit)
    suspend fun send(chatId: String, response: OutgoingMessage)
    suspend fun start()
    suspend fun stop()
}

data class IncomingMessage(
    val id: String,                 // generated UUID
    val channel: String,
    val chatId: String,             // "telegram_123456"
    val content: String,
    val ts: Instant,
    val isCommand: Boolean = false,
    val commandName: String? = null,
    val commandArgs: String? = null,
)

data class OutgoingMessage(
    val content: String,
    val replyToId: String? = null,
)
```

### TelegramChannel

```kotlin
@Singleton
class TelegramChannel(
    private val config: TelegramConfig,
    private val engineClient: EngineSocketClient,
    private val jsonlWriter: ConversationJsonlWriter,
    private val outboundHandler: OutboundMessageHandler,
) : Channel {
    override val name = "telegram"

    // Long polling через TelegramBotAPI (InsanusMokrassar)
    // lib: "dev.inmo:tgbotapi:{version}"

    override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {
        // Получить update от Telegram
        // Если message entity type = bot_command → isCommand=true
        // Нормализовать в IncomingMessage
        // chatId формат: "telegram_{platformChatId}"
        // Записать в JSONL (Gateway всегда пишет)
        // Отправить в Engine socket (или в buffer если Engine недоступен)
    }

    override suspend fun send(chatId: String, response: OutgoingMessage) {
        // Отправить сообщение обратно в Telegram
        // chatId "telegram_123456" → платформенный ID 123456
    }
}
```

### Outbound Handler

```kotlin
@Singleton
class OutboundMessageHandler(
    private val channels: Map<String, Channel>,
    private val config: GatewayConfig,
    private val jsonlWriter: ConversationJsonlWriter,
) {
    // Получает outbound сообщение от Engine через socket
    // 1. Проверяет whitelist: channel + chatId в allowedChatIds
    //    Если не в whitelist → error в Engine, НЕ отправляем
    // 2. Записывает в JSONL (assistant message)
    // 3. Отправляет в соответствующий Channel
    suspend fun handle(message: OutboundSocketMessage)

    private fun isAllowedChatId(channel: String, chatId: String): Boolean {
        val allowedIds = config.channels.telegram?.allowedChatIds ?: return true
        return allowedIds.isEmpty() || chatId in allowedIds
        // Примечание: если allowedChatIds пуст — все ИСХОДЯЩИЕ отклоняются (не принимаются)
        // Кроме ответов на входящие сообщения (implicit allow)
    }
}
```

**Whitelist логика** (из дизайна §2.4):
- `allowedChatIds: []` → принимать от всех (inbound), но ВСЕ outbound через `send_message` отклоняются
- Ответы на inbound сообщения: chatId входящего автоматически разрешён (implicit allow) на время сессии
- `send_message` tool: chatId ОБЯЗАН быть в whitelist

### JSONL Writer

```kotlin
@Singleton
class ConversationJsonlWriter(private val paths: KlawPaths) {
    // Записывает сообщения в conversations/{chatId}/YYYY-MM-DD.jsonl
    // Append-only, thread-safe
    // Gateway — ЕДИНСТВЕННЫЙ ПИСАТЕЛЬ для interactive-каналов

    suspend fun writeInbound(message: IncomingMessage)
    suspend fun writeOutbound(chatId: String, content: String, model: String? = null)
    suspend fun writeCommand(message: IncomingMessage)
}
```

### Gateway Application

```kotlin
// gateway/src/main/kotlin/io/github/klaw/gateway/

@MicronautApplication
class GatewayApplication

@Singleton
class GatewayLifecycle(
    private val channel: TelegramChannel,
    private val engineClient: EngineSocketClient,
    private val config: GatewayConfig,
) : ApplicationEventListener<StartupEvent> {
    override fun onApplicationEvent(event: StartupEvent) {
        // 1. Подключиться к Engine socket
        // 2. Drain gateway-buffer.jsonl если есть накопленные сообщения
        // 3. Зарегистрировать bot-команды в Telegram (setMyCommands)
        // 4. Запустить long polling
    }
}
```

---

## TDD Approach

Тесты **до** реализации.

### Test Suite

**1. Message normalization tests**:
```kotlin
class TelegramMessageNormalizationTest {
    @Test fun `regular text message normalized to IncomingMessage`()
    @Test fun `bot_command entity detected as isCommand=true`()
    @Test fun `slash_new command parsed correctly`()
    @Test fun `slash_model with args parsed correctly`()
    @Test fun `chatId formatted as telegram_{platformId}`()
    @Test fun `message with forwarded content handled`()
}
```

**2. Whitelist tests**:
```kotlin
class OutboundWhitelistTest {
    @Test fun `empty allowedChatIds blocks all send_message tool outbound`()
    @Test fun `chatId in allowedChatIds allowed`()
    @Test fun `chatId not in allowedChatIds rejected`()
    @Test fun `reply to inbound allowed even with non-empty whitelist (implicit allow)`()
    @Test fun `whitelist is channel-scoped (telegram chatId valid only for telegram)`()
}
```

**3. JSONL writer tests**:
```kotlin
class ConversationJsonlWriterTest {
    @Test fun `inbound message written to correct file path`()
    @Test fun `outbound message written with role=assistant`()
    @Test fun `file path includes YYYY-MM-DD date`()
    @Test fun `messages appended not overwritten`()
    @Test fun `concurrent writes don't corrupt file`()
    @Test fun `file created if not exists`()
    @Test fun `directories created if not exist`()
}
```

**4. Buffer + reconnect tests**:
```kotlin
class GatewayBufferReconnectTest {
    @Test fun `message buffered when engine unavailable`()
    @Test fun `buffer drained on reconnect in order`()
    @Test fun `messages processed from buffer before new messages`()
}
```

**5. Outbound handler tests**:
```kotlin
class OutboundHandlerTest {
    @Test fun `outbound from engine dispatched to correct channel`()
    @Test fun `outbound written to JSONL before sending`()
    @Test fun `blocked by whitelist returns error to engine`()
}
```

---

## Acceptance Criteria

- [ ] Telegram bot получает и нормализует сообщения
- [ ] Bot-команды корректно распознаются и отправляются в Engine как `type:"command"`
- [ ] Все входящие сообщения записываются в JSONL до отправки в Engine
- [ ] Whitelist защищает от несанкционированных исходящих
- [ ] `gateway-buffer.jsonl` заполняется при недоступном Engine
- [ ] Buffer drains при реконнекте в правильном порядке
- [ ] `setMyCommands` регистрирует команды в Telegram при старте

---

## Constraints

- Gateway **не знает** про LLM, память, skills, субагенты — чистый транспорт
- Gateway — **единственный писатель** JSONL для interactive-каналов (telegram, discord)
- **Не использовать** Spring Boot или Ktor — только Micronaut
- TelegramBotAPI: `dev.inmo:tgbotapi` (InsanusMokrassar) — long polling, не webhooks (Pi 5 за NAT)
- Discord Gateway (Kord) — Post-MVP (P1)

---

## Documentation Subtask

**Files to create / complete**:

1. `doc/config/gateway-yaml.md`
2. `doc/storage/where-data-lives.md` — add conversations, summaries, and config sections (extends TASK-004 stub)

All documentation in **English only**.

---

### `doc/config/gateway-yaml.md`

- **Location** — `~/.config/klaw/gateway.json`; read-only from the agent's perspective (outside workspace); the user must edit this file to change settings
- **channels.telegram.allowedChatIds** — whitelist for outbound delivery; for `send_message` tool and `injectInto` on scheduled tasks, the `chatId` MUST be in this list; empty list means accept all inbound but REJECT all unsolicited outbound (safe default to prevent hallucinating agent from messaging arbitrary people)
- **Implicit allow for replies** — when replying to a message received from a user, that user's `chatId` is temporarily allowed even if not in the whitelist; this lets the agent accept and reply to any user without adding them to the permanent whitelist
- **chatId format** — channel name prefix + platform chat ID: Telegram chat `123456` → `"telegram_123456"`; use this exact format in `schedule_add(injectInto=...)` and `send_message(chatId=...)`
- **What to do when send_message fails** — check the tool result error message; if `"chatId not in allowedChatIds"`, the target must be added to `gateway.json` by the user; the agent cannot modify this file
- **discord.enabled** — set to `true` to enable Discord channel (requires `DISCORD_BOT_TOKEN` env variable); disabled by default

---

### `doc/storage/where-data-lives.md` — complete version

Complete the stub from TASK-004 with all data locations:

- **Configuration** — `~/.config/klaw/gateway.json` (channels, tokens, allowedChatIds); `~/.config/klaw/engine.json` (LLM providers, models, routing, memory, scheduling, code execution)
- **Conversations** — `~/.local/share/klaw/conversations/{channel}_{chatId}/YYYY-MM-DD.jsonl`; one file per day per chat; append-only; written by Gateway; example path: `conversations/telegram_123456/2026-02-24.jsonl`
- **Summaries** — `~/.local/share/klaw/summaries/{channel}_{chatId}/YYYY-MM-DD_msg_{from}_{to}.md`; human-readable Markdown; generated by background summarization (post-MVP); also indexed in archival memory
- **Core memory** — `~/.local/share/klaw/memory/core_memory.json`; read/write via memory tools; can be hand-edited when engine is stopped
- **Skills (system)** — `~/.local/share/klaw/skills/`; skills installed system-wide; lower priority than workspace skills with the same name
- **Databases** — `~/.local/share/klaw/klaw.db` (messages, sessions, embeddings); `~/.local/share/klaw/scheduler.db` (Quartz jobs); both owned exclusively by Engine
- **Sockets and buffers** — `~/.local/state/klaw/engine.sock` (exists when Engine is running); `~/.local/state/klaw/gateway-buffer.jsonl` (buffered messages when Engine was unavailable)
- **Logs** — `~/.local/state/klaw/logs/`; process logs for Gateway and Engine
- **Model cache** — `~/.cache/klaw/models/all-MiniLM-L6-v2/`; ONNX model files (~80 MB); managed automatically
- **Workspace** — `$KLAW_WORKSPACE` (default `~/klaw-workspace/`); the agent has read/write access here via file tools; contains SOUL.md, IDENTITY.md, AGENTS.md, MEMORY.md, HEARTBEAT.md, `skills/`, `memory/`

---

## Quality Check

```bash
./gradlew gateway:ktlintCheck gateway:detekt
./gradlew gateway:test
```
