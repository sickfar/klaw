# TASK-007 — Memory System (sqlite-vec + ONNX)

**Phase**: 6
**Priority**: P0
**Dependencies**: TASK-002
**Est. LOC**: ~950
**Design refs**: [§3.5 Memory](../design/klaw-design-v0_4.md#35-память-sqlite-vec--локальная-embedding-модель), [§5.3 klaw.db Schema](../design/klaw-design-v0_4.md#53-klawdb--единая-sqlite-база-engine-владеет), [§3.4 Background Summarization](../design/klaw-design-v0_4.md#34-фоновая-суммаризация), [§7.2 CLI reindex](../design/klaw-design-v0_4.md#72-архитектура-два-режима-работы)

---

## Summary

Реализовать систему памяти: SQLite схема `klaw.db` с sqlite-vec расширением, ONNX Runtime для embeddings (`all-MiniLM-L6-v2`), FTS5 полнотекстовый поиск, гибридный поиск RRF, core memory, Markdown-aware чанкинг, `klaw reindex`.

---

## Goals

1. SQLite схема `klaw.db` (все таблицы)
2. sqlite-vec native extension загрузка
3. ONNX embedding service (`all-MiniLM-L6-v2`, 384d)
4. Fallback: Ollama embedding через HTTP
5. Markdown-aware чанкинг (~400 токенов, 80-токенное перекрытие)
6. Vector search (sqlite-vec KNN top-K)
7. FTS5 полнотекстовый поиск
8. Гибридный поиск: RRF (k=60, topK=10)
9. Core memory (read/write `core_memory.json`)
10. `klaw reindex`: пересборка `klaw.db` из JSONL

---

## Implementation Details

### SQLite Schema (klaw.db)

```sql
-- Индекс сообщений (зеркало JSONL, восстанавливается командой klaw reindex)
-- ⚠️ НЕ добавлять WITHOUT ROWID — FTS5 content sync зависит от implicit rowid
CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    channel TEXT NOT NULL,
    chat_id TEXT NOT NULL,
    role TEXT NOT NULL,          -- user, assistant, system, tool
    type TEXT,                   -- null, session_break, subagent_result
    content TEXT NOT NULL,
    metadata JSON,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_messages_chat ON messages(chat_id, created_at DESC);

-- FTS5 для полнотекстового поиска по сообщениям
CREATE VIRTUAL TABLE messages_fts USING fts5(
    content,
    content=messages,
    content_rowid=rowid
);

-- Сессии
CREATE TABLE sessions (
    chat_id TEXT PRIMARY KEY,
    model TEXT NOT NULL,
    segment_start TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- Саммари
CREATE TABLE summaries (
    id INTEGER PRIMARY KEY,
    chat_id TEXT NOT NULL,
    from_message_id TEXT,
    to_message_id TEXT,
    file_path TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- Архивная память: чанки
CREATE TABLE memory_chunks (
    id INTEGER PRIMARY KEY,
    source TEXT NOT NULL,
    chat_id TEXT,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- sqlite-vec: векторный индекс архивной памяти
CREATE VIRTUAL TABLE vec_memory USING vec0(embedding float[384]);

-- Документация агента: чанки
CREATE TABLE doc_chunks (
    id INTEGER PRIMARY KEY,
    file TEXT NOT NULL,
    section TEXT,
    content TEXT NOT NULL,
    version TEXT NOT NULL
);

-- sqlite-vec: векторный индекс документации
CREATE VIRTUAL TABLE vec_docs USING vec0(embedding float[384]);
```

### sqlite-vec Extension Loading

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/db/

object SqliteVecLoader {
    // Загружает sqlite-vec нативное расширение
    // Путь зависит от платформы (linux ARM64 для Pi 5, linux x64 для dev)
    fun loadExtension(connection: Connection) {
        connection.createStatement().execute("SELECT load_extension('vec0')")
    }
}
```

sqlite-vec — нативная библиотека на C, распространяется как `.so` под linux-aarch64 и linux-x64. Включается в ресурсы gradle (`src/main/resources/native/`).

### ONNX Embedding Service

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/memory/

@Singleton
class OnnxEmbeddingService(
    private val config: MemoryConfig,
    cachePath: Path,  // $XDG_CACHE_HOME/klaw/models/
) : EmbeddingService {
    // all-MiniLM-L6-v2: ~80MB, 384-dim, ~5-15ms/inference на Pi 5
    private val ortSession: OrtSession
    private val tokenizer: HuggingFaceTokenizer

    override suspend fun embed(text: String): FloatArray {
        // tokenize → ONNX inference → mean pooling → L2 normalize → 384d vector
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray>

    // Fallback: если ONNX не загрузился → OllamaEmbeddingService
}

@Singleton
class OllamaEmbeddingService(
    private val httpClient: HttpClient,
    private val config: MemoryConfig,
) : EmbeddingService {
    // POST http://localhost:11434/api/embed
    // model: all-minilm:l6-v2
    override suspend fun embed(text: String): FloatArray
}

interface EmbeddingService {
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}
```

**Зависимости (engine/build.gradle.kts)**:
```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
implementation("ai.djl.huggingface:tokenizers:0.30.0")
```

### Markdown-Aware Chunker

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/memory/

class MarkdownChunker(
    private val chunkSize: Int = 400,       // tokens
    private val overlap: Int = 80,          // tokens
    private val tokenCounter: TokenCounter,  // from common
) {
    // Не ломает: заголовки (#, ##, ###), списки (-, *, 1.), блоки кода (```)
    // Ломает только в конце параграфов / предложений
    fun chunk(text: String, source: String): List<MemoryChunk>
}

data class MemoryChunk(
    val content: String,
    val source: String,
    val sectionHeader: String? = null,
)
```

### Memory Service

```kotlin
@Singleton
class MemoryService(
    private val db: KlawDatabase,
    private val embedding: EmbeddingService,
    private val chunker: MarkdownChunker,
) {
    // Сохранить текст в архивную память (чанкует + индексирует)
    suspend fun save(content: String, source: String, chatId: String? = null)

    // Индексировать файл целиком (для MEMORY.md, daily logs)
    suspend fun indexFile(filePath: Path, source: String)

    // Семантический поиск через sqlite-vec
    suspend fun vectorSearch(query: String, topK: Int = 20): List<MemorySearchResult>

    // Полнотекстовый поиск через FTS5
    suspend fun ftsSearch(query: String, topK: Int = 20): List<MemorySearchResult>

    // Гибридный поиск: RRF слияние
    suspend fun hybridSearch(query: String, topK: Int = 10): List<MemorySearchResult>
}

data class MemorySearchResult(
    val content: String,
    val source: String,
    val createdAt: Instant,
    val score: Double,
)
```

### Hybrid Search (RRF)

```kotlin
// Reciprocal Rank Fusion k=60 (из дизайна §3.5)
fun reciprocalRankFusion(
    vectorResults: List<MemorySearchResult>,
    ftsResults: List<MemorySearchResult>,
    k: Int = 60,
    topK: Int = 10,
): List<MemorySearchResult> {
    // score(d) = Σ 1/(k + rank_i(d))
    // rank_vector + rank_fts → combined score → sort desc → take topK
}
```

### Core Memory Service

```kotlin
@Singleton
class CoreMemoryService(private val paths: KlawPaths) {
    // core_memory.json — всегда загружается в контекст LLM
    fun get(): CoreMemory
    fun update(section: String, key: String, value: String)
    fun delete(section: String, key: String)

    // Инициализация из USER.md (workspace loader)
    fun initFromUserMd(content: String)
}

@Serializable
data class CoreMemory(
    val user: Map<String, String> = emptyMap(),
    val agent: Map<String, String> = emptyMap(),
)
```

### Message Repository

```kotlin
@Singleton
class MessageRepository(private val db: KlawDatabase) {
    // Записать сообщение в messages таблицу (синхронно с JSONL)
    suspend fun insert(message: MessageRecord)

    // Скользящее окно: последние N сообщений из текущего сегмента
    suspend fun getWindow(chatId: String, segmentStart: String, limit: Int): List<MessageRecord>

    // Для klaw reindex: bulk insert из JSONL
    suspend fun bulkInsert(messages: List<MessageRecord>)

    // Пересборка FTS5 индекса
    suspend fun rebuildFts()
}
```

### klaw reindex

```kotlin
// engine/src/main/kotlin/io/github/klaw/engine/maintenance/

class ReindexService(
    private val paths: KlawPaths,
    private val db: KlawDatabase,
    private val memory: MemoryService,
) {
    // Пересобирает klaw.db из JSONL файлов
    // ТРЕБУЕТ остановки Engine (WAL-lock)
    // Поддерживает прогресс-репорт для CLI
    suspend fun reindex(onProgress: (String) -> Unit = {})

    private suspend fun rebuildMessages()   // читает все conversations/*.jsonl
    private suspend fun rebuildSessions()   // вычисляет из последних session_break
    private suspend fun rebuildVectors()    // переиндексирует memory_chunks в sqlite-vec
    private suspend fun rebuildFts()        // пересобирает messages_fts
}
```

---

## TDD Approach

Тесты **до** реализации. Используем in-memory SQLite (или temp-file SQLite).

### Test Suite

**1. SQLite schema tests**:
```kotlin
class SchemaTest {
    @Test fun `klaw_db schema creates all required tables`()
    @Test fun `messages table has correct indexes`()
    @Test fun `vec_memory created with float[384]`()
    @Test fun `messages_fts content table sync works`()
    @Test fun `foreign key constraints enforced`()
}
```

**2. ONNX embedding tests**:
```kotlin
class OnnxEmbeddingTest {
    @Test fun `embed returns 384-dimensional vector`()
    @Test fun `embed L2-normalized (length close to 1.0)`()
    @Test fun `similar texts have higher cosine similarity`()
    @Test fun `different texts have lower cosine similarity`()
    @Test fun `embedBatch returns same results as sequential embed`()
}
```

**3. Chunker tests**:
```kotlin
class MarkdownChunkerTest {
    @Test fun `basic text chunked to ~400 tokens`()
    @Test fun `overlap between consecutive chunks`()
    @Test fun `markdown header not split mid-line`()
    @Test fun `code block not split mid-block`()
    @Test fun `list items not split mid-item`()
    @Test fun `short text produces single chunk`()
    @Test fun `empty text produces no chunks`()
    @Test fun `chunk size respects max tokens`()
}
```

**4. Vector search tests**:
```kotlin
class VectorSearchTest {
    @Test fun `vector search returns topK results`()
    @Test fun `results ordered by distance ascending`()
    @Test fun `search on empty index returns empty list`()
    @Test fun `cosine similarity scores are reasonable`()
}
```

**5. FTS5 search tests**:
```kotlin
class FtsSearchTest {
    @Test fun `fts5 finds exact phrase match`()
    @Test fun `fts5 finds partial word match`()
    @Test fun `fts5 results ordered by rank`()
    @Test fun `fts5 handles Russian/Chinese text`()
    @Test fun `fts5 returns empty on no match`()
}
```

**6. Hybrid search tests (RRF)**:
```kotlin
class HybridSearchTest {
    @Test fun `rrf combines vector and fts results`()
    @Test fun `document in both results gets higher score`()
    @Test fun `topK respected`()
    @Test fun `k=60 parameter used correctly`()
    @Test fun `empty vector results falls back to fts only`()
    @Test fun `empty fts results falls back to vector only`()
}
```

**7. Core memory tests**:
```kotlin
class CoreMemoryTest {
    @Test fun `get returns empty memory for new installation`()
    @Test fun `update user section`()
    @Test fun `update agent section`()
    @Test fun `delete existing key`()
    @Test fun `delete non-existent key returns error`()
    @Test fun `persistence: survives read after write`()
    @Test fun `initFromUserMd populates user section`()
}
```

**8. klaw reindex tests**:
```kotlin
class ReindexTest {
    @Test fun `reindex from single JSONL file restores messages`()
    @Test fun `reindex from multiple chat dirs restores all messages`()
    @Test fun `reindex restores session_break markers`()
    @Test fun `reindex after partial write (crash) handles incomplete last line`()
    @Test fun `reindex rebuilds FTS5 index`()
    @Test fun `reindex preserves message order`()
}
```

---

## Acceptance Criteria

- [ ] `klaw.db` создаётся с полной схемой при первом старте Engine
- [ ] sqlite-vec загружается на linuxArm64 (Pi 5) и linuxX64 (dev)
- [ ] ONNX embedding работает: `embed("test") returns FloatArray(384)`
- [ ] Гибридный поиск возвращает топ-10 релевантных результатов
- [ ] Markdown чанкер не ломает заголовки, блоки кода, списки
- [ ] `klaw reindex` корректно пересобирает `klaw.db` из JSONL
- [ ] Core memory `core_memory.json` корректно читается/пишется

---

## Constraints

- Engine — **единственный владелец** `klaw.db`. Никакой другой процесс не открывает этот файл напрямую
- `klaw reindex` требует остановки Engine (`systemctl stop klaw-engine`) — WAL lock
- ONNX Runtime: JVM-only (`engine` модуль), не в `common`
- sqlite-vec extension — нативная `.so` библиотека. Должна быть доступна на ARM64 (Pi 5)
- Fallback на Ollama если ONNX не загружается (конфигурируемо через `memory.embedding.type`)
- **НЕ** внешние сервисы для памяти (Letta, PostgreSQL, pgvector) — всё embedded

---

## Documentation Subtask

**Files to create / complete**:

1. `doc/tools/memory.md` — complete with implementation details (replaces TASK-006 stub)
2. `doc/memory/core-memory.md`
3. `doc/memory/archival-memory.md`
4. `doc/storage/database-schema.md`
5. `doc/storage/jsonl-format.md`

All documentation in **English only**.

---

### `doc/tools/memory.md` — complete version

Extend the stub from TASK-006 with implementation details the agent needs:

- **Hybrid search internals** — combines sqlite-vec KNN (semantic) and FTS5 (keyword) results; merged via Reciprocal Rank Fusion (RRF, k=60); default topK=10; returned results include content, source, and creation date
- **Chunking** — text saved via `memory_save` is split into ~400-token chunks with 80-token overlap; Markdown headings, code blocks, and lists are not broken mid-element; short content under 400 tokens produces one chunk
- **Embedding model** — `all-MiniLM-L6-v2` via ONNX Runtime (384-dimensional vectors); fallback to Ollama `all-minilm:l6-v2` via HTTP if ONNX is unavailable
- **Search latency** — on Pi 5: ~5–15 ms for embedding + ~60 ms for KNN over 100k chunks; acceptable for interactive use

---

### `doc/memory/core-memory.md`

- **What it is** — `~/.local/share/klaw/memory/core_memory.json`; structured JSON loaded into every LLM call; two sections: `user` (name, location, occupation, preferences, current projects) and `agent` (personality_notes, learned_rules)
- **How to read it** — already in system prompt each call; call `memory_core_get` only when the raw JSON is explicitly needed
- **How to update** — `memory_core_update(section, key, value)` immediately when the user states a preference, corrects a fact, or teaches a rule; do not defer to end of conversation
- **How to delete a key** — `memory_core_delete(section, key)`; returns error if key does not exist
- **Initialization** — populated from `USER.md` at engine startup; subsequent changes are only via tool calls; hand-editing is possible while the engine is stopped
- **Source of truth** — `core_memory.json` takes precedence over `USER.md` on conflict; `USER.md` is not re-read after initial import

---

### `doc/memory/archival-memory.md`

- **What it is** — chunks of text (~400 tokens each) stored in `klaw.db`; sources: `MEMORY.md`, `memory/YYYY-MM-DD.md`, summaries, manually saved facts via `memory_save`, conversation log entries
- **Searching** — `memory_search(query, topK)` runs hybrid search; results have `content`, `source`, and `created_at` fields
- **Saving** — `memory_save(content, source)`: content is chunked, embedded, and indexed automatically; use for facts that should persist beyond the sliding window
- **Sources in search results** — `"MEMORY.md"`, `"memory/2026-02-24.md"`, `"conversation"`, `"manual"` (saved via tool call)
- **Stale facts** — archival memory is append-only; old versions of facts are not deleted automatically; for frequently changing facts use `memory_core_update` instead; when a search result appears outdated, note it to the user and update core memory
- **Workspace indexing** — `MEMORY.md` and all `memory/*.md` files in the workspace are chunked and indexed at engine startup; adding new content to `MEMORY.md` requires an engine restart to be searchable

---

### `doc/storage/database-schema.md`

- **klaw.db** — owned exclusively by the Engine; do not access directly from outside the Engine process; tables:
  - `messages` — index of all conversation messages; used for sliding window queries; mirrored from JSONL (recoverable via `klaw reindex`)
  - `sessions` — current model and segment start per `chat_id`
  - `summaries` — metadata for generated summary Markdown files
  - `memory_chunks` — archival memory text chunks
  - `vec_memory` — sqlite-vec virtual table; 384-dimension float embeddings for archival memory
  - `doc_chunks` — documentation text chunks
  - `vec_docs` — sqlite-vec virtual table; 384-dimension float embeddings for documentation
  - `messages_fts` — FTS5 virtual table for full-text search over message content
- **scheduler.db** — owned exclusively by the Engine; contains Quartz JDBC tables (QRTZ_*); managed entirely by Quartz; do not interpret directly — use `schedule_list` tool instead
- **Restoring klaw.db** — run `klaw reindex` after stopping the engine (`systemctl stop klaw-engine`); rebuilds the entire database from JSONL source files; safe to run on a stopped engine; may take several minutes for large conversation histories
- **Why no direct database access** — Engine holds an exclusive WAL lock on both databases while running; external access risks corruption; all data access goes through tools

---

### `doc/storage/jsonl-format.md`

- **One line per message** — each line is a complete JSON object; lines are never modified; new messages are always appended
- **Fields** — `id` (unique string, e.g. `"msg_001"`), `ts` (ISO 8601 UTC), `role` (`"user"`, `"assistant"`, `"system"`, `"tool"`), `content` (text or JSON string), `type` (null, `"session_break"`, `"subagent_result"`), `meta` (object)
- **meta fields** — `channel`, `chat_id`, `model` (for assistant messages), `tokens_in`, `tokens_out`, `source` (`"scheduler"` for heartbeat tasks), `task_name`, `tool` (for tool messages)
- **session_break marker** — written by Engine when the user sends `/new`; the sliding window stops at this marker; all messages after it form the current segment
- **Conversation file paths** — `~/.local/share/klaw/conversations/{channel}_{chat_id}/YYYY-MM-DD.jsonl`; one file per day per chat; example: `conversations/telegram_123456/2026-02-24.jsonl`
- **Scheduler logs** — scheduler tasks optionally log to `conversations/scheduler_{task-name}/YYYY-MM-DD.jsonl`; `meta.source` is `"scheduler"`, `meta.task_name` identifies the task
- **Reading logs** — CLI: `klaw logs --chat telegram_123456`; or use `file_read` with the absolute path if known

---

## Quality Check

```bash
./gradlew engine:ktlintCheck engine:detekt
./gradlew engine:test
```
