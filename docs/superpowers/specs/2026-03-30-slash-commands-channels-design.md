# Slash Commands → Channels

**Date:** 2026-03-30
**Status:** Approved

## Context

Klaw поддерживает slash-команды (`/new`, `/model`, `/status`, и т.д.), но их список жёстко прописан в конфигурации gateway (`channels.telegram.commands`) и не связан с реальными обработчиками в Engine. Нет единого источника истины: Engine и Gateway хранят команды независимо.

Цель: сделать Engine источником истины для команд, разбить обработчики на Micronaut-бины, Gateway дополняет список своими командами и регистрирует итоговый список в каждом канале нативным способом.

---

## Компоненты

### 1. `common` — интерфейс `SlashCommand`

**Новый файл:** `common/src/commonMain/kotlin/io/github/klaw/common/command/SlashCommand.kt`

```kotlin
interface SlashCommand {
    val name: String
    val description: String
}
```

`CommandConfig` (в `EngineConfig.kt`) реализует `SlashCommand` — используется для кастомных команд из `engine.json`.

---

### 2. Engine — рефакторинг на бины

**Новый интерфейс** `engine/.../command/EngineSlashCommand.kt`:
```kotlin
interface EngineSlashCommand : SlashCommand {
    suspend fun handle(msg: CommandSocketMessage): OutboundSocketMessage
}
```

**Разбивка `CommandHandler.kt`** — каждая встроенная команда становится отдельным `@Singleton`-бином:

| Бин | Команда |
|-----|---------|
| `NewConversationCommand` | `/new` |
| `ModelCommand` | `/model [id]` |
| `ModelsCommand` | `/models` |
| `MemoryCommand` | `/memory` |
| `StatusCommand` | `/status` |
| `UseForHeartbeatCommand` | `/use-for-heartbeat` |
| `SkillsCommand` | `/skills` |
| `HelpCommand` | `/help` |

`CommandHandler` инжектит `List<EngineSlashCommand>`, диспатчит по `msg.command`.

**`EngineCommandRegistry`** `@Singleton`:
- Инжектит `List<EngineSlashCommand>` (встроенные)
- Читает кастомные из `engine.json` (`config.commands: List<CommandConfig>`) — только метаданные
- `fun allCommands(): List<SlashCommand>` — объединённый список

**Новый API-обработчик** в механизме `EngineApiProxy`:
- Команда: `commands/list`
- Ответ: JSON-массив `[{"name":"new","description":"..."},...]`

---

### 3. Gateway — `GatewayCommandRegistry`

**Новый интерфейс** `gateway/.../command/GatewaySlashCommand.kt`:
```kotlin
interface GatewaySlashCommand : SlashCommand {
    suspend fun handle(msg: IncomingMessage, channel: Channel)
}
```

Gateway-команды рефакторятся в бины: `StartGatewayCommand`, и любые другие gateway-специфичные команды.

**`GatewayCommandRegistry`** `@Singleton`:
- Инжектит `List<GatewaySlashCommand>`
- Pull Engine-команд происходит через `GatewayLifecycle.onBecameAlive` callback (уже используется для аналогичной инициализации). Вызывает `engineApiProxy.send("commands/list")` один раз при первом установлении соединения.
- Объединяет: engine-команды + gateway-команды → кешированный `List<SlashCommand>`
- `fun commands(): List<SlashCommand>`
- При недоступности Engine (ошибка pull) — логирует warn, использует только gateway-команды

**Удаляется** `commands: List<CommandConfig>` из `TelegramChannelConfig` и соответствующая логика в `TelegramChannel`.

---

### 4. Telegram

**`TelegramChannel.start()`:**
- Убрать чтение `config.commands`
- Вызывать `setMyCommands(registry.commands().map { BotCommand(it.name, it.description) })`
- Обработка ошибок регистрации остаётся (warn + continue)

---

### 5. Discord

**Регистрация в `DiscordChannel.start()`:**
```kotlin
kord.createGlobalApplicationCommands {
    registry.commands().forEach { cmd ->
        input(cmd.name, cmd.description)
    }
}
```

