# Klaw — Design Document v0.4

> Лёгкий, надёжный, модульный AI-агент для Raspberry Pi 5 с поддержкой китайских LLM.  
> Название рабочее. Klaw — от англ. claw (клешня) 🦞

---

## 1. Мотивация и цели

### Проблема

Существующие "claw"-агенты (OpenClaw, NanoClaw) страдают от фундаментальных архитектурных проблем:

- **OpenClaw**: heartbeat потребляет 170k–210k токенов за запуск, cron-задачи теряются при компакции сессий, ролловер в 4 утра ломает долгие задачи. Монолит на 430k строк, где планировщик, gateway и LLM-сессия делят состояние.
- **NanoClaw**: дедлоки при конкурентном доступе к запланированным задачам, вендор-лок на Claude Agent SDK (никаких китайских LLM), минимальная память без семантического поиска.
- **Letta**: отличная память, но нет встроенного планировщика.
- **Agent Zero**: надёжный планировщик, но тяжёлый для ARM.

Ни один фреймворк не объединяет: надёжный cron, качественную семантическую память, поддержку китайских LLM и эффективную работу на ARM.

### Цели

1. **Надёжный cron/scheduler**, не зависящий от состояния LLM-сессии
2. **Качественная память** с семантическим поиском (sqlite-vec + локальные эмбеддинги)
3. **Поддержка китайских LLM**: GLM-5, DeepSeek, Qwen через OpenAI-compatible API
4. **Работа на Raspberry Pi 5** (16GB RAM)
5. **Исполнение кода** в изолированных Docker-контейнерах (sandboxed)
6. **Расширяемая система skills**
7. **Человекочитаемое хранилище** — файлы как source of truth

### Не-цели

- Поддержка 15+ мессенджеров (начинаем с Telegram + Discord)
- Внешние зависимости для памяти (Letta, PostgreSQL, pgvector)
- Визуальный UI / веб-интерфейс (CLI-first)
- Multi-agent оркестрация (один агент, один пользователь)

---

## 2. Архитектура

### 2.1. Два процесса: Gateway и Engine

Ключевое архитектурное решение: **два процесса — Gateway и Engine**, связанных через Unix domain socket. Каждый процесс владеет своими данными, ни один не разделяет состояние с другими. Scheduler — модуль внутри Engine (Quartz в том же JVM), а не отдельный процесс.

```
┌─────────────────┐
│     Gateway      │
│  (Telegram,      │
│   Discord)       │
└────────┬─────────┘
         │ persistent
         │ JSONL socket
         ▼
┌──────────────────────────────────────────┐
│                Engine                     │
│          (единственный владелец           │
│      klaw.db + sqlite-vec + scheduler.db)│
│                                           │
│  ┌──────────┐ ┌────────┐ ┌─────────────┐ │
│  │ LLM API  │ │Memory │ │ Docker API  │ │
│  │(GLM-5,   │ │sqlite- │ │(code exec)  │ │
│  │ DeepSeek,│ │vec+ONNX│ │             │ │
│  │ Qwen)    │ │        │ │             │ │
│  └──────────┘ └────────┘ └─────────────┘ │
│                                           │
│  ┌──────────────────────────────────────┐ │
│  │ Scheduler (Quartz JDBC, in-process)  │ │
│  └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
         ▲
         │ persistent
         │ JSONL socket
         ▼
┌─────────────────┐
│     Gateway      │
│  (ответы ←       │
│   Engine pushes)  │
└─────────────────┘

CLI ──engine.sock──→ Engine
```

**Почему Scheduler в Engine, а не отдельный процесс и не в Gateway?**

Scheduler — генератор сообщений для Engine по расписанию. Ему нужно знать про модели (`model`), куда inject'ить результат (`injectInto`) — это Engine'овские концепты. Если поместить Scheduler в Gateway, Gateway начнёт знать про бизнес-логику субагентов, нарушится разделение ответственности. Gateway — чистый транспорт. А Scheduler без Engine бесполезен (некуда слать сообщения), поэтому fault isolation отдельного процесса не даёт практической пользы. Экономим ~50MB RAM и один systemd service.

### 2.2. Почему два, а не один

В OpenClaw gateway и engine живут в одном процессе Node.js — "разделение" чисто логическое. Компакция сессии вешает весь gateway, cron-задачи теряются при ролловере.

У нас:

- **Gateway упал** → Engine (включая Scheduler) продолжает работать. Входящие сообщения буферизуются на стороне Telegram/Discord. Scheduled-задачи без `injectInto` выполняются нормально; задачи с `injectInto` логируются, доставка пользователю произойдёт при реконнекте Gateway.
- **Engine упал** → Gateway буферизует сообщения в локальный файл (`gateway-buffer.jsonl`). При реконнекте — досылает из буфера. Quartz misfire recovery при рестарте Engine.
- **Компакция/суммаризация** — фоновая операция engine, не блокирует приём сообщений.

### 2.3. Межпроцессное взаимодействие: persistent JSONL socket

Gateway держит **persistent Unix domain socket** соединение к Engine. Протокол — **JSONL в обе стороны**: каждая строка — JSON-объект. Engine пушит ответы в то же соединение. Scheduler — модуль Engine, общается через internal function calls, не через socket.

```
Gateway ←──persistent JSONL──→ Engine

→ {"type":"inbound","id":"msg_001","channel":"telegram","chatId":"telegram_123456","content":"Привет","ts":"2026-02-24T14:30:00Z"}
→ {"type":"inbound","id":"msg_002","channel":"telegram","chatId":"telegram_789","content":"Как дела","ts":"2026-02-24T14:30:05Z"}
← {"type":"outbound","replyTo":"msg_001","channel":"telegram","chatId":"telegram_123456","content":"Привет! ..."}
← {"type":"outbound","chatId":"telegram_123456","content":"Обнаружено 2 важных письма от клиента","meta":{"source":"heartbeat"}}
```

```
CLI ──engine.sock──→ Engine (request-response, короткоживущие соединения)

→ {"command":"status"}
← {"uptime":"2h30m","sessions":[...],"queue":{"pending":0}}
```

**Буферизация при падении Engine:**

```
Gateway:
  1. Получил сообщение из Telegram
  2. Записал в conversations/ JSONL (всегда, независимо от Engine)
  3. Попытался отправить в engine socket
     → Успех: всё ок
     → Engine недоступен: записал в gateway-buffer.jsonl
  4. При реконнекте: отправить все из буфера ПОСЛЕДОВАТЕЛЬНО (в порядке записи),
     очистить файл. Engine обрабатывает буферные сообщения по одному,
     применяя стандартный debounce per chat_id. Порядок внутри одного chat_id
     гарантирован (JSONL append-only = хронологический порядок).
     Порядок МЕЖДУ разными chat_id не гарантирован (Engine может обработать
     msg от chat_B раньше msg от chat_A, если chat_A был в debounce).
```

Gateway хранит `gateway-buffer.jsonl` в `$XDG_STATE_HOME/klaw/`. Файл append-only, формат идентичен socket-протоколу. При рестарте Gateway проверяет буфер и досылает. Буфер содержит `ts` каждого сообщения — Engine использует его для корректного хронологического размещения в `klaw.db`, даже если сообщение доставлено с задержкой.

### 2.4. Описание компонентов

#### Gateway (~500 строк)

**Ответственность**: приём сообщений из внешних каналов, нормализация в единый формат, доставка ответов обратно в каналы.

- Telegram: через библиотеку `TelegramBotAPI` (InsanusMokrassar), long polling
- Discord: через `Kord`, WebSocket
- Каждый канал — реализация интерфейса `Channel`:

```kotlin
interface Channel {
    val name: String
    suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit)
    suspend fun send(chatId: String, response: OutgoingMessage)
}
```

- Gateway **не знает** про LLM, память, skills, субагенты. Это чистый транспорт.
- Gateway **отправляет** сообщения в Engine через persistent socket и **получает** ответы из того же соединения.
- JSONL — переносимый лог, source of truth. SQLite-индекс (в Engine) восстанавливается из JSONL при необходимости (`klaw reindex`).

**JSONL ownership**: Gateway — **единственный писатель** в `conversations/{chat_id}/*.jsonl` для interactive-каналов (telegram, discord). Всё что приходит от пользователя и всё что уходит пользователю (включая результаты субагентов) — пишет Gateway. Engine в эти файлы не пишет. Engine пишет только в `conversations/scheduler_*/*.jsonl` — опциональные логи субагентов для статистики (см. секцию 2.4 Engine, `logging.subagentConversations`).

**Маршрутизация ответов из Engine:**

Engine пушит в Gateway сообщения типа `type: "outbound"` — Gateway безусловно отправляет пользователю в канал и записывает в JSONL. Gateway **не парсит содержимое** сообщений — решение «отправлять или нет» принимает Engine до отправки (см. silent-логику в секции 2.4 Engine).

**Whitelist исходящих сообщений**: Gateway проверяет пару `channel + chatId` исходящих сообщений по whitelist из `gateway.json` (`allowedChatIds`). chatId привязан к каналу: если `telegram_123456` в whitelist — сообщение с этим chatId разрешено только для канала Telegram. Если chatId не в whitelist или канал не соответствует — сообщение отклоняется, Gateway возвращает ошибку в Engine. Это защита от галлюцинирующего агента, который может попытаться отправить сообщения в произвольные чаты через tool `send_message`.

```kotlin
fun handleOutbound(msg: OutboundMessage) {
    // Проверить whitelist
    if (!isAllowedChatId(msg.chatId)) {
        engineSocket.sendError(msg.replyTo, "chatId ${msg.chatId} not in allowedChatIds")
        return
    }
    
    // Записать в JSONL (Gateway — единственный писатель для interactive-каналов)
    jsonlWriter.append(msg)
    
    // Отправить пользователю
    channel.send(msg.chatId, OutgoingMessage(content = msg.content))
}
```

#### Engine (~1800 строк, включая Scheduler)

**Ответственность**: обработка сообщений, взаимодействие с LLM, управление памятью, исполнение skills, хранение индексов, выполнение задач по расписанию.

**Engine — единственный владелец всех SQLite-баз** (`klaw.db` с sqlite-vec, `scheduler.db` для Quartz). Ни один другой процесс не открывает SQLite напрямую. Это устраняет конкурентный доступ.

Цикл обработки одного сообщения:

```
1. Получить сообщение через socket (от Gateway) или от Scheduler (internal)
2. Debounce: если от того же chat_id за последние N мс пришли ещё сообщения — склеить
3. Записать в klaw.db (таблица messages — индекс для скользящего окна)
4. Собрать контекст (→ раздел 3. Управление контекстом)
5. Вызвать LLM API с контекстом и списком tools
6. Если LLM вернул tool_call:
   a. Найти tool по имени (встроенные tools — in-process)
   b. Выполнить tool:
      - Встроенные (memory_search, file_read, skill_load, ...) → in-process
      - code_execute → Docker-контейнер (sandbox)
   c. Получить результат
   d. Добавить результат в контекст → вернуться к шагу 5
   e. Если превышен maxToolCallRounds → прервать цикл, сообщить об ошибке
7. Отправить ответ:
   - Сообщение от Gateway → ответ в Gateway socket (type: "outbound")
   - Сообщение от Scheduler с injectInto != null:
     → Если ответ не silent → отправить в Gateway socket (type: "outbound")
     → Если silent → только лог в scheduler-JSONL (опционально)
   - Сообщение от Scheduler с injectInto = null → только лог
```

**Кто пишет JSONL**: Gateway — единственный писатель для interactive-каналов (telegram, discord). Engine пишет только `conversations/scheduler_*/*.jsonl` — опциональные логи субагентов для статистики и дебага. Формат записей идентичен interactive-каналам (секция 5.2), с `meta.source: "scheduler"` и `meta.task_name` для идентификации задачи:

