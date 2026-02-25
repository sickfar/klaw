# TASK-004 — IPC: Unix Socket + JSONL Protocol

**Phase**: 3
**Priority**: P0
**Dependencies**: TASK-002
**Est. LOC**: ~500
**Design refs**: [§2.3 IPC Protocol](../design/klaw-design-v0_4.md#23-межпроцессное-взаимодействие-persistent-jsonl-socket), [§2.4 Engine Socket Server](../design/klaw-design-v0_4.md#24-описание-компонентов), [§7.3 CLI Implementation](../design/klaw-design-v0_4.md#73-реализация)

---

## Summary

Реализовать Unix domain socket IPC между Engine, Gateway и CLI. Gateway держит persistent JSONL соединение с Engine. CLI использует короткоживущие request-response соединения. Engine буферизует сообщения при недоступности (gateway-buffer.jsonl).

---

## Goals

1. `SocketServer` (Engine) — принимает persistent и short-lived соединения
2. `GatewaySocketClient` — persistent соединение с Engine, push/pull
3. `CliSocketClient` — short-lived request-response
4. Gateway buffer: `gateway-buffer.jsonl` — append при недоступности Engine, drain при реконнекте
5. Registration handshake при подключении Gateway
6. `chmod 600` для `engine.sock`
7. Socket protocol tests (loopback)

---

## Implementation Details

### Socket Server (Engine)

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/socket/

@Singleton
class EngineSocketServer(
    private val paths: KlawPaths,
    private val messageHandler: SocketMessageHandler,  // Engine logic
) {
    // При старте: слушает engine.sock, chmod 600
    // Принимает два типа клиентов:
    // 1. Gateway — persistent, регистрируется через {"type":"register","client":"gateway"}
    //    Engine пушит ответы (type:"outbound") в это соединение
    // 2. CLI — короткоживущие, request-response, потом закрытие

    private var gatewayConnection: SocketConnection? = null

    suspend fun start()
    suspend fun stop()  // graceful: отправить {"type":"shutdown"} в Gateway
    suspend fun pushToGateway(message: OutboundSocketMessage)
}
```

**Протокол**:
```
Engine ← Gateway: {"type":"inbound","id":"msg_001","channel":"telegram","chatId":"telegram_123456",...}
Engine ← Gateway: {"type":"command","channel":"telegram","chatId":"telegram_123456","command":"new"}
Engine ← Gateway: {"type":"register","client":"gateway"}
Engine → Gateway: {"type":"outbound","replyTo":"msg_001","channel":"telegram","chatId":"telegram_123456","content":"..."}
Engine → Gateway: {"type":"shutdown"}  # при graceful stop

Engine ← CLI:  {"command":"status"}
Engine → CLI:  {"uptime":"2h30m","sessions":[...]}
```

### Gateway Socket Client (Gateway)

```kotlin
// gateway/src/main/kotlin/io/github/klaw/gateway/socket/

@Singleton
class EngineSocketClient(
    private val paths: KlawPaths,
    private val buffer: GatewayBuffer,
    private val outboundHandler: OutboundMessageHandler,  // Gateway logic
) {
    // Persistent соединение с Engine
    // При подключении: отправить registration handshake
    // При отключении Engine: reconnect с exponential backoff
    // При получении outbound от Engine: вызвать outboundHandler

    suspend fun connect()
    suspend fun send(message: SocketMessage): Boolean  // false если недоступен → buffer
    suspend fun drainBuffer()  // при реконнекте: отправить из buffer.jsonl
}
```

### Gateway Buffer

```kotlin
// gateway/src/main/kotlin/io/github/klaw/gateway/socket/

@Singleton
class GatewayBuffer(private val paths: KlawPaths) {
    // Append-only JSONL файл: $XDG_STATE_HOME/klaw/gateway-buffer.jsonl
    // При старте: проверить есть ли накопленные сообщения (drain при реконнекте)

    fun append(message: SocketMessage)
    fun drain(): List<SocketMessage>  // читает всё, очищает файл
    fun isEmpty(): Boolean
}
```

**Порядок буферизации** (из дизайна §2.3):
1. Получить сообщение из Telegram
2. Записать в conversations/ JSONL (Gateway пишет всегда)
3. Попытаться отправить в Engine socket
   - Успех → OK
   - Недоступен → записать в `gateway-buffer.jsonl`
4. При реконнекте: drain буфера последовательно (порядок гарантирован для одного chat_id)

### CLI Socket Client (CLI — Kotlin/Native)

```kotlin
// cli/src/nativeMain/kotlin/io/github/klaw/cli/socket/

class EngineSocketClient {
    // Короткоживущее соединение
    // request-response: отправить JSON, получить JSON ответ, закрыть

    fun request(command: String, params: Map<String, String> = emptyMap()): String
    // throws EngineNotRunningException если socket недоступен
}
```

Ошибка при недоступном Engine:
```
Engine не запущен. Запустите: systemctl start klaw-engine
```

---

## TDD Approach

Тесты **до** реализации.

### Test Suite

**1. Protocol serialization tests** (unit):
```kotlin
class SocketMessageSerializationTest {
    @Test fun `InboundSocketMessage round-trip`()
    @Test fun `OutboundSocketMessage with replyTo`()
    @Test fun `CommandSocketMessage`()
    @Test fun `RegisterMessage`()
    @Test fun `CliRequestMessage`()
    @Test fun `JSONL line parsing (newline-delimited)`()
    @Test fun `partial line doesn't crash parser`()
}
```

**2. Socket protocol tests** (integration, loopback):
```kotlin
class SocketProtocolTest {
    // Поднимает реальный UnixSocket server в тесте

    @Test fun `gateway registers and receives outbound messages`()
    @Test fun `cli request-response cycle`()
    @Test fun `gateway reconnects after server restart`()
    @Test fun `multiple messages in flight`()
    @Test fun `shutdown message sent to gateway on stop`()
    @Test fun `socket file permissions are 600`()
}
```

**3. Buffer tests**:
```kotlin
class GatewayBufferTest {
    @Test fun `buffer appends when engine unavailable`()
    @Test fun `drain returns messages in order and clears file`()
    @Test fun `drain on empty buffer returns empty list`()
    @Test fun `drains on reconnect`()
    @Test fun `partial write (crash mid-write) is handled gracefully`()
}
```

**4. Registration handshake tests**:
```kotlin
class RegistrationHandshakeTest {
    @Test fun `gateway registers with type=register client=gateway`()
    @Test fun `cli connection doesn't send register`()
    @Test fun `engine tracks single gateway connection`()
}
```

---

## Acceptance Criteria

- [ ] `EngineSocketServer` слушает `$XDG_STATE_HOME/klaw/engine.sock`
- [ ] `chmod 600` применён к socket файлу
- [ ] Gateway persistent соединение: Engine пушит ответы в то же соединение
- [ ] CLI request-response работает с коротким соединением
- [ ] `gateway-buffer.jsonl` заполняется при недоступном Engine
- [ ] Drain буфера при реконнекте работает в правильном порядке
- [ ] Все loopback тесты зелёные
- [ ] Engine отправляет `{"type":"shutdown"}` при graceful stop

---

## Constraints

- Unix domain socket: `java.nio.channels.ServerSocketChannel` с `UnixDomainSocketAddress` (Java 16+) на JVM; POSIX sockets на Native (Kotlin/Native interop)
- CLI реализация в `cli` модуле (Native) — использует `common` модели
- Gateway и Engine реализации — в их JVM модулях
- Не использовать `netty` или другие тяжёлые фреймворки для IPC — только стандартные Java NIO
- Socket файл удаляется при shutdown Engine и при старте (если остался от предыдущего запуска)

---

## Documentation Subtask

**File to create**: `doc/storage/where-data-lives.md` (sockets and buffer sections only — other sections added in TASK-007 and TASK-009)

Document the runtime state files that the agent may need to reason about (e.g. understanding if the gateway buffer has backed-up messages, or diagnosing connectivity issues).

**Sections to write** (English only):

- **engine.sock** — Unix domain socket at `~/.local/state/klaw/engine.sock`; exists only while the Engine process is running; the CLI connects here for delegated commands; protected by `chmod 600` (owner-only access); if this file is absent, the Engine is not running
- **gateway-buffer.jsonl** — at `~/.local/state/klaw/gateway-buffer.jsonl`; contains messages buffered by the Gateway when the Engine was temporarily unreachable; automatically drained (sent to Engine) when the Engine reconnects; if the buffer is large after a restart, there may be a backlog of messages to process
- **How to check engine status** — run `klaw status` (requires Engine running) or check if `engine.sock` exists: `ls ~/.local/state/klaw/engine.sock`
- **Logs directory** — `~/.local/state/klaw/logs/` contains process logs for Gateway and Engine; useful for diagnosing startup failures

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt gateway:ktlintCheck gateway:detekt
./gradlew engine:test gateway:test
```