**Получение команд** — Discord не присылает slash-команду как текст, а как `ChatInputCommandInteractionCreateEvent`. Добавить listener рядом с существующим `MessageCreateEvent`:
```kotlin
kord.on<ChatInputCommandInteractionCreateEvent> {
    // Собираем args из string-опций (если зарегистрировано) или null
    val args = interaction.command.options
        .filterIsInstance<StringOptionValue>()
        .joinToString(" ") { it.value }
        .ifBlank { null }
    val msg = IncomingMessage(
        isCommand = true,
        commandName = interaction.invokedCommandName,
        commandArgs = args,
        chatId = "discord_${interaction.channelId}",
        userId = interaction.user.id.toString(),
        senderName = interaction.user.username,
        chatType = if (interaction.guildId != null) "guild_text" else "dm",
    )
    // Acknowledge interaction немедленно (Discord требует ответа в течение 3 сек)
    // Реальный ответ придёт через обычный channel.send()
    interaction.deferPublicResponse()
    onMessage(msg)
}
```

> **Важно:** `deferPublicResponse()` отправляет Discord "thinking..." индикатор. Реальный ответ приходит через `channel.send()` обычным путём. Взаимодействие с `interaction.followUp` не нужно — DiscordChannel уже умеет отправлять в канал по `chatId`.

Оба режима инициализации `DiscordChannel` (prod Kord и custom WebSocket-mode для тестов) должны регистрировать slash-команды и listener на interactions.

---

### 6. WebUI / CLI

**Новый endpoint** в `ApiController`:
```
GET /api/v1/commands
```
Ответ: `List<CommandInfo>` (name + description) из `GatewayCommandRegistry.commands()`.

Без изменений в WebSocket-протоколе (`ChatFrame`).

---

## Изменения конфига

**`gateway.json`** — удалить поле `channels.telegram.commands` из схемы и из `TelegramChannelConfig`.

**`engine.json`** — `commands` остаётся как кастомные команды (метаданные без handler).

---

## Затронутые файлы

### common
- `common/src/commonMain/kotlin/io/github/klaw/common/command/SlashCommand.kt` — **новый**
- `common/src/commonMain/kotlin/io/github/klaw/common/config/EngineConfig.kt` — `CommandConfig implements SlashCommand`

### engine
- `engine/src/main/kotlin/io/github/klaw/engine/command/EngineSlashCommand.kt` — **новый интерфейс**
- `engine/src/main/kotlin/io/github/klaw/engine/command/EngineCommandRegistry.kt` — **новый**
- `engine/src/main/kotlin/io/github/klaw/engine/command/CommandHandler.kt` — рефакторинг на DI-диспатч
- `engine/src/main/kotlin/io/github/klaw/engine/command/commands/` — **новый пакет**, 8 бинов
- Engine API handler для `commands/list` — добавляется в `CliCommandDispatcher.kt` (обрабатывает `CliRequestMessage`, аналогично существующим `status`, `models` и т.д.)

### gateway
- `gateway/src/main/kotlin/io/github/klaw/gateway/command/GatewaySlashCommand.kt` — **новый интерфейс**
- `gateway/src/main/kotlin/io/github/klaw/gateway/command/GatewayCommandRegistry.kt` — **новый**
- `gateway/src/main/kotlin/io/github/klaw/gateway/command/commands/` — **новый пакет**, gateway-бины
- `gateway/src/main/kotlin/io/github/klaw/gateway/channel/TelegramChannel.kt` — убрать config.commands, использовать registry
- `gateway/src/main/kotlin/io/github/klaw/gateway/channel/DiscordChannel.kt` — регистрация + InteractionCreateEvent
- `gateway/src/main/kotlin/io/github/klaw/gateway/api/ApiController.kt` — новый endpoint
- `gateway/src/main/kotlin/io/github/klaw/gateway/config/GatewayConfig.kt` — удалить `commands` из `TelegramChannelConfig`

---

## Верификация

1. **Unit тесты Engine:**
   - Каждый command-бин тестируется в изоляции
   - `EngineCommandRegistry.allCommands()` возвращает встроенные + кастомные из конфига
   - `CommandHandler` корректно диспатчит по имени и возвращает "Unknown command" для неизвестных

2. **Unit тесты Gateway:**
   - `GatewayCommandRegistry` корректно объединяет engine + gateway команды
   - Мок `EngineApiProxy` возвращает список, registry его кеширует

3. **Integration тесты:**
   - TelegramChannel вызывает `setMyCommands()` с правильным списком
   - Discord регистрирует команды и обрабатывает `ChatInputCommandInteractionCreateEvent`
   - `GET /api/v1/commands` возвращает объединённый список

4. **E2E:**
   - Обновить `EngineHealthToolE2eTest` если затрагивается health/schema
   - Проверить что slash-команды проходят сквозь систему end-to-end