```json
{"id":"sched_001","ts":"2026-02-24T09:00:00Z","role":"user","content":"Сделай саммари вчерашних событий","meta":{"source":"scheduler","task_name":"daily-summary","channel":"scheduler","chat_id":"scheduler_daily-summary"}}
{"id":"sched_002","ts":"2026-02-24T09:00:15Z","role":"assistant","content":"{\"silent\": true, \"reason\": \"nothing to report\"}","meta":{"source":"scheduler","task_name":"daily-summary","channel":"scheduler","chat_id":"scheduler_daily-summary","model":"glm/glm-4-plus","tokens_in":3200,"tokens_out":45}}
```

Отключаются через конфиг:

```yaml
# engine.json
logging:
  subagentConversations: true    # false — не писать scheduler JSONL вообще
```

**Silent-логика субагентов**: Engine решает, отправлять ли результат субагента пользователю. LLM субагента возвращает JSON с полем `silent`:

```json
{"silent": true, "reason": "nothing to report"}
```

Если LLM вернул `silent: true` — Engine не отправляет сообщение в Gateway. Если LLM вернул обычный текст (не JSON или без `silent`) — Engine отправляет в Gateway как `type: "outbound"`. Инструкции для LLM описываются в AGENTS.md workspace'а:

```
Если задача heartbeat не требует внимания пользователя — ответь JSON:
{"silent": true, "reason": "краткое описание что проверил"}
```

Это надёжнее exact-match по магическим строкам: LLM возвращает структурный ответ, Engine парсит JSON. Если парсинг не удался — считаем что не silent (safe default: отправить пользователю).

**Debounce сообщений**: если от одного chat_id за `processing.debounceMs` (конфигурируемо, по умолчанию 1500ms) пришло несколько сообщений — Engine склеивает их в одно перед отправкой в LLM. Типичный кейс: пользователь пишет тремя сообщениями подряд "посмотри" "вот этот файл" "и скажи что думаешь". Engine ждёт паузу, склеивает, обрабатывает как одно сообщение.

```yaml
# engine.json
processing:
  debounceMs: 1500               # склеить сообщения от одного chat_id за это время
  maxConcurrentLlm: 2            # макс параллельных LLM-запросов (interactive + subagent)
  maxToolCallRounds: 10          # макс итераций tool call loop (защита от зацикливания)
```

**Rate limiting**: Engine ограничивает количество параллельных LLM-запросов через семафор (`maxConcurrentLlm`). Interactive-сообщения имеют приоритет выше субагентов: Engine использует приоритетную очередь, где interactive-запросы вытесняют субагентов. Если оба слота заняты субагентами и приходит interactive-сообщение, interactive ждёт ближайшего освободившегося слота (субагент не прерывается mid-request, но следующий слот отдаётся interactive).

**Порядок: debounce → очередь на LLM-слот.** Debounce и rate limiting — разные стадии конвейера, не конкурируют. Debounce работает на уровне приёма сообщений (до обработки), семафор — на уровне вызова LLM:

```
1. Сообщение приходит из socket → записывается в klaw.db
2. Debounce timer стартует/рестартится для chat_id
3. Пока идёт debounce → новые сообщения от того же chat_id
   НЕ встают в очередь на LLM-слот, а накапливаются в debounce-буфере
4. Debounce timer истёк (1500ms без новых сообщений от chat_id)
   → Все накопленные сообщения склеиваются в одно
5. Склеенное сообщение встаёт в приоритетную очередь на LLM-слот
   (interactive > subagent)
6. Когда слот освобождается → контекст → LLM
```

Пример: пользователь пишет 3 сообщения с паузой 1с каждое, оба LLM-слота заняты.
Debounce собирает все три (1с < 1.5с порога между каждым), через 1.5с после последнего
сообщения одно склеенное сообщение встаёт в очередь и ждёт слот. Если бы пауза между
сообщениями была >1.5с — первое ушло бы в очередь сразу, остальные — отдельными запросами.

**Tool call loop protection**: Если LLM возвращает tool_call более `maxToolCallRounds` раз подряд — цикл прерывается, Engine отправляет пользователю сообщение об ошибке: «Достигнут лимит вызовов инструментов. Попробуйте переформулировать запрос.» Это защита от зацикливания, которое бывает у китайских моделей с нестабильным function calling.

Сохранение фактов в archival memory (embed → sqlite-vec) происходит **не автоматически**, а через tool call `memory_save` на шаге 6 — LLM сам решает, что стоит запомнить. Это поведение описывается в SOUL.md / AGENTS.md.

Взаимодействия engine с внешними системами:

| Система | Протокол | Назначение |
|---------|----------|------------|
| LLM API | HTTP (OpenAI-compatible) | Генерация ответов, tool calling |
| sqlite-vec + ONNX | Embedded (in-process) | Архивная память, семантический поиск |
| Docker | Unix socket `/var/run/docker.sock` | Исполнение кода (`code_execute`) в sandbox |
| SQLite (`klaw.db`) | Embedded (in-process) | Индекс сообщений, сессии, vec, FTS |
| SQLite (`scheduler.db`) | Embedded (in-process) | Quartz JDBC JobStore |
| Файловая система | Direct I/O | JSONL логи (scheduler), саммари, конфиги |
| Gateway | Unix domain socket | Приём сообщений, отправка ответов |

**Engine socket server**:

Engine при старте слушает `$XDG_STATE_HOME/klaw/engine.sock`. Принимает два типа клиентов:

1. **Gateway** — persistent connection, регистрируется при подключении: `{"type":"register","client":"gateway"}`. Engine пушит ответы в это соединение.
2. **CLI** — короткоживущие соединения, request-response.

```kotlin
// Engine при старте
val socketPath = KlawPaths.state / "engine.sock"
val server = UnixServerSocketChannel.open()
server.bind(UnixDomainSocketAddress.of(socketPath))

// Обработка подключений
when (registration.client) {
    "gateway" -> gatewayConnection = connection  // сохранить для push'а ответов
    else      -> handleCliRequest(connection)    // request-response, закрыть
}
```

Сокет защищён правами файловой системы (`chmod 600`) — доступен только владельцу процесса. Gateway и Engine должны работать под одним пользователем (или использовать общую группу).

**Graceful shutdown**:

При `systemctl stop klaw-engine`:
1. Quartz `scheduler.shutdown(waitForJobsToComplete = true)` — ждёт завершения текущих scheduled jobs
2. Engine перестаёт принимать новые сообщения из socket (отправляет `{"type":"shutdown"}` в Gateway)
3. Ожидание завершения текущих LLM-вызовов (таймаут: 60 секунд)
4. Если mid-flight tool call loop не завершился за таймаут — логируем состояние, корутина отменяется
5. Закрытие SQLite-соединений (`klaw.db`, `scheduler.db`)
6. Удаление `engine.sock`

При потере питания (типичный сценарий для Pi 5 на SD-карте): SQLite WAL обеспечивает целостность баз, JSONL append-only не повреждается (последняя неполная строка игнорируется при чтении), Quartz misfire recovery при следующем старте.

#### Scheduler (модуль Engine, ~300 строк)

**Ответственность**: выполнение задач по расписанию — генерирует сообщения для Engine по cron-выражениям.

