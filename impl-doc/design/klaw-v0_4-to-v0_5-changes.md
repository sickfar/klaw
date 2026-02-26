# Klaw — Изменения v0.4 → v0.5

---

## 1. Новый tool: `host_exec` — выполнение команд на хосте

### Мотивация

`code_execute` работает в Docker sandbox и не видит хост — ни процессы, ни systemd, ни температуру CPU, ни диски. Для мониторинга Pi 5 (типичный heartbeat-кейс: "проверь состояние сервисов") это бесполезно.

### Tool

| Tool | Описание | Параметры |
|------|----------|-----------|
| `host_exec` | Выполнение команды на хосте с многоуровневым approval-контролем | `command: string` |

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
| LLM risk < порога | Выполняется — команда безобидная, но не в whitelist | `sed -n '5p' /etc/hosts`, `cat /var/log/syslog` |
| LLM risk ≥ порога | Спрашивает пользователя | `apt upgrade -y`, `echo "..." > /etc/cron.d/job` |

### LLM pre-validation

Быстрая и дешёвая модель (Haiku-класс) оценивает риск команды без какого-либо контекста разговора. Промпт минимальный:

```
Оцени риск выполнения shell-команды от 0 до 10.
0 — только чтение, никаких побочных эффектов (cat, grep, ls, head, sed -n, awk, wc).
10 — необратимое разрушительное действие (rm -rf, mkfs, dd if=/dev/zero).

Модифицирующие команды (запись в файлы, перезапуск сервисов, установка пакетов) — 
всегда 6 и выше, даже если кажутся безобидными.

Команда: {command}
Ответь только числом.
```

Ключевой принцип промпта: **модифицирующие команды всегда получают высокий балл**, даже если выглядят безобидно (`echo "test" > file.txt` — запись, значит ≥ 6). Это гарантирует что read-only операции проходят свободно, а всё остальное попадает на подтверждение.

### Конфигурация

```yaml
# engine.yaml
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
    model: anthropic/claude-haiku    # быстрая модель для оценки риска
    riskThreshold: 5                 # 0–5 выполняем, 6–10 спрашиваем
    timeoutMs: 5000                  # таймаут на оценку, при превышении — ask
  askTimeoutMin: 5                   # таймаут ожидания ответа пользователя
```

Паттерны в `allowList` и `notifyList` — glob.

При `preValidation.model` не задан или LLM недоступен — fallback на `ask` (безопасный дефолт).

### Механика `ask` и `notify`

#### Протокол Engine → Gateway

Engine отправляет специальный тип сообщения `approval_request` в Gateway socket:

```json
{"type":"approval_request","id":"apr_001","chatId":"telegram_123456","command":"apt upgrade -y","riskScore":8,"timeout":300}
```

Gateway отображает запрос пользователю в канале и ждёт ответа. При получении ответа — отправляет обратно в Engine:

```json
{"type":"approval_response","id":"apr_001","approved":true}
```

Для `notify` Engine отправляет обычный `outbound` с меткой:

```json
{"type":"outbound","chatId":"telegram_123456","content":"ℹ️ Выполняю: `systemctl restart klaw-gateway`","meta":{"source":"host_exec_notify"}}
```

#### Реализация в Telegram

Запрос `approval_request` отображается как сообщение с inline keyboard:

```
🔐 Агент хочет выполнить:
`apt upgrade -y`

Риск: 8/10 · Таймаут: 5 мин

[✅ Разрешить]  [❌ Отклонить]
```

Gateway использует `InlineKeyboardMarkup` с callback data:

```kotlin
// Gateway: обработка approval_request
fun sendApprovalRequest(chatId: String, approval: ApprovalRequest) {
    val keyboard = InlineKeyboardMarkup(listOf(
        listOf(
            InlineKeyboardButton("✅ Разрешить", callbackData = "approve:${approval.id}"),
            InlineKeyboardButton("❌ Отклонить", callbackData = "deny:${approval.id}"),
        )
    ))
    bot.sendMessage(
        chatId = chatId,
        text = "🔐 Агент хочет выполнить:\n`${approval.command}`\n\nРиск: ${approval.riskScore}/10 · Таймаут: ${approval.timeout / 60} мин",
        parseMode = MarkdownV2,
        replyMarkup = keyboard
    )
}

// Gateway: callback query от нажатия кнопки
fun handleCallbackQuery(query: CallbackQuery) {
    val (action, approvalId) = query.data.split(":")
    val approved = action == "approve"
    
    // Обновить сообщение — убрать кнопки, показать результат
    bot.editMessageText(
        messageId = query.message.messageId,
        text = if (approved) "✅ Разрешено: `${command}`" else "❌ Отклонено: `${command}`",
        parseMode = MarkdownV2
    )
    
    // Отправить ответ в Engine socket
    engineSocket.send(ApprovalResponse(id = approvalId, approved = approved))
}
```

#### Жизненный цикл approval в Engine

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

При таймауте (`askTimeoutMin`, по умолчанию 5 минут) Gateway обновляет сообщение, убирая кнопки: `⏰ Таймаут: \`apt upgrade -y\` — отклонено`.

#### Discord

Аналогично — `ActionRow` с `Button.primary("approve:id")` / `Button.danger("deny:id")`. Gateway абстрагирует это через интерфейс `Channel`, добавляя метод:

```kotlin
interface Channel {
    val name: String
    suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit)
    suspend fun send(chatId: String, response: OutgoingMessage)
    suspend fun sendApproval(chatId: String, approval: ApprovalRequest, onResponse: (Boolean) -> Unit)
}
```

### Добавление в таблицу tools (секция 6.4 → Утилиты)

| Tool | Описание | Параметры |
|------|----------|-----------|
| `host_exec` | Выполнение команды на хосте (approval-controlled) | `command: string` |

### Добавление в таблицу взаимодействий Engine (секция 2.4)

| Система | Протокол | Назначение |
|---------|----------|------------|
| Host OS | Direct exec | Мониторинг, управление сервисами (`host_exec`) |

### Добавление в оценку объёма (секция 10)

| Компонент | Строки (оценка) | Приоритет |
|-----------|-----------------|-----------|
| Tools: host_exec (glob matching, LLM pre-validation, approval flow) | ~250 | P0 |

---

## 2. Docker sandbox: workspace монтируется как `rw`

### Мотивация

Ранее в Docker sandbox монтировалась только `${KLAW_WORKSPACE}/skills:ro`. Если агенту нужно обработать файл из workspace (например "обработай data.csv из моего workspace"), доступа не было. Workspace монтируется целиком как `rw` — агент может читать и писать файлы workspace из sandbox.

### Изменение конфига (секция 6.5)

Было:
```yaml
codeExecution:
  volumeMounts:
    - "${KLAW_WORKSPACE}/skills:ro"
    - "/tmp/klaw-sandbox:rw"
```

Стало:
```yaml
codeExecution:
  volumeMounts:
    - "${KLAW_WORKSPACE}:/workspace:rw"
    - "/tmp/klaw-sandbox:rw"
```

### Изменение docker run команды

Было:
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

Стало:
```bash
docker run --rm \
  --memory 256m --cpus 1.0 \
  --read-only \
  --network ${allowNetwork ? "bridge" : "none"} \
  --tmpfs /tmp:rw,size=64m \
  -v "${workspace}:/workspace:rw" \
  -v "/tmp/klaw-sandbox:/output:rw" \
  klaw-sandbox:latest \
  timeout 30 python3 -c "${code}"
```

Прочие ограничения sandbox не меняются: `--read-only` rootfs, запрет `--privileged`, запрет доступа к Docker socket изнутри, лимиты memory/CPU/timeout.
