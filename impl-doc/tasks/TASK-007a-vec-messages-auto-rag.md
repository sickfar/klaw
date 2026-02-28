# TASK-007a — vec_messages Auto-RAG Infrastructure

**Phase**: 6a (immediately after TASK-007)
**Priority**: P0
**Dependencies**: TASK-007 (memory system — done)
**Blocks**: TASK-007b
**Est. LOC**: ~550
**Design refs**: [klaw-v0_5-auto-rag-context.md §1](../design/klaw-v0_5-auto-rag-context.md#1-auto-rag-по-истории-сообщений), [klaw-v0_5-auto-rag-context.md §2](../design/klaw-v0_5-auto-rag-context.md#2-векторный-индекс-по-сообщениям-vec_messages)

---

## Summary

Add `vec_messages` virtual table (sqlite-vec) for semantic search over message history. Implement fire-and-forget message embedding after `MessageRepository.save()`. Create `AutoRagService` with hybrid RRF search scoped to the current segment. Update `ReindexService` to populate `vec_messages`. Add `AutoRagConfig` to `EngineConfig` and rename `context.subagentWindow` → `context.subagentHistory`.

This task delivers the Auto-RAG **infrastructure only**. Integration into `ContextBuilder` and subagent history loading are in **TASK-007b** which is blocked on this task.

---

## Goals

1. Common module config: `AutoRagConfig` data class; rename `subagentWindow` → `subagentHistory`; add `autoRag` field to `EngineConfig`
2. `vec_messages` virtual table in `VirtualTableSetup.kt` (inside existing sqlite-vec guard)
3. `MessageEmbeddingService`: fire-and-forget embedding for eligible messages
4. `MessageRepository.saveAndGetRowId()`: new method returning SQLite rowid; add `rowId` to `MessageRow`; update `getWindowMessages` SQL
5. `AutoRagService`: hybrid search (vec_messages + messages_fts) scoped to segment, with RRF, deduplication, relevance threshold, token budget truncation
6. `ReindexService`: populate `vec_messages` during reindex for eligible messages

---

## Implementation Details

### Stage 1: Config Schema Changes (common module)

**File:** `common/src/commonMain/kotlin/io/github/klaw/common/config/EngineConfig.kt`

Add `AutoRagConfig` data class:

```kotlin
@Serializable
data class AutoRagConfig(
    val enabled: Boolean = true,
    val topK: Int = 3,
    val maxTokens: Int = 400,
    val relevanceThreshold: Double = 0.5,
    val minMessageTokens: Int = 10,
) {
    init {
        require(topK > 0) { "topK must be > 0, got $topK" }
        require(maxTokens > 0) { "maxTokens must be > 0, got $maxTokens" }
        require(relevanceThreshold > 0.0) { "relevanceThreshold must be > 0, got $relevanceThreshold" }
        require(minMessageTokens > 0) { "minMessageTokens must be > 0, got $minMessageTokens" }
    }
}
```

Rename `subagentWindow` → `subagentHistory` in `ContextConfig` (semantics change: no longer a message count, now means "N completed runs" for subagents):

```kotlin
@Serializable
data class ContextConfig(
    val defaultBudgetTokens: Int,
    val slidingWindow: Int,
    val subagentHistory: Int,   // renamed from subagentWindow
) {
    init {
        require(defaultBudgetTokens > 0) { "defaultBudgetTokens must be > 0" }
        require(slidingWindow > 0) { "slidingWindow must be > 0" }
        require(subagentHistory > 0) { "subagentHistory must be > 0" }
    }
}
```

Add `autoRag` field to `EngineConfig`:

```kotlin
@Serializable
data class EngineConfig(
    // ... existing fields ...
    val autoRag: AutoRagConfig = AutoRagConfig(),   // defaults applied when section absent from YAML
)
```

**Cascading changes required after rename:**
- All engine source files using `config.context.subagentWindow` → `config.context.subagentHistory`
- Engine test `engine.json` (`engine/src/test/resources/engine.json`): rename field
- `ConfigParsingTest` YAML fixture: rename field
- Any other references found via `grep -r subagentWindow`

### Stage 2: `vec_messages` Virtual Table

**File:** `engine/src/main/kotlin/io/github/klaw/engine/db/VirtualTableSetup.kt`

Inside the existing `if (sqliteVecAvailable)` block, after `vec_docs` creation:

```kotlin
driver.execute(
    null,
    "CREATE VIRTUAL TABLE IF NOT EXISTS vec_messages USING vec0(embedding float[384])",
    0,
)
```

**Key contract:** `vec_messages.rowid` ↔ `messages.rowid` (implicit SQLite integer rowid, same as `vec_memory.rowid ↔ memory_chunks.id`).

### Stage 3: `MessageEmbeddingService`

**New file:** `engine/src/main/kotlin/io/github/klaw/engine/message/MessageEmbeddingService.kt`

```kotlin
@Singleton
class MessageEmbeddingService(
    private val driver: JdbcSqliteDriver,
    private val embeddingService: EmbeddingService,
    private val sqliteVecLoader: SqliteVecLoader,
) {
    /**
     * Fire-and-forget: launches in [scope] and returns immediately.
     * Never blocks — caller delivers response to user before embed completes.
     */
    fun embedAsync(
        messageRowId: Long,
        role: String,
        type: String,
        content: String,
        config: AutoRagConfig,
        scope: CoroutineScope,
    ) {
        if (!sqliteVecLoader.isAvailable()) return
        if (!isEligible(role, type, content, config)) return
        scope.launch {
            try {
                val embedding = embeddingService.embed(content)
                val blob = floatArrayToBlob(embedding)
                withContext(Dispatchers.VT) {
                    driver.execute(null, "INSERT OR IGNORE INTO vec_messages(rowid, embedding) VALUES (?, ?)", 2) {
                        bindLong(0, messageRowId)
                        bindBytes(1, blob)
                    }
                }
                logger.trace { "Message embedding stored rowId=$messageRowId" }
            } catch (e: Exception) {
                logger.warn { "Failed to embed message rowId=$messageRowId role=$role: ${e::class.simpleName}" }
            }
        }
    }

    // internal for unit testing
    internal fun isEligible(role: String, type: String, content: String, config: AutoRagConfig): Boolean {
        if (role != "user" && role != "assistant") return false
        if (role == "assistant" && type == "tool_call") return false
        if (approximateTokenCount(content) < config.minMessageTokens) return false
        return true
    }

    private fun floatArrayToBlob(arr: FloatArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(arr.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in arr) buf.putFloat(f)
        return buf.array()
    }
}
```

**Logging rules:** log only `rowId`, `role`, `e::class.simpleName` — never `content`.

### Stage 4: `MessageRepository` Changes

**File:** `engine/src/main/sqldelight/io/github/klaw/engine/db/Messages.sq`

Add query:
```sql
lastInsertRowId:
SELECT last_insert_rowid();
```

Update `getWindowMessages` to return `rowid`:
```sql
getWindowMessages:
SELECT rowid, id, channel, chat_id, role, type, content, metadata, created_at
FROM messages
WHERE chat_id = :chatId AND created_at >= :segmentStart
ORDER BY created_at ASC, rowid ASC
LIMIT :limit;
```

**File:** `engine/src/main/kotlin/io/github/klaw/engine/message/MessageRepository.kt`

Update `MessageRow`:
```kotlin
data class MessageRow(
    val rowId: Long,       // NEW — SQLite implicit rowid for vec_messages FK
    val id: String,
    val channel: String,
    val chatId: String,
    val role: String,
    val type: String,
    val content: String,
    val metadata: String?,
    val createdAt: String,
)
```

Add `saveAndGetRowId()`:
```kotlin
suspend fun saveAndGetRowId(
    id: String,
    channel: String,
    chatId: String,
    role: String,
    type: String,
    content: String,
    metadata: String? = null,
): Long = withContext(Dispatchers.VT) {
    db.transactionWithResult {
        val now = Clock.System.now().toString()
        db.messagesQueries.insertMessage(id, channel, chatId, role, type, content, metadata, now)
        db.messagesQueries.lastInsertRowId().executeAsOne()
    }
}
```

Existing `save()` remains unchanged. Update `getWindowMessages()` mapping to include `rowId`.

### Stage 5: `AutoRagService`

**New file:** `engine/src/main/kotlin/io/github/klaw/engine/memory/AutoRagService.kt`

```kotlin
data class AutoRagResult(
    val messageId: String,
    val content: String,
    val role: String,
    val createdAt: String,
)

@Singleton
class AutoRagService(
    private val driver: JdbcSqliteDriver,
    private val embeddingService: EmbeddingService,
    private val sqliteVecLoader: SqliteVecLoader,
) {
    /**
     * Hybrid search over message history of the current segment.
     * Scoped to [chatId] messages with created_at >= [segmentStart].
     * Excludes [slidingWindowRowIds] (already in context).
     * Returns empty list on any exception (fail-safe).
     */
    suspend fun search(
        query: String,
        chatId: String,
        segmentStart: String,
        slidingWindowRowIds: Set<Long>,
        config: AutoRagConfig,
    ): List<AutoRagResult> {
        if (!config.enabled) return emptyList()
        logger.debug { "Auto-RAG search: queryLength=${query.length} chatId=$chatId" }
        return try {
            val vecResults = if (sqliteVecLoader.isAvailable()) vectorSearch(query, chatId, segmentStart, topK = 20) else emptyList()
            val ftsResults = ftsSearch(query, chatId, segmentStart, topK = 20)
            logger.trace { "Auto-RAG: vec=${vecResults.size} fts=${ftsResults.size} before merge" }

            val merged = rrfMerge(vecResults, ftsResults, k = 60)
            val filtered = merged.filter { it.rowId !in slidingWindowRowIds }

            // Relevance threshold: if vec results exist and best distance too high, skip all
            if (vecResults.isNotEmpty() && filtered.isNotEmpty()) {
                val bestVecCandidate = vecResults.firstOrNull { it.rowId == filtered.first().rowId }
                if (bestVecCandidate != null && bestVecCandidate.distance > config.relevanceThreshold) {
                    logger.debug { "Auto-RAG: relevance threshold exceeded, skipping" }
                    return emptyList()
                }
            }

            val result = truncateToTokenBudget(filtered.take(config.topK), config.maxTokens)
            logger.debug { "Auto-RAG: returning ${result.size} results for chatId=$chatId" }
            result.map { AutoRagResult(it.messageId, it.content, it.role, it.createdAt) }
        } catch (e: Exception) {
            logger.warn { "Auto-RAG search failed: ${e::class.simpleName}" }
            emptyList()
        }
    }

    // internal for testing
    internal suspend fun vectorSearch(query: String, chatId: String, segmentStart: String, topK: Int): List<RawCandidate>
    internal suspend fun ftsSearch(query: String, chatId: String, segmentStart: String, topK: Int): List<RawCandidate>

    internal data class RawCandidate(
        val rowId: Long,
        val messageId: String,
        val content: String,
        val role: String,
        val createdAt: String,
        val score: Double,
        val distance: Double = 1.0,
    )
}
```

**Vector search SQL:**
```sql
SELECT v.rowid, v.distance, m.id, m.content, m.role, m.created_at
FROM vec_messages v
JOIN messages m ON m.rowid = v.rowid
WHERE v.embedding MATCH ?
  AND m.chat_id = ?
  AND m.created_at >= ?
ORDER BY v.distance
LIMIT ?
```

**FTS search SQL:**
```sql
SELECT m.rowid, m.id, m.content, m.role, m.created_at, rank
FROM messages_fts fts
JOIN messages m ON m.rowid = fts.rowid
WHERE messages_fts MATCH ?
  AND m.chat_id = ?
  AND m.created_at >= ?
ORDER BY rank
LIMIT ?
```

**RRF merge:** identical algorithm to `MemoryServiceImpl` — key is `rowId: Long`, score `1.0 / (k + rank + 1)`.

**Follow existing patterns from `MemoryServiceImpl`:** `Dispatchers.VT`, raw SQL via `JdbcSqliteDriver.executeQuery()`, `floatArrayToBlob()`.

### Stage 6: `ReindexService` Update

**File:** `engine/src/main/kotlin/io/github/klaw/engine/maintenance/ReindexService.kt`

Add to constructor: `embeddingService: EmbeddingService`, `sqliteVecLoader: SqliteVecLoader`, `config: EngineConfig`

Update `reindex()` to:
1. Clear `vec_messages` (`DELETE FROM vec_messages` — skip if sqlite-vec unavailable)
2. Rebuild `messages` (existing logic)
3. Rebuild `vec_messages`: query eligible messages, embed each, `INSERT OR IGNORE`

Eligibility in reindex: `role IN ('user', 'assistant')` AND `NOT (role = 'assistant' AND type = 'tool_call')` AND `approximateTokenCount(content) >= config.autoRag.minMessageTokens`

Log individual embed failures as WARN with `e::class.simpleName`; continue loop.

---

## TDD Approach

Tests **before** implementation. All tests in `engine/src/test/kotlin/`.

### Stage 1: Config Tests (common/jvmTest)

**File:** `common/src/jvmTest/kotlin/io/github/klaw/common/config/AutoRagConfigTest.kt`

```kotlin
class AutoRagConfigTest {
    @Test fun `default AutoRagConfig has valid values`()
    @Test fun `topK zero throws IllegalArgumentException`()
    @Test fun `maxTokens zero throws IllegalArgumentException`()
    @Test fun `relevanceThreshold zero throws IllegalArgumentException`()
    @Test fun `minMessageTokens zero throws IllegalArgumentException`()
    @Test fun `parse engine yaml with full autoRag section`()
    @Test fun `parse engine yaml without autoRag section uses defaults`()
    @Test fun `ContextConfig subagentHistory field parsed correctly`()
    @Test fun `subagentHistory zero throws IllegalArgumentException`()
}
```

Update existing `ConfigParsingTest` YAML fixtures: `subagentWindow` → `subagentHistory`.

### Stage 2: VirtualTableSetup (no new test needed)

The existing `SchemaTest` (or equivalent) verifies table creation. Add an assertion that `vec_messages` is created alongside `vec_memory` and `vec_docs` when `sqliteVecAvailable = true`.

### Stage 3: MessageEmbeddingService Tests

**New file:** `engine/src/test/kotlin/io/github/klaw/engine/message/MessageEmbeddingServiceTest.kt`

```kotlin
class MessageEmbeddingServiceTest {
    // isEligible
    @Test fun `isEligible true for user role with sufficient tokens`()
    @Test fun `isEligible true for assistant text type with sufficient tokens`()
    @Test fun `isEligible false for assistant tool_call type`()
    @Test fun `isEligible false for tool role`()
    @Test fun `isEligible false for session_break role`()
    @Test fun `isEligible false for system role`()
    @Test fun `isEligible false when content below minMessageTokens`()

    // embedAsync behavior
    @Test fun `embedAsync no-op when sqlite-vec not available`()
    @Test fun `embedAsync no-op when message not eligible`()
    @Test fun `embedAsync inserts into vec_messages for eligible user message`()
    @Test fun `embedAsync inserts into vec_messages for eligible assistant text`()
    @Test fun `embedAsync INSERT OR IGNORE handles duplicate rowId without error`()
    @Test fun `embedAsync exception logged as warn, not rethrown`()
}
```

Use `runBlocking` for tests using `Dispatchers.VT`. Use `MockEmbeddingService`. For vec_messages insert tests: create table via `driver.execute(null, "CREATE TABLE vec_messages(rowid INTEGER PRIMARY KEY, embedding BLOB)", 0)` as a test-only stub (native sqlite-vec unavailable in CI).

### Stage 4: MessageRepository Tests

**New file:** `engine/src/test/kotlin/io/github/klaw/engine/message/MessageRepositoryTest.kt`

```kotlin
class MessageRepositoryTest {
    @Test fun `save returns Unit — backward compatible`()
    @Test fun `saveAndGetRowId returns monotonically increasing rowIds`()
    @Test fun `saveAndGetRowId rowId matches actual SQLite rowid`()
    @Test fun `getWindowMessages includes rowId in MessageRow`()
    @Test fun `getWindowMessages filters by chatId and segmentStart`()
}
```

### Stage 5: AutoRagService Tests

**New file:** `engine/src/test/kotlin/io/github/klaw/engine/memory/AutoRagServiceTest.kt`

```kotlin
class AutoRagServiceTest {
    @Test fun `search returns empty when enabled=false`()
    @Test fun `search returns empty when vec unavailable and FTS no match`()
    @Test fun `ftsSearch scoped to chatId — excludes other chatIds`()
    @Test fun `ftsSearch scoped to segmentStart — excludes earlier messages`()
    @Test fun `search excludes rowIds in slidingWindowRowIds`()
    @Test fun `search respects topK limit`()
    @Test fun `search truncates to maxTokens budget`()
    @Test fun `search returns empty when no matches found`()
    @Test fun `search returns empty when best result exceeds relevanceThreshold`()
    @Test fun `rrfMerge document in both sets scores higher than unique`()
    @Test fun `rrfMerge deduplicates by rowId`()
    @Test fun `search returns empty on exception (fail-safe)`()
}
```

FTS tests work without native sqlite-vec (uses `messages_fts`). For relevance threshold: use `MockEmbeddingService` returning orthogonal vectors.

### Stage 6: ReindexService Tests

**File:** update `engine/src/test/kotlin/io/github/klaw/engine/maintenance/ReindexServiceTest.kt`

```kotlin
// Add to existing ReindexServiceTest:
@Test fun `reindex clears vec_messages before rebuild when vec available`()
@Test fun `reindex skips vec_messages entirely when sqlite-vec unavailable`()
@Test fun `reindex embeds eligible user messages into vec_messages`()
@Test fun `reindex skips assistant tool_call type messages`()
@Test fun `reindex skips tool role messages`()
@Test fun `reindex skips messages below minMessageTokens`()
@Test fun `reindex progress callback reports vec_messages steps`()
@Test fun `reindex continues after individual embed failure`()
```

---

## Acceptance Criteria

- [ ] `AutoRagConfig` parses from YAML with all fields; defaults apply when section absent
- [ ] `ContextConfig.subagentHistory` replaces `subagentWindow` — all references updated, all existing tests pass
- [ ] `vec_messages` created in `VirtualTableSetup` when `sqliteVecAvailable = true`
- [ ] `MessageEmbeddingService.isEligible()`: user/assistant-text only; minMessageTokens enforced; tool_call type excluded
- [ ] `MessageEmbeddingService.embedAsync()`: fire-and-forget, exceptions logged as warn (not thrown)
- [ ] `MessageRepository.saveAndGetRowId()` returns correct rowid within transaction
- [ ] `MessageRow.rowId` populated by `getWindowMessages()`
- [ ] `AutoRagService.search()`: scoped to segment; excludes sliding window rowIds; respects relevanceThreshold; truncates to maxTokens; empty on exception
- [ ] `ReindexService.reindex()`: populates `vec_messages` for eligible messages; skips when vec unavailable; continues on individual failure
- [ ] All pre-existing tests pass (no regression from rename or MessageRow change)

---

## Constraints

- **Common module**: NO SLF4J, no logging — pure KMP `@Serializable` data classes only
- **Engine module**: `Dispatchers.VT` for all JDBC/blocking I/O; `runBlocking` (NOT `runTest`) in tests using VT
- **Fire-and-forget**: `embedAsync()` must never await result — caller must not block on it
- **`vec_messages` rowid** = SQLite implicit integer rowid of the `messages` table row (NOT `messages.id` TEXT PK)
- **`INSERT OR IGNORE`** for `vec_messages` — handles reindex and retry races
- **Logging**: never log message content; log `rowId`, `contentLength`, result counts, `e::class.simpleName` only
- **`approximateTokenCount()`**: use function from common module for budget calculations
- **RRF deduplication key**: `rowId: Long` (not string content — unlike MemoryServiceImpl which uses content)
- **kaml `strictMode = false`** already global — new `autoRag` YAML section works without changes to parser config
- **`AutoRagService`** is `@Singleton` — DI by Micronaut; `EmbeddingService`, `SqliteVecLoader`, `JdbcSqliteDriver` are already beans

---

## Expected New Dead Code False Positives (lang-tools)

After this task, lang-tools will report these as unused — they are used by TASK-007b (not yet implemented):
- `AutoRagService.search` — used by `ContextBuilder`
- `MessageEmbeddingService.embedAsync` — used by `MessageProcessor`
- `MessageRepository.saveAndGetRowId` — used by `MessageProcessor`
- `MessageRow.rowId` — used by `ContextBuilder` (sliding window deduplication)

These are expected false positives. Document them in the quality check output.

---

## Quality Check

```bash
./gradlew :common:jvmTest :common:compileKotlinLinuxX64
./gradlew :engine:ktlintCheck :engine:detekt :engine:test
```

Run lang-tools dead code scan on whole project (via MCP). Fix any unexpected findings before marking task done. Then run code-reviewer subagent.