Scheduler — **модуль внутри Engine** (не отдельный процесс). Построен на [Quartz Scheduler](http://www.quartz-scheduler.org/) с JDBC JobStore (SQLite). Для работы с SQLite используется кастомный `SQLiteDelegate`, переопределяющий Quartz `StdJDBCDelegate` для совместимости (SQLite не поддерживает `SELECT ... FOR UPDATE` — используется `BEGIN IMMEDIATE` вместо row-level locking). Режим: single-node (`org.quartz.jobStore.isClustered = false`), без `QRTZ_LOCKS`.

Quartz берёт на себя cron-парсинг, misfire handling, персистентность задач — не нужно писать свой cron loop.

Задачи хранятся в стандартных таблицах Quartz в `$XDG_DATA_HOME/klaw/scheduler.db` — **Engine единственный владелец этой базы** (как и `klaw.db`). Управляются через tools (`schedule_add`, `schedule_remove`), CLI (`klaw schedule add`) или импорт HEARTBEAT.md.

```kotlin
// Единственный Job — вызывает Engine напрямую (in-process)
class ScheduledMessageJob : Job {
    override fun execute(context: JobExecutionContext) {
        val data = context.mergedJobDataMap
        val message = ScheduledMessage(
            name = data.getString("name"),
            message = data.getString("message"),
            model = data.getString("model"),         // модель для субагента
            injectInto = data.getString("injectInto"),
        )
        // Прямой вызов Engine — не через socket, а через internal API
        engine.handleScheduledMessage(message)
    }
}

// Добавление задачи — стандартный Quartz API
fun addSchedule(name: String, cron: String, message: String, model: String?, injectInto: String?) {
    val job = JobBuilder.newJob(ScheduledMessageJob::class.java)
        .withIdentity(name)
        .usingJobData("name", name)
        .usingJobData("message", message)
        .usingJobData("model", model)
        .usingJobData("injectInto", injectInto)
        .storeDurably()
        .build()
    val trigger = TriggerBuilder.newTrigger()
        .withIdentity(name)
        .withSchedule(CronScheduleBuilder.cronSchedule(cron)
            .withMisfireHandlingInstructionFireAndProceed())
        .build()
    scheduler.scheduleJob(job, trigger)
}
```

**Все задачи persistent** — хранятся в Quartz JDBC JobStore (SQLite), переживают рестарт. Нет разделения на "runtime" и "persistent": `schedule_add` → запись в Quartz, `schedule_remove` → удаление из Quartz.

Scheduler **не знает** про LLM, контексты, субагенты. Он просто генерирует `ScheduledMessage` и передаёт Engine. Engine решает, как обработать.

**Субагентная модель (Engine)**: cron/heartbeat задачи выполняются в Engine **параллельно** с основной сессией. В 90% случаев им не нужен текущий контекст разговора — только базовый контекст. LLM-провайдеру всё равно, сколько параллельных запросов прилетит.

**Модель субагента**: каждая scheduled-задача имеет привязанную модель (`model` в Quartz JobData). Задаётся при создании через `schedule_add` tool, CLI, или конфиг. Если не задана — используется `routing.tasks.subagent` из `engine.json`.

```
Основная сессия (interactive):           Субагент (heartbeat):
┌────────────────────────────┐           ┌────────────────────────────┐
│ System prompt (SOUL/AGENTS)│ ← общий → │ System prompt (SOUL/AGENTS)│
│ Core memory                │ ← общий → │ Core memory                │
│ memory_search (on-demand)  │ ← общий → │ memory_search (on-demand)  │
│ Последние 20 сообщений     │ ← СВОЁ    │ Последние 5 heartbeat'ов   │ ← СВОЁ
│ Модель из session          │ ← СВОЁ    │ Модель из задачи/конфига   │ ← СВОЁ
│ Tools/Skills               │ ← общий → │ Tools/Skills               │
└────────────────────────────┘           └────────────────────────────┘
         ↕ параллельно ↕
```

Engine обрабатывает интерактивные и cron-задачи в **параллельных корутинах**, каждая со своим собранным контекстом.

**Результат субагента → пользователю**: Engine проверяет ответ субагента на наличие `silent` флага. Если LLM вернул JSON с `{"silent": true}` — Engine логирует результат в scheduler-JSONL (если включен), но не отправляет в Gateway. Если ответ не содержит `silent: true` (обычный текст или JSON без этого флага) и `injectInto != null` — Engine отправляет в Gateway как `type: "outbound"` для доставки пользователю.

Если `inject_into` = null — результат логируется в JSONL scheduler-канала (опционально), в Gateway не отправляется.

**Пример задач** (хранятся в Quartz JDBC JobStore `scheduler.db`):

```
name                cron                    message                              model              inject_into
daily-summary       0 0 9 * * ?             Сделай саммари вчерашних событий     glm/glm-4-plus     telegram_123456
health-check        0 */30 * * * ?          Проверь статус сервисов              glm/glm-4-plus     null
memory-compaction   0 0 3 * * ?             summarize_old_messages               ollama/qwen3:8b    null
```

Примечание: cron в формате Quartz (7 полей: секунды минуты часы день месяц день_недели [год]).

Задачи добавляются через: tool `schedule_add` (LLM), CLI `klaw schedule add`, импорт HEARTBEAT.md.

**Конкурентный доступ к памяти**:

| Операция | Основная сессия | Субагент | Конфликт? |
|----------|----------------|----------|-----------|
| Чтение core memory | ✅ | ✅ | Нет — оба читают |
| Запись core memory | ✅ (tool call) | ❌ (read-only) | Нет |
| Чтение archival (sqlite-vec) | ✅ | ✅ | Нет — Engine единственный владелец |
| Запись archival | ✅ | ✅ | Нет — Engine единственный владелец, внутренняя сериализация |
| Чтение conversation log | Своя сессия | Своя сессия | Нет — разные chat_id |

Engine — единственный процесс работающий с `klaw.db`. Конкурентность между корутинами управляется внутри Engine (Kotlin coroutines + dispatcher).

**Жизненный цикл субагента** — это полноценный агентный цикл, идентичный обработке интерактивного сообщения, но в изолированном контексте на отдельной корутине:

```
Quartz fires "daily-summary"
  → Scheduler (in-process) вызывает engine.handleScheduledMessage():
    ScheduledMessage(name="daily-summary", message="Сделай саммари...", model="glm/glm-4-plus", injectInto="telegram_123456")
  → Engine спавнит корутину:
  
    Субагент "daily-summary" (отдельная корутина):
    ┌─────────────────────────────────────────────────────────────┐
    │ 1. Собрать СВОЙ контекст:                                   │
    │    - System prompt (SOUL.md, AGENTS.md — общий)              │
    │    - Core memory (общая, read-only)                         │
    │    - Последние 5 сообщений ИЗ СВОЕГО лога                    │
    │      (conversations/scheduler_daily-summary/*.jsonl)         │
    │    - Tools/Skills (общие, включая memory_search)             │
    │                                                              │
    │ 2. Вызвать LLM (модель из задачи: glm/glm-4-plus)          │
    │                                                              │
    │ 3. Если LLM вернул tool_call → выполнить → добавить в       │
    │    контекст → вернуться к шагу 2 (полный tool call loop)     │
    │                                                              │
    │ 4. Получить финальный ответ от LLM                           │
    │                                                              │
    │ 5. Записать всё (запрос + ответ + tool calls) в СВОЙ JSONL   │
    │    (опционально, если logging.subagentConversations: true)   │
    │                                                              │
    │ 6. Если injectInto != null:                                  │
    │    → Проверить ответ на silent: JSON {"silent": true} → пропустить│
    │    → Если не silent → отправить в Gateway socket как outbound │
    │    → Gateway: записать в JSONL целевого chat_id               │
    │    → При следующем вызове основной сессии telegram_123456     │
    │      результат появится в sliding window как обычное сообщение│
    │                                                              │
    │ 7. Корутина завершается. Контекст освобождается.             │
    └─────────────────────────────────────────────────────────────┘

    Параллельно основная сессия продолжает работать как обычно.
    Субагент НИКАК не блокирует интерактивный чат.
```

---

## 3. Управление контекстом и памятью

### 3.1. Принцип: сессия ≠ история сообщений

В OpenClaw сессия — это и контекстное окно, и история, и состояние cron. Мутация одного ломает остальное.

У нас три отдельных слоя:

| Слой | Хранение | Мутабельность | Назначение |
|------|----------|---------------|------------|
| **Conversation Log** | JSONL файлы (source of truth) + `klaw.db` messages (индекс) | Append-only, иммутабельный | Полная история всех сообщений |
| **LLM Context Window** | Собирается на лету | Эфемерный, пересобирается каждый вызов | То, что реально уходит в LLM |
| **Long-term Memory** | sqlite-vec + core_memory.json + JSONL | Append-only + self-edit | Семантическая память агента |

### 3.2. Модель данных: chat → segment → session

```
channel (telegram, discord, scheduler)
  └── chat_id (123456)              ← conversation log (JSONL файл по дням)
        │                              один чат = один непрерывный лог
        ├── segment_0               ← сообщения от начала
        ├── [session_break]         ← маркер /new
        ├── segment_1               ← сообщения после первого /new
        ├── [session_break]
        └── segment_2 (текущий)     ← sliding window читает только отсюда
```

| Понятие | Привязка | Жизненный цикл | Хранение |
|---------|----------|----------------|----------|
| **Chat** | `chat_id` (один Telegram-чат) | Бессрочный | JSONL: `conversations/{chat_id}/YYYY-MM-DD.jsonl` + `klaw.db` messages |
| **Segment** | Часть chat между маркерами `[session_break]` | От `/new` до `/new` | Маркеры в JSONL и `klaw.db` |
| **Session** | chat_id + текущий segment + runtime state | Пока engine работает | Таблица `sessions` в `klaw.db` |

Session — это runtime-состояние, привязанное к chat_id. Хранится в `klaw.db` (таблица `sessions`), восстанавливается из JSONL при необходимости (модель берётся из `routing.default`, `segmentStart` вычисляется по последнему `session_break`).

```sql
-- В klaw.db (Engine — единственный владелец)
CREATE TABLE sessions (
    chat_id TEXT PRIMARY KEY,
    model TEXT NOT NULL,              -- "glm/glm-5"
    segment_start TEXT NOT NULL,      -- id первого сообщения текущего сегмента
    created_at TIMESTAMP NOT NULL
);
```

**Что привязано к chat_id (переживает `/new`):**
- Conversation log (JSONL файлы — append-only, всё хранится)
- Текущая модель (`session.model`)
- Core memory (общая для всех чатов — это память агента, не сессии)
- Archival memory (общая — sqlite-vec)

**Что привязано к segment (сбрасывается при `/new`):**
- Sliding window (последние N сообщений — только из текущего сегмента)
- Last summary (саммари предыдущих сообщений текущего сегмента)

**`/new` записывает маркер и сдвигает offset:**

```json
{"id":"msg_041","ts":"...","role":"system","type":"session_break","meta":{"reason":"user_reset"}}
```

Engine при сборке sliding window читает из `klaw.db` (таблица `messages`) от конца и останавливается на первом `session_break`. Саммари текущего сегмента — только из сообщений после маркера.

**Полная цепочка: от входящего сообщения до LLM-вызова**

```
Telegram сообщение от пользователя
  │
  ▼
Gateway нормализует, записывает в JSONL, отправляет в Engine socket
  │
  ▼
Engine получает сообщение через socket
  │  Записывает в klaw.db (таблица messages)
  ▼
chat_id = "{channel}_{platform_id}"        # "telegram_123456"
  │
  ▼
Session = sessions.get(chat_id)
  │  Нет сессии? → создать с model=routing.default, segment_start=текущий msg
  │  Есть? → взять model и segment_start из таблицы sessions
  ▼
model = session.model                      # "glm/glm-5"
  │
  ▼
contextBudget = models[model].contextBudget  # 12000 токенов
  │  Определяет сколько токенов отдать под контекст
  ▼
Сборка контекста (см. 3.3):
  system prompt + core memory + summary     ≈ 1500 токенов (фиксировано)
  tools/skills descriptions                 ≈ 500 токенов (фиксировано)
  ─────────────────────────────────────────
  остаток = contextBudget - фиксированные   ≈ 10000 токенов
  │
  ▼
slidingWindow = min(context.slidingWindow, подогнано под остаток)
  │  Читает последние N сообщений из klaw.db (таблица messages)
  │  где chat_id = текущий и created_at >= segment_start
  ▼
LLM вызов: router.chat(request, model)
  │  Router: model → provider → client → fallback chain
  ▼
Ответ → отправить через Gateway socket
```

### 3.3. Сборка контекстного окна

Перед каждым вызовом LLM engine собирает контекст:

```
┌─────────────────────────────────────────────────┐
│ 1. System prompt                    (~500 tokens)│
│    - Персона агента (из SOUL.md, IDENTITY.md)    │
│    - Инструкции (из AGENTS.md)                   │
│    - Текущая дата/время                          │
│    - Инструкция: "Используй memory_search для    │
│      доступа к долгосрочной памяти"              │
├─────────────────────────────────────────────────┤
│ 2. Core Memory (core_memory.json)   (~500 tokens)│
│    - Факты о пользователе (из USER.md)           │
│    - Заученные правила                           │
├─────────────────────────────────────────────────┤
│ 3. Саммари (compaction)        (summaryBudget)   │
│    - "Ранее в этом разговоре: ..."               │
│    - Генерируются фоновой компакцией (см. 3.4)   │
│    - Бюджет: summaryBudgetFraction × budget (25%) │
├─────────────────────────────────────────────────┤
│ 4. Последние N сообщений           (~3000 tokens)│
│    - Из klaw.db (таблица messages)               │
│    - N — min(slidingWindow, оставшийся бюджет)   │
│    - Только из текущего сегмента (после /new)    │
├─────────────────────────────────────────────────┤
│ 5. Доступные tools/skills           (~500 tokens)│
│    - Описания в формате function calling          │
│    - Включает memory_search для on-demand поиска │
└─────────────────────────────────────────────────┘
                                    Итого: ~5000 tokens
```

**Архивная память — on-demand через tool call.** В отличие от постоянной загрузки результатов архивного поиска в контекст (~1000 токенов при каждом вызове), LLM сам решает, когда ему нужна долгосрочная память, и вызывает tool `memory_search`. Результат приходит как tool result и добавляется в контекст текущего вызова.

Выигрыш двойной:
- **Меньше шума в контексте**: "привет, как дела?" не тратит 1000 токенов на нерелевантные архивные результаты
- **Больше места для sliding window**: +1000 токенов → больше недавних сообщений в контексте

Инструкция для LLM описывается в AGENTS.md workspace'а: *"Ты имеешь доступ к долгосрочной памяти через memory_search. Используй когда пользователь ссылается на прошлые разговоры, просит вспомнить что-то, или когда контекст в скользящем окне недостаточен."*

**Фоновая компакция (см. 3.4).** Контекст пересобирается каждый раз. Conversation log растёт бесконечно (append-only JSONL). Старые сообщения не удаляются из лога — компакция создаёт саммари, которые заменяют старые сообщения в контекстном окне. Триггер основан на доле бюджета (`compactionThresholdFraction`), а не на фиксированном пороге токенов.

**Подсчёт токенов**: точный подсчёт зависит от токенизатора модели (tiktoken для OpenAI-compatible, sentencepiece для других). Для MVP — приближённый подсчёт: `длина_текста / 3.5` для английского, `длина_текста / 2` для русского/китайского. Это даёт ±15% точности. **⚠️ Tech debt**: ±15% означает, что ~15% вызовов могут обрезать нужный контекст (недооценка) или приблизиться к лимиту модели (переоценка). Митигация для MVP: закладывать 10% safety margin в contextBudget (т.е. реально использовать 90% от заявленного бюджета). Точный подсчёт через HuggingFace Tokenizer (уже в зависимостях engine для ONNX) — Post-MVP оптимизация.

### 3.4. Фоновая компакция (Background Compaction)

Вместо порогового подхода OpenClaw (tokenThreshold — фиксированное число токенов), Klaw использует **fraction-based** систему компакции. Оригинальные сообщения **остаются в логе навсегда** — компакция создаёт саммари, но не удаляет исходные данные.

**Конфигурация (fraction-based model):**
- `summaryBudgetFraction` (default `0.25`) — доля бюджета, отведённая под саммари (25%)
- `compactionThresholdFraction` (default `0.5`) — зона компакции = самые старые сообщения до `budget × fraction` токенов

**Триггер компакции:**
```
messageTokens > budget × (summaryBudgetFraction + compactionThresholdFraction)
```

Когда суммарные токены сообщений в сегменте превышают `budget × (summaryBudgetFraction + compactionThresholdFraction)`, engine запускает фоновую задачу компакции.

**Ключевые свойства:**
- **Zero gap**: сообщения остаются в контексте до завершения компакции — саммари заменяет их только после успешного создания. Нет «дыры» в контексте между удалением старых сообщений и появлением саммари.
- **Summary eviction**: когда саммари превышают `summaryBudgetFraction × budget` токенов, самые старые саммари вытесняются из контекста.
- **Auto-RAG guard**: auto-RAG активируется когда саммари были вытеснены из контекста (а не при превышении бюджета).
- **Валидация**: `summaryBudgetFraction + compactionThresholdFraction < 1.0`

**Жизненный цикл компакции:**
1. Найти `coverageEnd` = `max(to_created_at)` из ВСЕХ саммари в сегменте
2. Загрузить ВСЕ сообщения после `coverageEnd` — без ограничения по бюджету
3. Включить саммари с учётом бюджета (новейшие в пределах `summaryBudget`; старейшие вытесняются)
4. Проверить триггер → запустить фоновую компакцию если нужно
5. Auto-RAG: активируется когда саммари вытеснены из контекста

**Старая конфигурация `tokenThreshold` удалена** — заменена на `compactionThresholdFraction`.

```
data/summaries/telegram_123456/
  2026-02-24_msg_001_050.md
  2026-02-24_msg_051_100.md
```

### 3.5. Память: sqlite-vec + локальная embedding-модель

Вместо внешнего сервиса (Letta) — полностью автономная система памяти внутри engine.

**Embedding-модель**: `all-MiniLM-L6-v2` через ONNX Runtime на JVM. Модель весит ~80MB, генерирует 384-мерные вектора, работает на ARM64 без GPU. Инференс одного предложения ~5–15ms на Pi 5.

```kotlin
// Engine (JVM-only) — ONNX Runtime + DJL HuggingFace Tokenizer
val env = OrtEnvironment.getEnvironment()
val session = env.createSession("models/all-MiniLM-L6-v2/model.onnx")
val tokenizer = HuggingFaceTokenizer.newInstance("models/all-MiniLM-L6-v2/tokenizer.json")

fun embed(text: String): FloatArray {
    val encoding = tokenizer.encode(text)
    // ... tokenize → run ONNX → mean pooling → L2 normalize
    return normalized384dVector
}
```

**Альтернатива (проще)**: Ollama как embedding-сервер — `ollama pull all-minilm:l6-v2`, вызов через HTTP `POST /api/embed`. Плюс: нулевой код для инференса. Минус: ещё одна зависимость.

**Векторный поиск**: `sqlite-vec` — расширение SQLite на чистом C, без зависимостей, работает на Pi/ARM/WASM. Наследник sqlite-vss (который заброшен). Brute-force KNN, но для масштабов персонального агента (десятки тысяч чанков) — достаточно быстро (~60ms на 100k записей 384d).

```sql
-- В klaw.db — векторный индекс для архивной памяти
CREATE VIRTUAL TABLE vec_memory USING vec0(
    embedding float[384]
);

-- Метаданные чанков
CREATE TABLE memory_chunks (
    id INTEGER PRIMARY KEY,
    source TEXT NOT NULL,       -- 'MEMORY.md', 'memory/2026-02-24.md', 'conversation'
    chat_id TEXT,               -- NULL для workspace-файлов
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- Вставка
INSERT INTO memory_chunks(content, source, created_at) VALUES (?, ?, ?);
INSERT INTO vec_memory(rowid, embedding) VALUES (last_insert_rowid(), ?);

-- Семантический поиск: top-K ближайших
SELECT mc.content, mc.source, mc.created_at, vm.distance
FROM vec_memory vm
JOIN memory_chunks mc ON mc.id = vm.rowid
WHERE vm.embedding MATCH ?     -- query embedding
ORDER BY vm.distance
LIMIT 10;
```

**Гибридный поиск (vector + FTS5)**: для лучшего recall — комбинируем семантический поиск с полнотекстовым, ранжируем через Reciprocal Rank Fusion (k=60):

```kotlin
fun hybridSearch(query: String, topK: Int = 10): List<MemoryChunk> {
    val queryEmbedding = embed(query)
    
    // 1. Семантический поиск (sqlite-vec)
    val vectorResults = vectorSearch(queryEmbedding, topK = 20)
    
    // 2. Полнотекстовый поиск (FTS5)
    val ftsResults = ftsSearch(query, topK = 20)
    
    // 3. RRF: score = Σ 1/(k + rank_i), k=60
    return reciprocalRankFusion(vectorResults, ftsResults, k = 60)
        .take(topK)
}
```

**Трёхуровневая память (аналог Letta, но своя):**

| Уровень | Хранение | Загрузка | Обновление |
|---------|----------|----------|------------|
| **Core Memory** | `core_memory.json` | Каждый вызов LLM — в system prompt | LLM вызывает tool `memory_core_update` |
| **Archival Memory** | `sqlite-vec` + `memory_chunks` таблица в `klaw.db` | По запросу — LLM вызывает tool `memory_search` | LLM вызывает tool `memory_save`, фоновая индексация |
| **Recall Memory** | JSONL conversation log + таблица `messages` в `klaw.db` | Скользящее окно последних N сообщений | Append-only, автоматически |

**Core Memory** — JSON-файл с структурированными фактами о пользователе и агенте. Всегда в контексте LLM. Агент может обновлять через tool call:

```json
{
  "user": {
    "name": "Roman",
    "location": "Prague, CZ",
    "occupation": "KMP developer, sports trainer",
    "preferences": "Отвечай на русском. Без лишней воды.",
    "current_projects": ["trainer app MVP", "Docker on RPi5"]
  },
  "agent": {
    "personality_notes": "User prefers structured, technical answers",
    "learned_rules": ["Don't suggest Spring Boot", "RPi5 has 16GB RAM"]
  }
}
```

**Архивная память** — чанки по ~400 токенов из MEMORY.md, daily logs, разговоров. Индексируются в sqlite-vec при записи. LLM запрашивает их on-demand через tool `memory_search`.

**Чанкинг**: ~400 токенов с 80-токенным перекрытием (как у OpenClaw). Markdown-aware: не ломаем заголовки, списки, блоки кода.

**Зависимости (engine, JVM-only):**

```kotlin
// engine/build.gradle.kts
implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")  // ONNX Runtime (ARM64 поддержка)
implementation("ai.djl.huggingface:tokenizers:0.30.0")           // HF Tokenizer для JVM
// sqlite-vec загружается как native расширение при открытии SQLite
```

**Потребление ресурсов на Pi 5:**

| Компонент | RAM | Диск |
|-----------|-----|------|
| ONNX Runtime + модель | ~150MB | ~80MB |
| sqlite-vec индекс (10k чанков) | ~15MB | ~6MB |
| FTS5 индекс | ~5MB | ~3MB |
| **Итого** | **~170MB** | **~89MB** |

---

## 4. Совместимость с OpenClaw workspace

### 4.1. Принцип

Klaw читает и использует стандартные workspace-файлы OpenClaw без модификации. Пользователь может взять существующий `~/.openclaw/workspace/` и указать его как рабочую директорию — агент подхватит персону, память и настройки. Это обеспечивает безболезненную миграцию и обратную совместимость с экосистемой OpenClaw (шаблоны SOUL.md, community skills и т.д.).

### 4.2. Маппинг файлов OpenClaw → Klaw

```
OpenClaw workspace:                    Как используется в Klaw:
─────────────────────────────────────────────────────────────────────────
AGENTS.md          (инструкции)     → System prompt: основной блок инструкций
SOUL.md            (личность)       → System prompt: блок «кто ты»
IDENTITY.md        (презентация)    → System prompt: имя, визуал, тон
USER.md            (о пользователе) → Core memory: загружается в core_memory.json
TOOLS.md           (заметки о тулах)→ System prompt: контекст окружения
MEMORY.md          (долгосрочная)   → Archival memory: чанкуется и индексируется в sqlite-vec
HEARTBEAT.md       (чеклист)        → Scheduler: парсится в задачи cron
BOOT.md            (ритуал старта)  → Engine: выполняется при первом запуске сессии
BOOTSTRAP.md       (онбординг)      → CLI: интерактивное создание workspace
memory/YYYY-MM-DD.md (дневные логи) → Archival memory: чанкуются и индексируются в sqlite-vec
skills/            (навыки)         → Skills: читаются SKILL.md, progressive disclosure
```

### 4.3. Сборка system prompt из workspace-файлов

Engine при старте и перед каждым вызовом LLM собирает system prompt из файлов в определённом порядке. Порядок соответствует приоритетам OpenClaw (cascade resolution):

```kotlin
fun buildSystemPrompt(workspace: Path): String = buildString {
    // 1. SOUL.md — философия, ценности, характер
    appendFileIfExists(workspace / "SOUL.md", header = "## Soul")

    // 2. IDENTITY.md — имя, визуал, тон общения
    appendFileIfExists(workspace / "IDENTITY.md", header = "## Identity")

    // 3. AGENTS.md — операционные инструкции, приоритеты, границы
    appendFileIfExists(workspace / "AGENTS.md", header = "## Instructions")

    // 4. TOOLS.md — заметки об окружении, SSH-хосты, API-квирки
    appendFileIfExists(workspace / "TOOLS.md", header = "## Environment Notes")

    // 5. USER.md — информация о пользователе
    //    Включается в system prompt для OpenClaw-совместимости.
    //    core_memory.json — superset USER.md (содержит те же данные + дополнения агента).
    //    Дублирование намеренное: USER.md остаётся источником для OpenClaw-экосистемы,
    //    core_memory.json — рабочий формат Klaw.
    //
    //    Source of truth: core_memory.json. При конфликте core_memory.json приоритетнее.
    //    Обратная запись в USER.md (секция 4.6) генерирует Markdown из секции
    //    core_memory.json["user"], сохраняя человекочитаемый формат OpenClaw.
    //    USER.md НЕ читается обратно после первого импорта — только core_memory.json.
    appendFileIfExists(workspace / "USER.md", header = "## About the User")

    // 6. Динамический контекст (дата, время, активный канал)
    append("\n## Current Context\n")
    append("Date: ${LocalDate.now()}\n")
    append("Time: ${LocalTime.now()}\n")
}
```

### 4.4. Маппинг HEARTBEAT.md → Scheduler

OpenClaw использует `HEARTBEAT.md` как чеклист, который агент выполняет периодически. В Klaw heartbeat — это **субагент** с изолированным контекстом:

```yaml
# Автогенерируется из HEARTBEAT.md при старте
tasks:
  - name: heartbeat
    cron: "0 0 */1 * * ?"     # Quartz формат (6-7 полей)
    message: |
      Выполни чеклист из HEARTBEAT.md:
      {{ содержимое HEARTBEAT.md }}
      
      Если ничего не требует внимания пользователя — ответь JSON:
      {"silent": true, "reason": "краткое описание что проверил"}
    model: glm/glm-4-plus
    inject_into: "telegram_123456"
```

Ключевые отличия от OpenClaw:
- Heartbeat **не загружает контекст основной сессии** — у него свой изолированный контекст с базовым system prompt и своей короткой историей
- Heartbeat **не потребляет 170k+ токенов** — только ~2–3k на базовый контекст + чеклист
- Heartbeat **не блокирует** интерактивный разговор — работает параллельно
- Результат heartbeat'а **отправляется пользователю** (если не содержит `"silent": true`)

### 4.5. Маппинг MEMORY.md и daily logs → sqlite-vec

При первом запуске (или по команде `klaw import`) engine индексирует существующие файлы памяти OpenClaw в sqlite-vec:

```
MEMORY.md                    → Archival memory (чанками по ~400 токенов → embed → sqlite-vec)
memory/YYYY-MM-DD.md         → Archival memory (с привязкой к дате, чанки → embed → sqlite-vec)
USER.md                      → Core memory (парсится в core_memory.json)
```

После импорта Klaw продолжает писать собственные JSONL-логи и Markdown-саммари параллельно, сохраняя совместимость с инструментами экосистемы OpenClaw (memory-viewer и т.д.).

### 4.6. Обратная запись (опционально)

Для пользователей, которые хотят сохранить возможность вернуться к OpenClaw или использовать оба параллельно, engine может опционально записывать обновления обратно в формат OpenClaw:

```yaml
# engine.json
compatibility:
  openclaw:
    enabled: true
    sync:
      memory_md: true      # обновлять MEMORY.md
      daily_logs: true      # писать memory/YYYY-MM-DD.md
      user_md: true         # обновлять USER.md из core_memory.json["user"]
                             # Формат: Markdown с ключами как заголовками, значениями как текстом.
                             # Пример: "## Name\nRoman\n\n## Location\nPrague, CZ\n"
                             # USER.md после первого импорта — write-only для Klaw.
                             # Изменения в USER.md вручную НЕ подхватываются — редактируйте
                             # core_memory.json или используйте tool memory_core_update.
```

При `KLAW_WORKSPACE=~/.openclaw/workspace` Klaw работает напрямую с OpenClaw workspace — sync записывает обратно в те же файлы.

### 4.7. Структура workspace

Workspace — это только файлы агента (персона, память, навыки). Конфигурация и рантайм-данные живут отдельно по XDG-путям (см. секцию 9).

```
workspace/                              # $KLAW_WORKSPACE или --workspace
├── AGENTS.md                           # ← OpenClaw-совместимый
├── SOUL.md                             # ← OpenClaw-совместимый
├── IDENTITY.md                         # ← OpenClaw-совместимый
├── USER.md                             # ← OpenClaw-совместимый
├── TOOLS.md                            # ← OpenClaw-совместимый
├── MEMORY.md                           # ← OpenClaw-совместимый
├── HEARTBEAT.md                        # ← OpenClaw-совместимый
├── BOOT.md                             # ← OpenClaw-совместимый
├── memory/                             # ← OpenClaw-совместимый
│   ├── 2026-02-24.md
│   └── 2026-02-25.md
└── skills/                             # ← OpenClaw-совместимый (читает SKILL.md)
    └── web-search/
        ├── SKILL.md                    #    Инструкции + метаданные
        └── scripts/                    #    Опциональные скрипты
            └── search.sh
```

Workspace чистый — никаких `.klaw/`, `config/`, `index.db`. Можно держать под git, шарить между агентами, использовать одновременно с OpenClaw.

---

## 5. Хранилище данных

### 5.1. Принцип: файлы как source of truth, SQLite как индекс

Рантайм-данные (conversations, summaries, memory snapshots, индексы) живут в `$XDG_DATA_HOME/klaw/` отдельно от workspace и конфигурации.

**Единая база `klaw.db`** — Engine единственный владелец. Содержит: индекс сообщений (для скользящего окна), сессии, FTS5, sqlite-vec. Восстанавливается из JSONL-файлов при повреждении (`klaw reindex`).

**Отдельная база `scheduler.db`** — Engine единственный владелец (Scheduler — модуль Engine). Содержит таблицы Quartz (QRTZ_*) для персистентности задач.

```
$XDG_DATA_HOME/klaw/                    # ~/.local/share/klaw/
├── conversations/                       # JSONL — иммутабельный лог (source of truth)
│   ├── telegram_123456/
│   │   ├── 2026-02-24.jsonl
│   │   └── 2026-02-25.jsonl
│   ├── discord_789012/
│   │   └── 2026-02-24.jsonl
│   └── scheduler_daily-summary/
│       └── 2026-02-24.jsonl
│
├── summaries/                           # Markdown — человекочитаемые саммари
│   ├── telegram_123456/
│   │   └── 2026-02-24_msg_001_050.md
│   └── discord_789012/
│       └── 2026-02-24_msg_001_030.md
│
├── memory/
│   └── core_memory.json               # Снапшот core memory (редактируемый)
│
├── skills/                              # Директории skills
│   ├── web-search/
│   │   ├── manifest.json
│   │   └── run.sh
│   └── code-execute/
│       ├── manifest.json
│       └── run.py
│
├── klaw.db                              # SQLite — Engine владеет (messages, sessions, vec, FTS)
└── scheduler.db                         # SQLite — Engine владеет (Quartz QRTZ_* таблицы)
```

### 5.2. Формат JSONL

Одна строка = одно сообщение. Append-only.

```json
{"id":"msg_001","ts":"2026-02-24T14:30:00Z","role":"user","content":"Привет, напомни что у меня сегодня","meta":{"channel":"telegram","chat_id":"123456"}}
{"id":"msg_002","ts":"2026-02-24T14:30:02Z","role":"assistant","content":"Сегодня у тебя встреча в 16:00...","meta":{"channel":"telegram","chat_id":"123456","model":"glm-5","tokens_in":4500,"tokens_out":120}}
{"id":"msg_003","ts":"2026-02-24T14:30:03Z","role":"tool","content":"{\"result\":\"OK\"}","meta":{"channel":"telegram","chat_id":"123456","tool":"calendar-check"}}
{"id":"msg_004","ts":"2026-02-24T15:00:00Z","role":"assistant","content":"Обнаружено 2 важных письма от клиента","meta":{"source":"heartbeat","channel":"telegram","chat_id":"123456"}}
```

### 5.3. klaw.db — единая SQLite база (Engine владеет)

```sql
-- Индекс сообщений (восстанавливается из JSONL)
-- Это зеркало JSONL для быстрого скользящего окна
-- ⚠️ ВАЖНО: НЕ добавлять WITHOUT ROWID — FTS5 content sync зависит от implicit rowid.
-- См. messages_fts ниже.
CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    channel TEXT NOT NULL,
    chat_id TEXT NOT NULL,
    role TEXT NOT NULL,          -- user, assistant, system, tool
    type TEXT,                   -- null, session_break, subagent_result, internal
    content TEXT NOT NULL,
    metadata JSON,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_messages_chat ON messages(chat_id, created_at DESC);

-- Сессии (восстанавливается из JSONL + routing.default)
CREATE TABLE sessions (
    chat_id TEXT PRIMARY KEY,
    model TEXT NOT NULL,
    segment_start TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- FTS5 для полнотекстового поиска
-- Примечание: messages использует TEXT PRIMARY KEY, но SQLite автоматически создаёт
-- implicit rowid. FTS5 content sync привязан к этому rowid.
-- При klaw reindex FTS5 пересоздаётся синхронно с таблицей messages.
CREATE VIRTUAL TABLE messages_fts USING fts5(content, content=messages, content_rowid=rowid);

-- Саммари (восстанавливается из файлов)
CREATE TABLE summaries (
    id INTEGER PRIMARY KEY,
    chat_id TEXT NOT NULL,
    from_message_id TEXT,
    to_message_id TEXT,
    file_path TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- sqlite-vec: векторный индекс для архивной памяти
CREATE VIRTUAL TABLE vec_memory USING vec0(
    embedding float[384]
);

CREATE TABLE memory_chunks (
    id INTEGER PRIMARY KEY,
    source TEXT NOT NULL,
    chat_id TEXT,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- sqlite-vec: документация агента
CREATE VIRTUAL TABLE vec_docs USING vec0(embedding float[384]);

CREATE TABLE doc_chunks (
    id INTEGER PRIMARY KEY,
    file TEXT NOT NULL,
    section TEXT,
    content TEXT NOT NULL,
    version TEXT NOT NULL
);
```

**Принцип**: `klaw.db` — это кэш/индекс. JSONL — source of truth. При повреждении `klaw.db` → `klaw reindex` пересобирает из JSONL-файлов.

### 5.4. Операции с данными

| Операция | Реализация |
|----------|------------|
| **Бэкап** | `rsync -a ~/.local/share/klaw/ backup/` или `git commit` |
| **Миграция** | Скопировать `$XDG_DATA_HOME/klaw/`, пересобрать `klaw.db` |
| **Дебаг** | `tail -f ~/.local/share/klaw/conversations/telegram_123456/2026-02-25.jsonl` |
| **Поиск** | `grep "ключевое слово" ~/.local/share/klaw/conversations/**/*.jsonl` |
| **Ручная правка памяти** | Редактировать `~/.local/share/klaw/memory/core_memory.json` |
| **Экспорт** | JSONL → любой формат тривиальным скриптом |
| **Восстановление индекса** | `klaw reindex` — пересобирает `klaw.db` из JSONL + файлов |

---

## 6. Система Skills

### 6.1. Что такое skill

Skill — папка с `SKILL.md` файлом, содержащим концентрированные знания и инструкции для агента. Формат совместим со стандартом [Agent Skills](https://agentskills.io/) (Anthropic, OpenAI Codex, GitHub Copilot, Cursor).

Skill — это **не исполняемый код**. Это переупакованная документация: best practices, workflows, decision trees, примеры кода. Агент читает skill когда задача совпадает с описанием, и следует инструкциям — так же, как опытный разработчик читал бы README перед работой с незнакомой библиотекой.

```
skills/
├── web-search/
│   ├── SKILL.md                    # Инструкции: как искать, какие API, формат ответа
│   └── examples/
│       └── search-patterns.md      # Примеры хороших поисковых запросов
├── code-review/
│   └── SKILL.md                    # Чеклист code review, паттерны, антипаттерны
├── docker-deploy/
│   ├── SKILL.md                    # Как деплоить на RPi5, Docker Compose шаблоны
│   ├── templates/
│   │   └── docker-compose.json     # Шаблон для новых сервисов
│   └── scripts/
│       └── health-check.sh         # Скрипт проверки здоровья (агент может запустить)
└── kotlin-kmp/
    └── SKILL.md                    # KMP best practices, Compose Multiplatform паттерны
```

### 6.2. Формат SKILL.md

```markdown
---
name: web-search
description: Используй когда нужно найти актуальную информацию в интернете. 
  Не используй для вопросов, ответы на которые уже есть в памяти.
---

# Web Search

## Когда использовать
- Пользователь спрашивает о текущих событиях, ценах, погоде
- Нужна верификация факта, который может быть устаревшим
- Запрос содержит "найди", "загугли", "что нового"

## Как искать
1. Сформулируй запрос на языке ожидаемого результата
2. Используй tool `code_execute` с curl к SearXNG: ...
3. Парси JSON-ответ, выбери top-3 результата
4. Если нужна полная страница — используй `code_execute` с readability-cli

## Формат ответа
- Всегда указывай источник (URL)
- Дата публикации если есть
- Кратко, по делу, без пересказа всей страницы
```

### 6.3. Progressive disclosure

Не все skills загружаются в контекст. Это сохраняет бюджет токенов.

**Два источника skills:**

| Директория | Назначение | Пример |
|------------|-----------|--------|
| `$KLAW_WORKSPACE/skills/` | Skills агента — специфичные для персоны, можно держать под git вместе с workspace | `web-search/`, `code-review/` |
| `$XDG_DATA_HOME/klaw/skills/` | Системные / установленные skills — общие для любого workspace, ставятся через `klaw skill install` (Post-MVP) | `code-execute/` |

**Приоритет при конфликте имён**: workspace skill побеждает системный (аналог PATH — пользовательское перекрывает системное). Engine логирует warning: `Skill 'web-search' found in both workspace and data, using workspace version`.

```
Старт engine:
  → Сканирование $XDG_DATA_HOME/klaw/skills/ (системные, низкий приоритет)
  → Сканирование $KLAW_WORKSPACE/skills/ (workspace, высокий приоритет — перекрывает по имени)
  → Для каждого skill: прочитать только YAML frontmatter (name + description)
  → Включить в system prompt как список доступных skills (~5 токенов на skill):
    "Доступные skills: web-search (поиск в интернете), code-review (чеклист ревью), ..."

Запрос пользователя:
  → LLM видит список skills в system prompt
  → LLM решает что нужен web-search
  → LLM вызывает tool: skill_load(name="web-search")
  → Engine читает полный SKILL.md (~500-2000 токенов) и возвращает как tool result
  → LLM следует инструкциям из skill
  → Если skill ссылается на файлы (templates/, scripts/) — LLM вызывает file_read
```

**Discovery** → **Activation** → **Execution**. Агент подгружает знания on-demand.

### 6.4. Встроенные tools (in-process)

Tools — встроенные инструменты engine, исполняемые in-process. LLM вызывает их через function calling, engine обрабатывает напрямую. Имеют прямой доступ к внутренним подсистемам.

#### Управление памятью

| Tool | Описание | Параметры |
|------|----------|-----------|
| `memory_search` | Семантический поиск по архивной памяти (sqlite-vec + FTS5 hybrid) | `query: string`, `topK?: int` |
| `memory_save` | Сохранить факт/заметку в архивную память | `content: string`, `source?: string` |
| `memory_core_get` | Прочитать текущее содержимое core memory | — |
| `memory_core_update` | Обновить секцию core memory | `section: "user"\|"agent"`, `key: string`, `value: string` |
| `memory_core_delete` | Удалить ключ из core memory | `section: "user"\|"agent"`, `key: string` |

#### Работа с файлами

| Tool | Описание | Параметры |
|------|----------|-----------|
| `file_read` | Чтение файла из workspace | `path: string`, `startLine?: int`, `maxLines?: int` |
| `file_write` | Запись/дозапись файла в workspace | `path: string`, `content: string`, `mode: "overwrite"\|"append"` |
| `file_list` | Листинг директории | `path: string`, `recursive?: bool` |

Пути ограничены workspace-директорией — агент не может читать произвольные файлы хоста. Размер записи ограничен `maxFileSizeBytes` (по умолчанию 1MB):

```yaml
# engine.json
files:
  maxFileSizeBytes: 1048576     # 1MB, защита от записи гигантских файлов
```

#### Управление расписанием

| Tool | Описание | Параметры |
|------|----------|-----------|
| `schedule_list` | Показать текущие задачи | — |
| `schedule_add` | Добавить cron-задачу (persistent, в Quartz) | `name: string`, `cron: string`, `message: string`, `model?: string`, `injectInto?: string` |
| `schedule_remove` | Удалить задачу из Quartz | `name: string` |

Задачи персистентные — переживают рестарт. Engine вызывает Scheduler (in-process) напрямую.

#### Субагенты

| Tool | Описание | Параметры |
|------|----------|-----------|
| `subagent_spawn` | Запустить одноразовый субагент прямо сейчас | `name: string`, `message: string`, `model?: string`, `injectInto?: string` |

`subagent_spawn` создаёт эфемерный субагент — отдельная корутина с изолированным контекстом. В отличие от `schedule_add`, выполняется немедленно и однократно. Полезно когда LLM хочет делегировать тяжёлую задачу (исследование, длинный анализ) не блокируя основной диалог.

```kotlin
// Пользователь: "Проанализируй эту статью подробно"
// → LLM вызывает: subagent_spawn(name="article-analysis", message="Прочитай и подробно проанализируй...", injectInto="telegram_123456")
// → Engine спавнит корутину с изолированным контекстом
// → Основная сессия сразу отвечает: "Начал анализ, результат пришлю когда будет готов"
// → Субагент завершается → результат приходит пользователю
```

#### Skills

| Tool | Описание | Параметры |
|------|----------|-----------|
| `skill_load` | Загрузить полный SKILL.md в контекст | `name: string` |
| `skill_list` | Показать все доступные skills с описаниями | — |

#### Самодокументация

| Tool | Описание | Параметры |
|------|----------|-----------|
| `docs_search` | Семантический поиск по документации Klaw | `query: string`, `topK?: int` |
| `docs_read` | Прочитать конкретный раздел документации | `path: string` |
| `docs_list` | Показать структуру документации | — |

Документация поставляется в дистрибутиве как Markdown-файлы и индексируется в `klaw.db` (таблица `vec_docs`) при первом запуске (или при обновлении версии).

#### Утилиты

| Tool | Описание | Параметры |
|------|----------|-----------|
| `current_time` | Текущая дата/время/timezone | — |
| `send_message` | Отправить сообщение в другой канал (cross-channel) | `channel: string`, `chatId: string`, `text: string` |

`send_message` проходит через Gateway, который проверяет пару `channel + chatId` по whitelist (`allowedChatIds` в `gateway.json`). chatId привязан к каналу — `telegram_123456` валиден только для Telegram. Попытка отправить в неразрешённый chatId или несоответствующий канал возвращает ошибку как tool result.

### 6.5. Исполнение кода (Docker sandbox)

Отдельная capability, не связанная со skills. Когда агенту нужно выполнить код (пользовательский запрос, curl-запрос к API, скрипт обработки данных), он использует tool `code_execute`:

| Tool | Описание | Параметры |
|------|----------|-----------|
| `code_execute` | Исполнение кода в изолированном Docker-контейнере | `language: "python"\|"bash"`, `code: string`, `timeout?: int` |

**Sandboxing**: каждый вызов `code_execute` запускает контейнер с жёсткими ограничениями:

**⚠️ Производительность на Pi 5**: `docker run --rm` на ARM с SD-картой — 2–5 секунд на старт контейнера. Для цепочки из нескольких tool call'ов (типичный сценарий: скрипт → ошибка → исправление → повтор) это 10–20 секунд overhead. **Митигация для MVP**: keep-alive контейнер — Engine при старте запускает один long-running контейнер (`docker run -d --name klaw-sandbox ...`), code_execute использует `docker exec` вместо `docker run`. Контейнер перезапускается каждые N вызовов или по таймауту бездействия (10 минут). Это сокращает overhead до ~100ms за вызов ценой постоянных ~40MB RAM.

```yaml
# engine.json
codeExecution:
  dockerImage: "klaw-sandbox:latest"    # prebuilt образ с Python, bash, curl
  timeout: 30                            # секунд, жёсткий лимит
  allowNetwork: true                     # false → --network none
  maxMemory: "256m"                      # --memory
  maxCpus: "1.0"                         # --cpus
  readOnlyRootfs: true                   # --read-only
  noPrivileged: true                     # запрет --privileged (hardcoded, не конфигурируемый)
  keepAlive: true                        # true: docker exec (быстро), false: docker run --rm (изоляция)
  keepAliveIdleTimeoutMin: 10            # убить keep-alive контейнер после N минут бездействия
  keepAliveMaxExecutions: 50             # пересоздать контейнер после N вызовов (защита от утечек)
  volumeMounts:                          # разрешённые mount'ы
    - "${KLAW_WORKSPACE}/skills:ro"      # read-only доступ к файлам skills (скрипты, шаблоны)
    - "/tmp/klaw-sandbox:rw"             # временная директория для output'а
```

Engine формирует `docker run` с этими ограничениями:
```bash
docker run --rm \
  --memory 256m --cpus 1.0 \
  --read-only \
  --network ${allowNetwork ? "bridge" : "none"} \
  --tmpfs /tmp:rw,size=64m \
  -v "${workspace}/skills:/skills:ro" \
  -v "/tmp/klaw-sandbox:/output:rw" \
  klaw-sandbox:latest \
  timeout 30 python3 -c "${code}"
```

Запрещены: `--privileged`, произвольные volume mounts, доступ к Docker socket изнутри контейнера, доступ к host filesystem кроме явно разрешённых путей.

### 6.6. Команды

Slash-команды регистрируются в Telegram через `setMyCommands` (меню бота). Gateway знает о них на уровне протокола (Telegram `bot_command` entity) — но не знает что они делают. Gateway нормализует команду в unified message format и отправляет в Engine. Engine обрабатывает без вызова LLM.

```kotlin
// Gateway: Telegram message с entity type=bot_command
// → нормализация в unified format, отправка в Engine socket
{"type":"command","channel":"telegram","chatId":"telegram_123456","command":"model","args":"deepseek/deepseek-chat"}

// Engine: видит type=command → CommandHandler → ответ без LLM
// Engine: видит type=inbound → контекст → LLM
```

Список команд задаётся в `engine.json` (Engine — владелец логики), Gateway читает только имена для регистрации в Telegram:

```yaml
# engine.json
commands:
  - name: new
    description: "Новая сессия (сброс контекста)"
  - name: model
    description: "Показать/сменить модель LLM"
  - name: models
    description: "Список доступных моделей"
  - name: memory
    description: "Показать core memory"
  - name: status
    description: "Статус агента"
  - name: help
    description: "Список команд"
```

| Команда | Описание |
|---------|----------|
| `/new` | Новая сессия — сброс контекстного окна |
| `/model` | Показать текущую модель сессии |
| `/model provider/model-id` | Переключить модель (например `/model deepseek/deepseek-chat`) |
| `/models` | Список доступных моделей |
| `/memory` | Показать core memory |
| `/status` | Статус engine, текущая сессия, модель, uptime |
| `/help` | Список команд |

#### Семантика `/new`

`/new` сбрасывает контекстное окно, но не стирает историю. Conversation log — append-only, сообщения никогда не удаляются.

Что сбрасывается, что нет:

| | Сбрасывается | Сохраняется |
|---|---|---|
| Sliding window (последние N сообщений) | ✓ | |
| Last summary | ✓ | |
| Core memory (факты о пользователе) | | ✓ |
| Archival memory (sqlite-vec) | | ✓ |
| Текущая модель | | ✓ |
| Conversation log (JSONL) | | ✓ (append-only) |

По сути `/new` — это не удаление, а разрыв. Старые сообщения остаются в логе и доступны через archival search, но не попадают в sliding window нового сегмента.

---

## 7. CLI

### 7.1. Назначение

CLI (`klaw`) — основной интерфейс администрирования. Gateway и Engine работают как демоны (systemd services), CLI — точка входа для управления, диагностики и обслуживания. CLI должен быть **мгновенным** (< 50ms для локальных команд) — JVM-стартап в 1–3 секунды неприемлем.

### 7.2. Архитектура: два режима работы

CLI команды делятся на **локальные** (работают с файлами напрямую, не требуют запущенного Engine) и **делегированные** (требуют Engine для тяжёлых операций вроде embedding, управления расписанием, или статуса рантайма).

| Команда | Режим | Что делает |
|---------|-------|------------|
| `klaw status` | Делегированный → Engine | Текущие сессии, модели, uptime |
| `klaw logs [--follow] [--chat ID]` | Локальный | `tail` JSONL файлов |
| `klaw schedule list` | Делегированный → Engine | Читает задачи из Quartz (in-process) |
| `klaw schedule add NAME CRON MSG` | Делегированный → Engine | Добавляет в Quartz (in-process) |
| `klaw schedule remove NAME` | Делегированный → Engine | Удаляет из Quartz (in-process) |
| `klaw memory show` | Локальный | Читает `core_memory.json` |
| `klaw memory edit` | Локальный | Открывает `core_memory.json` в `$EDITOR` |
| `klaw memory search QUERY` | Делегированный → Engine | Нужен ONNX для embedding + sqlite-vec |
| `klaw sessions` | Делегированный → Engine | Читает из `klaw.db` через Engine |
| `klaw reindex` | Локальный (тяжёлый) | Пересборка `klaw.db` из JSONL. **Требует остановки Engine** (`systemctl stop klaw-engine`) — иначе WAL-lock не позволит пересоздать базу |
| `klaw doctor` | Локальный | Проверка конфигов, файлов, зависимостей |
| `klaw import [--workspace PATH]` | Локальный | Импорт OpenClaw workspace |
| `klaw init [--from-openclaw PATH]` | Локальный | Создать workspace (BOOTSTRAP.md) |
| `klaw export [--format json\|md] CHAT` | Локальный | Экспорт разговора |

**Управление расписанием** (`klaw schedule *`): CLI отправляет команду в Engine через socket, Engine вызывает Scheduler (in-process) напрямую.

### 7.3. Реализация

**Бинарник**: Kotlin/Native. CLI модуль компилируется в нативный бинарник под `linuxArm64` (Pi 5) и `linuxX64` (dev) — старт ~5–20ms, нулевой JVM overhead. CLI зависит от `common` модуля (Native target) — модели данных, SQL-схемы, YAML-парсинг шарятся с JVM-модулями без дублирования.

**Делегированные команды**: CLI подключается к `$XDG_STATE_HOME/klaw/engine.sock`, отправляет JSON-запрос, получает JSON-ответ. Короткоживущие соединения (request-response):

```
→ {"command":"status"}
← {"uptime":"2h30m","sessions":[{"chat_id":"telegram_123456","model":"glm/glm-5"}]}

→ {"command":"memory_search","query":"встреча с клиентом"}
← {"results":[{"content":"...","source":"conversation","score":0.87}]}

→ {"command":"schedule_add","name":"test","cron":"0 0 12 * * ?","message":"Test","model":"glm/glm-4-plus"}
← {"status":"ok","name":"test"}
```

Если Engine не запущен — делегированные команды выдают понятную ошибку: `Engine не запущен. Запустите: systemctl start klaw-engine`.

**Локальные команды** работают **без запущенного Engine** — напрямую с файлами. Это критично для обслуживания: `klaw reindex`, `klaw doctor`, `klaw logs` должны работать даже если Engine упал.

---

## 8. Технический стек

### 8.1. Фреймворк: Micronaut

| Критерий | Micronaut | Spring Boot | Голый Kotlin |
|----------|-----------|-------------|--------------|
| DI | Compile-time, без рефлексии | Runtime, тяжёлый | Руками |
| Старт | <1 сек | 3–5 сек | Мгновенный |
| RAM | ~50–80MB | ~200–400MB | ~30MB |
| HTTP client | Декларативный, SSE, retry | RestTemplate/WebClient | Ktor Client |
| Знакомство | ✅ Привычен | Overkill | Нет DI |

**Решение**: Micronaut для Gateway и Engine. Compile-time DI, Micronaut HTTP Client (декларативный, SSE для стриминга LLM), lifecycle management. Монорепо с KMP-модулем `common`.

```
klaw/
├── build.gradle.kts              # root, общие зависимости
├── common/                       # KMP (JVM + Native): модели, утилиты, SQLite-схемы
│   └── build.gradle.kts          #   kotlinx-serialization, kotlinx-datetime, SQLiter
├── gateway/                      # JVM, Micronaut app
│   └── build.gradle.kts
├── engine/                       # JVM, Micronaut app (включает Scheduler)
│   └── build.gradle.kts
└── cli/                          # Kotlin/Native (linuxArm64, linuxX64)
    └── build.gradle.kts          #   зависит от common (Native target)
```

**`common` как KMP-модуль**: common собирается под два таргета — JVM (для gateway, engine) и Native (для cli). Это позволяет шарить модели данных, форматы JSONL/YAML, SQL-схемы между JVM-процессами и нативным CLI без дублирования. Все зависимости common — мультиплатформенные (см. секцию 8.3).

### 8.2. LLM-абстракция: Client / Provider / Router

LLM-фреймворки (LangChain4j, Spring AI) не нужны — OpenAI-compatible API это один POST-эндпоинт. Но нужна правильная абстракция для расширения на Anthropic и Gemini.

**Client ≠ Provider**. Client — это протокол (как разговаривать с API). Provider — это конфигурация (куда ходить, с каким ключом). Один client обслуживает много providers.

```
LlmClient (протокол)              Provider (конфигурация)
─────────────────                  ─────────────────────
OpenAiCompatibleClient ──────────→ GLM-5, DeepSeek, Qwen, Ollama, OpenRouter
AnthropicClient ─────────────────→ Claude Sonnet, Claude Opus
GeminiClient ────────────────────→ Gemini Flash, Gemini Pro
```

```kotlin
// === common модуль (KMP: commonMain) ===

// Единый внутренний формат — наш, не чей-то
data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolDef>? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
)

data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall>?,
    val usage: TokenUsage?,
    val finishReason: FinishReason,
)

data class ToolCall(val id: String, val name: String, val arguments: String)
data class ToolResult(val callId: String, val content: String)

// Протокол — как разговаривать с API
interface LlmClient {
    suspend fun chat(request: LlmRequest, provider: ProviderConfig, model: ModelRef): LlmResponse
    suspend fun chatStream(request: LlmRequest, provider: ProviderConfig, model: ModelRef): Flow<LlmChunk>
}

// Конфигурация провайдера — endpoint + credentials (без конкретной модели)
data class ProviderConfig(
    val name: String,              // "glm", "deepseek", "anthropic"
    val clientType: ClientType,    // OPENAI_COMPATIBLE, ANTHROPIC, GEMINI
    val endpoint: String,
    val apiKey: String?,
)

// Разрешённая модель — provider + model-id + per-model параметры
data class ModelRef(
    val provider: String,          // "glm"
    val modelId: String,           // "glm-5"
    val maxTokens: Int? = null,
    val contextBudget: Int? = null,
    val temperature: Double? = null,
) {
    val fullId: String get() = "$provider/$modelId"   // "glm/glm-5"
    companion object {
        fun parse(id: String): ModelRef {             // "glm/glm-5" → ModelRef("glm", "glm-5")
            val (provider, modelId) = id.split("/", limit = 2)
            return ModelRef(provider, modelId)
        }
    }
}

enum class ClientType { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }
```

**Роутер с fallback и session-aware model resolution**:

```kotlin
@Singleton
class LlmRouter(
    private val clients: Map<ClientType, LlmClient>,
    private val providers: Map<String, ProviderConfig>,
    private val models: Map<String, ModelRef>,
    private val routingConfig: RoutingConfig,
) {
    suspend fun chat(request: LlmRequest, model: String): LlmResponse {
        val chain = buildChain(model)
        for (ref in chain) {
            try {
                val provider = providers[ref.provider] ?: continue
                val client = clients[provider.clientType] ?: continue
                return client.chat(request, provider, ref)
            } catch (e: ProviderException) {
                log.warn("${ref.fullId} failed: ${e.message}, trying next")
                continue
            }
        }
        throw AllProvidersFailedException()
    }

    private fun buildChain(model: String): List<ModelRef> {
        val primary = models[model] ?: ModelRef.parse(model)
        val fallbacks = routingConfig.fallback.map { models[it] ?: ModelRef.parse(it) }
        return listOf(primary) + fallbacks.filter { it.fullId != model }
    }
}
```

### 8.3. Retry и backoff стратегия

Каждый `LlmClient` реализует retry internally перед тем как бросить `ProviderException` и передать управление fallback-цепочке Router'а:

| Параметр | Значение | Описание |
|----------|----------|----------|
| `maxRetries` | 2 | Retry на один provider перед fallback |
| `initialBackoffMs` | 1000 | Начальная задержка |
| `backoffMultiplier` | 2.0 | Экспоненциальный множитель (1s → 2s → 4s) |
| `requestTimeoutMs` | 60000 | Таймаут на один LLM-запрос (китайские API бывают медленными) |
| `retryOn` | 429, 500, 502, 503, 504 | HTTP-коды для retry |

Если все retry исчерпаны — `ProviderException`, Router переходит к следующему provider в fallback-цепочке. Если все providers упали — `AllProvidersFailedException`, Engine отправляет пользователю сообщение об ошибке.

```yaml
# engine.json
llm:
  maxRetries: 2
  requestTimeoutMs: 60000
  initialBackoffMs: 1000
  backoffMultiplier: 2.0
```

### 8.4. Зависимости

```kotlin
// build.gradle.kts (ориентировочно)

// === common (KMP: JVM + linuxArm64 + linuxX64) ===
kotlin {
    jvm()
    linuxArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
            implementation("org.jetbrains.kotlinx:kotlinx-io-core")
            implementation("app.cash.sqldelight:runtime")
            implementation("net.mamoe.yamlkt:yamlkt")
        }
        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver")
        }
        nativeMain.dependencies {
            implementation("app.cash.sqldelight:native-driver")
        }
    }
}

// === gateway (JVM) ===
dependencies {
    implementation(project(":common"))
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut:micronaut-inject")
    implementation("dev.inmo:tgbotapi")       // Telegram
    implementation("dev.kord:kord-core")       // Discord
}

// === engine (JVM) ===
dependencies {
    implementation(project(":common"))
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut:micronaut-inject")
    implementation("io.micronaut:micronaut-http-client")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
    implementation("ai.djl.huggingface:tokenizers:0.30.0")
    // Scheduler (Quartz, in-process)
    implementation("org.quartz-scheduler:quartz:2.5.0")
    implementation("org.xerial:sqlite-jdbc:3.45.0.0")  // для Quartz JDBC JobStore
    // sqlite-vec загружается как native расширение
}

// === cli (Kotlin/Native) ===
kotlin {
    linuxArm64 { binaries { executable() } }
    linuxX64 { binaries { executable() } }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":common"))
            implementation("com.github.ajalt.clikt:clikt")
        }
    }
}
```

### 8.5. Инфраструктура на Pi 5

```
Raspberry Pi 5 (16GB RAM)
├── systemd services:
│   ├── klaw-gateway.service         (Micronaut, ~80MB RAM)
│   ├── klaw-engine.service          (Micronaut + ONNX Runtime + Quartz, ~400MB RAM)
│   └── docker.service               (для code execution sandbox)
│
├── Опционально:
│   ├── ollama.service               (для локальных LLM + эмбеддингов)
│   └── searxng.service              (для web-search skill)
│
└── Итого: ~480MB (без Ollama)
    С Ollama + Qwen 3 8B Q4: ~5–6GB
    Запас: ~10GB свободных
```

---

## 9. Конфигурация

### 9.1. XDG-совместимые пути

Klaw следует [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir-spec/latest/). Три раздельных concern'а: конфигурация, данные, workspace.

```
$XDG_CONFIG_HOME/klaw/                   # ~/.config/klaw/
├── gateway.json                         # Каналы
└── engine.json                          # LLM, память, расписание, skills

$XDG_DATA_HOME/klaw/                     # ~/.local/share/klaw/
├── conversations/                       # JSONL логи
├── summaries/                           # Markdown саммари
├── memory/core_memory.json            # Core memory
├── skills/                              # Установленные skills
├── klaw.db                              # SQLite — Engine владеет
└── scheduler.db                         # SQLite — Engine владеет (Quartz)

$XDG_STATE_HOME/klaw/                    # ~/.local/state/klaw/
├── engine.sock                          # Unix domain socket (Engine)
├── gateway-buffer.jsonl                 # Буфер Gateway при недоступности Engine
└── logs/                                # Логи процессов

$KLAW_WORKSPACE                          # Отдельный путь, по умолчанию ~/klaw-workspace/
├── SOUL.md, IDENTITY.md, ...            # Workspace файлы агента
├── memory/                              # Дневные логи
└── skills/                              # Workspace skills
```

| Переменная | Fallback | Описание |
|------------|----------|----------|
| `$XDG_CONFIG_HOME/klaw` | `~/.config/klaw` | Конфигурация (gateway.json, engine.json) |
| `$XDG_DATA_HOME/klaw` | `~/.local/share/klaw` | Рантайм-данные (conversations, klaw.db, scheduler.db) |
| `$XDG_STATE_HOME/klaw` | `~/.local/state/klaw` | Сокеты, буферы, логи процессов |
| `$XDG_CACHE_HOME/klaw` | `~/.cache/klaw` | Кэш моделей ONNX, временные файлы |
| `$KLAW_WORKSPACE` | `~/klaw-workspace` | Workspace агента (персона, память, skills) |

### 9.2. Два файла: gateway и engine

Изолированно, без пересечений. Gateway не знает про LLM, engine не знает про Telegram-токены.

```yaml
# $XDG_CONFIG_HOME/klaw/gateway.json — всё что касается приёма/отправки сообщений

channels:
  telegram:
    token: "${TELEGRAM_BOT_TOKEN}"
    allowedChatIds: ["123456"]       # whitelist для входящих И исходящих сообщений
                                     #
                                     # Inbound:  пустой список = принимать от всех
                                     # Outbound: пустой список = ВСЕ outbound ОТКЛОНЯЮТСЯ
                                     #   (защита от галлюцинирующего агента по умолчанию).
                                     #   Для send_message и scheduler inject_into —
                                     #   chatId ОБЯЗАН быть в whitelist.
                                     #   Ответы на inbound: chatId входящего сообщения
                                     #   автоматически разрешён на время сессии (implicit allow),
                                     #   даже если не в whitelist. Это позволяет принимать
                                     #   от всех (пустой whitelist) и отвечать им.
  discord:
    enabled: false
    token: "${DISCORD_BOT_TOKEN}"
```

```yaml
# $XDG_CONFIG_HOME/klaw/engine.json — всё остальное: LLM, память, skills, расписание

# --- Провайдеры ---
providers:
  glm:
    type: openai-compatible
    endpoint: "https://open.bigmodel.cn/api/paas/v4"
    apiKey: "${GLM_API_KEY}"
  deepseek:
    type: openai-compatible
    endpoint: "https://api.deepseek.com/v1"
    apiKey: "${DEEPSEEK_API_KEY}"
  ollama:
    type: openai-compatible
    endpoint: "http://localhost:11434/v1"
  anthropic:
    type: anthropic
    endpoint: "https://api.anthropic.com/v1"
    apiKey: "${ANTHROPIC_API_KEY}"

# --- Модели ---
# contextBudget — рабочий бюджет контекста, значительно меньше максимального окна модели.
# Причины: (1) качество ответов деградирует на длинных контекстах у большинства моделей,
# (2) экономия токенов (каждый вызов = деньги), (3) предсказуемая латентность на Pi 5.
# Например, GLM-5 поддерживает 128k, но 12k — оптимальный баланс качества и стоимости.
models:
  glm/glm-4-plus:
    maxTokens: 4096
    contextBudget: 8000
  glm/glm-5:
    maxTokens: 8192
    contextBudget: 12000
  deepseek/deepseek-chat:
    contextBudget: 12000
  deepseek/deepseek-reasoner:
    maxTokens: 16384
    contextBudget: 16000
  ollama/qwen3:8b:
    contextBudget: 6000
  anthropic/claude-sonnet-4-20250514:         # Post-MVP (требует AnthropicClient, P1)
    maxTokens: 8192
    contextBudget: 16000

# --- Routing ---
routing:
  default: glm/glm-5
  fallback: [deepseek/deepseek-chat, ollama/qwen3:8b]
  tasks:
    summarization: ollama/qwen3:8b
    subagent: glm/glm-4-plus           # default для субагентов без явной модели

# --- Память ---
memory:
  embedding:
    type: onnx                    # onnx | ollama
    model: "all-MiniLM-L6-v2"
  chunking:
    size: 400
    overlap: 80
  search:
    topK: 10

# --- Контекст ---
context:
  defaultBudgetTokens: 8000       # если у модели не задан contextBudget
  slidingWindow: 20               # max cap, уменьшается если не влезает в бюджет
  subagentWindow: 5               # max cap для субагентов, подчиняется тому же бюджету

# --- Обработка ---
processing:
  debounceMs: 1500               # склеить сообщения от одного chat_id за это время
  maxConcurrentLlm: 2            # макс параллельных LLM-запросов (interactive + subagent)
  maxToolCallRounds: 10          # макс итераций tool call loop (защита от зацикливания)

# --- LLM retry ---
llm:
  maxRetries: 2
  requestTimeoutMs: 60000
  initialBackoffMs: 1000
  backoffMultiplier: 2.0

# --- Логирование ---
logging:
  subagentConversations: true    # писать JSONL для scheduler-каналов (для статистики/дебага)

# --- Исполнение кода ---
codeExecution:
  dockerImage: "klaw-sandbox:latest"
  timeout: 30
  allowNetwork: true
  maxMemory: "256m"
  maxCpus: "1.0"
  keepAlive: true
  keepAliveIdleTimeoutMin: 10
  keepAliveMaxExecutions: 50

# --- Файлы ---
files:
  maxFileSizeBytes: 1048576       # 1MB, защита от записи гигантских файлов
```

**Взаимодействие contextBudget и subagentWindow**: субагенты используют ту же формулу сборки контекста (секция 3.3), но с `subagentWindow` вместо `slidingWindow`. Бюджет берётся из `contextBudget` модели субагента. Если 5 сообщений субагента (с длинными tool results) не влезают в оставшийся бюджет — окно уменьшается до N сообщений, которые помещаются. Неиспользованный бюджет не «пропадает» — он просто не нужен, контекст собирается по факту.

Пути к данным (`klaw.db`, `scheduler.db`, `conversations/`) не указываются в конфигах — вычисляются из XDG-переменных автоматически.

---

## 10. Оценка объёма работ

### MVP (2–3 недели)

| Компонент | Строки (оценка) | Приоритет |
|-----------|-----------------|-----------|
| Общие модели, сериализация, утилиты | ~500 | P0 |
| LLM абстракция: Client/Provider/Router | ~350 | P0 |
| LLM клиент: OpenAiCompatibleClient | ~200 | P0 |
| Socket server (Engine) + protocol | ~300 | P0 |
| Socket client (Gateway → Engine) + buffer | ~200 | P0 |
| Engine: контекстная сборка + субагенты | ~500 | P0 |
| Engine: основной цикл + tool call loop + debounce | ~400 | P0 |
| Tools: memory (search, save, core CRUD) | ~300 | P0 |
| Tools: file (read, write, list) | ~150 | P0 |
| Tools: docs (search, read, list + индексация) | ~200 | P0 |
| Tools: skills (load, list + discovery) | ~150 | P0 |
| Tools: schedule, subagent_spawn, time, send_message | ~200 | P0 |
| Memory: sqlite-vec + FTS5 + гибридный поиск | ~400 | P0 |
| Memory: ONNX embedding service | ~200 | P0 |
| Memory: core memory (JSON, tool calls) | ~150 | P0 |
| Memory: чанкинг (Markdown-aware) | ~200 | P0 |
| OpenClaw workspace loader (SOUL/IDENTITY/USER/AGENTS.md + HEARTBEAT→Quartz + core_memory.json) | ~500 | P0 |
| Gateway: Telegram (Micronaut) | ~200 | P0 |
| Scheduler: Quartz + SQLite delegate + HEARTBEAT.md импорт (модуль Engine) | ~300 | P0 |
| Code execution (Docker sandbox + sandboxing) | ~300 | P0 |
| CLI: status, schedule, memory, reindex, logs (Kotlin/Native) | ~250 | P0 |
| **Итого MVP** | **~5900** | |

### Post-MVP

| Компонент | Приоритет |
|-----------|-----------|
| Gateway: Discord | P1 |
| Фоновая суммаризация | P1 |
| OpenClaw import (`klaw import`) | P1 |
| OpenClaw обратная запись (MEMORY.md, daily logs) | P1 |
| BOOT.md выполнение при старте | P1 |
| Skill: code-execute (SKILL.md с best practices для code execution) | P1 |
| LLM клиент: AnthropicClient (~150 строк) | P1 |
| LLM клиент: GeminiClient (~150 строк) | P2 |
| LLM streaming (SSE) через Micronaut HTTP Client | P2 |
| Skill: web-search (SearXNG) | P2 |
| BOOTSTRAP.md — интерактивный онбординг | P2 |
| CLI: import, export, doctor, init | P2 |
| Temporal decay для поиска (свежее = релевантнее) | P2 |
| Soul Spec v0.5 — поддержка soul.json | P3 |
| Метрики и мониторинг | P3 |

---

## 11. Тестирование

### Стратегия

| Уровень | Scope | Инструменты | Что покрывает |
|---------|-------|-------------|---------------|
| **Unit** | common, engine logic | kotlin.test, KMP-совместимые | Модели данных, сериализация JSONL/YAML, сборка контекста, чанкинг, гибридный поиск (mock SQLite), парсинг workspace-файлов |
| **Integration** | engine + SQLite + sqlite-vec | Testcontainers / in-memory SQLite | Полный цикл: сообщение → контекст → mock LLM → tool call → ответ. Запись/чтение JSONL. Индексация в sqlite-vec |
| **LLM contract tests** | LLM clients | WireMock / записанные ответы | OpenAI-compatible, Anthropic, Gemini: корректный маппинг запросов/ответов, tool calling формат, обработка ошибок, стриминг |
| **Socket protocol tests** | Gateway ↔ Engine | Loopback | JSONL protocol, reconnection, buffer drain, registration handshake |
| **E2E (smoke)** | Gateway → Engine → Gateway | Docker Compose | Telegram webhook mock → полный цикл → проверка ответа |

### Принципы

- **Mock LLM по умолчанию** — тесты не ходят в реальные API. Записанные (fixture) ответы для каждого провайдера.
- **common тестируется на обоих таргетах** — JVM и Native, чтобы ловить platform-specific баги (особенно SQLite/SqlDelight).
- **Regression-тесты на function calling** — для каждой поддерживаемой модели фиксируется формат tool_call и проверяется парсинг. Китайские LLM часто ломают формат между версиями.
- **Тесты восстановления** — `klaw reindex` корректно пересобирает `klaw.db` из JSONL; sessions восстанавливаются; misfire recovery Quartz.
- **Quartz + SQLite** — misfire recovery после простоя Engine (2+ часа, 4+ пропущенных задачи); кастомный `SQLiteDelegate` (`BEGIN IMMEDIATE` вместо row-level locking); single-node mode без `QRTZ_LOCKS`.

---

## 12. Риски и митигация

| Риск | Вероятность | Влияние | Митигация |
|------|-------------|---------|-----------|
| Rabbit hole: хочется добавить UI, multi-agent, свой векторный индекс | Высокая | Критическое | Жёсткий scope: оркестратор + интеграции, не платформа |
| Китайские LLM плохо держат function calling | Средняя | Высокое | Fallback на DeepSeek-V3 (лучший function calling среди китайских моделей). Тесты на каждую модель |
| ONNX Runtime на ARM64 тормозит | Низкая | Среднее | Fallback: Ollama `all-minilm` для эмбеддингов через HTTP |
| Docker overhead на ARM для каждого code_execute вызова | Средняя | Высокое | Keep-alive контейнер: `docker exec` вместо `docker run --rm` (2–5 сек → ~100ms). Пересоздание каждые 50 вызовов или 10 мин бездействия. Конфигурируемо: `keepAlive: false` для максимальной изоляции |
| JSONL файлы разрастаются | Низкая | Низкое | Ротация по месяцам, архивация старых. При 1000 сообщений/день ≈ 1MB/день |
| KMP common ограничивает выбор библиотек | Средняя | Среднее | Большинство kotlinx-* уже KMP. SqlDelight зрелый. JVM-only зависимости остаются в своих модулях |
| Приближённый подсчёт токенов (±15%) | Средняя | Среднее | Safety margin 10% в contextBudget. Tech debt: заменить на HuggingFace Tokenizer (Post-MVP). Мониторить API-ошибки `context_length_exceeded` — индикатор недооценки |
| Quartz + SQLite: нет официального диалекта | Средняя | Среднее | Кастомный `SQLiteDelegate` extends `StdJDBCDelegate`, заменяет `SELECT ... FOR UPDATE` на `BEGIN IMMEDIATE`. Single-node mode (`isClustered = false`), без `QRTZ_LOCKS`. Отдельные тесты на misfire recovery после простоя |

---

## 13. Принципы разработки

1. **Файлы > базы данных** для всего, что человек может захотеть прочитать или отредактировать
2. **SQLite только как кэш/индекс**, восстанавливаемый из файлов
3. **Процессы не делят состояние** — каждый владеет своими данными, общаются через Unix domain sockets
4. **Планировщик не вызывает LLM напрямую** — он передаёт параметры (включая модель) в Engine, который выполняет вызов
5. **Convention over Configuration** — минимум магии, максимум предсказуемости
6. **Контекст собирается на лету** — никакой мутации истории
7. **Append-only** — сообщения никогда не удаляются и не модифицируются
8. **Каждый процесс восстанавливаем** — при падении/повреждении данных восстановление из файлов или misfire recovery
