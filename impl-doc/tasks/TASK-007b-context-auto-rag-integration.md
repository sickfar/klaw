# TASK-007b — Context Builder: Auto-RAG Integration & Subagent History

**Phase**: 6b (immediately after TASK-007a)
**Priority**: P0
**Dependencies**: TASK-007a (must be complete — provides `AutoRagService`, `MessageEmbeddingService`, `MessageRepository.saveAndGetRowId`, `AutoRagConfig`)
**Blocks**: TASK-008
**Est. LOC**: ~350
**Design refs**: [klaw-v0_5-auto-rag-context.md §1](../design/klaw-v0_5-auto-rag-context.md#1-auto-rag-по-истории-сообщений), [klaw-v0_5-auto-rag-context.md §3](../design/klaw-v0_5-auto-rag-context.md#3-субагенты-нескользящее-окно-без-auto-rag)

---

## Summary

Patch the already-completed `ContextBuilder` (TASK-005) to integrate Auto-RAG: automatic retrieval of relevant messages from the current segment before each LLM call. Add `SubagentHistoryLoader` to load last N completed runs from scheduler JSONL files (replacing the current sliding window approach for subagents). Wire `MessageEmbeddingService` into `MessageProcessor` for fire-and-forget embedding after message saves.

All components modified here were originally completed in TASK-005. This task contains only the v0.5 additions.

---

## Goals

1. `ContextBuilder`: auto-RAG insertion between summary and sliding window (with guard + budget adjustment)
2. `ContextBuilder`: subagent path uses `SubagentHistoryLoader` instead of `messageRepository.getWindowMessages()`
3. `SubagentHistoryLoader`: loads last N completed runs from `conversations/scheduler_{taskName}/*.jsonl`
4. `MessageProcessor`: wire `MessageEmbeddingService.embedAsync()` after user and assistant message saves; thread `taskName` through to `buildContext()`
5. `Messages.sq`: add `countInSegment` query for the auto-RAG guard

---

## Implementation Details

### Component 1: `Messages.sq` — `countInSegment` Query

**File:** `engine/src/main/sqldelight/io/github/klaw/engine/db/Messages.sq`

Add:
```sql
countInSegment:
SELECT COUNT(*) FROM messages WHERE chat_id = :chatId AND created_at >= :segmentStart;
```

Regenerate SqlDelight code: `./gradlew :engine:generateKlawDatabaseInterface`

### Component 2: `ContextBuilder` Changes

**File:** `engine/src/main/kotlin/io/github/klaw/engine/context/ContextBuilder.kt`

**Constructor additions:**
```kotlin
@Singleton
class ContextBuilder(
    // ... existing dependencies ...
    private val autoRagService: AutoRagService,          // NEW from TASK-007a
    private val subagentHistoryLoader: SubagentHistoryLoader,  // NEW from this task
    private val config: EngineConfig,
)
```

**`buildContext()` signature change:**
```kotlin
suspend fun buildContext(
    session: Session,
    pendingMessages: List<String>,
    isSubagent: Boolean,
    taskName: String? = null,  // NEW — required for subagent history; null for interactive
): List<LlmMessage>
```

`taskName` has a default of `null` so existing call sites without it remain valid.

**New context assembly order for interactive (non-subagent):**
```
1. System prompt          (~500 tokens)
2. Core Memory            (~500 tokens)
3. Last summary           (~500 tokens)
4. Auto-RAG results       (~400 tokens, 0 if guard skips)   ← NEW
5. Sliding window messages (~2600 tokens, budget reduced by step 4)
6. Tools/skills           (~500 tokens)
```

**Auto-RAG guard logic:**
```kotlin
// Guard: skip auto-RAG if subagent, disabled, or all messages fit in window already
val autoRagResults: List<AutoRagResult> = if (
    !isSubagent &&
    config.autoRag.enabled &&
    db.messagesQueries.countInSegment(session.chatId, session.segmentStart).executeAsOne() > config.context.slidingWindow
) {
    val userQuery = pendingMessages.joinToString(" ")
    val slidingWindowRowIds = fittingMessages.map { it.rowId }.toSet()
    autoRagService.search(userQuery, session.chatId, session.segmentStart, slidingWindowRowIds, config.autoRag)
} else {
    emptyList()
}
```

**Budget adjustment:**
```kotlin
val autoRagTokens = autoRagResults.sumOf { approximateTokenCount(it.content) }
// Reduce sliding window budget by tokens consumed by auto-RAG
remaining -= autoRagTokens
// Then compute fittingMessages from the reduced budget (existing logic)
```

**Auto-RAG block format (inserted as a system-style user message or prepended system note):**
```
From earlier in this conversation:

[user] <content>
[assistant] <content>
```

Implementation choice: add as a separate `LlmMessage(role="system", ...)` or prepend to the first non-system message — follow whatever approach the existing `ContextBuilder` uses for summary injection; keep consistent.

**Subagent path:**
```kotlin
if (isSubagent && taskName != null) {
    // Load last N completed runs from JSONL — NOT from messages DB
    val historyMessages = subagentHistoryLoader.loadHistory(taskName, config.context.subagentHistory)
    // Build context: system + core memory + history messages + current request
    // NO auto-RAG, NO sliding window
    return buildSubagentContext(systemContent, historyMessages, pendingMessages)
}
```

### Component 3: `SubagentHistoryLoader`

**New file:** `engine/src/main/kotlin/io/github/klaw/engine/context/SubagentHistoryLoader.kt`

```kotlin
class SubagentHistoryLoader(
    private val conversationsDir: String = KlawPaths.conversations,
) {
    /**
     * Returns LlmMessages from the last [n] completed runs for [taskName],
     * in chronological order (oldest first — natural conversation context).
     *
     * A "run" = contiguous block ending with role=assistant (non-tool_call).
     * Incomplete last run (no final assistant) is skipped.
     * Missing directory = first run ever → returns empty list.
     */
    fun loadHistory(taskName: String, n: Int): List<LlmMessage>
}
```

**Run detection algorithm:**
1. List all `*.jsonl` files in `conversations/scheduler_$taskName/` sorted by filename (date-based = chronological)
2. For each file, parse line-by-line; deserialize each line as `LlmMessage`
3. Group lines into "runs": a run starts after the previous run's final assistant message
4. A run is **complete** when its last message is `role=assistant` and `type != "tool_call"`
5. Keep last `n` complete runs from across all files
6. Return all messages from those runs (oldest run first, messages within run in original order)

**Error handling:**
- Missing directory → return `emptyList()` (no exception, no log — expected on first run)
- Malformed JSONL line → skip line, `logger.warn { "Skipping malformed JSONL in $fileName: ${e::class.simpleName}" }`, continue
- Empty file → skip file

**`SubagentHistoryLoader` is NOT `@Singleton`** — it has no stateful dependencies. Inject as a regular constructor-created instance in `ContextBuilder`'s Micronaut factory, or make it `@Singleton` if DI requires it (check existing patterns).

### Component 4: `MessageProcessor` Wiring

**File:** `engine/src/main/kotlin/io/github/klaw/engine/message/MessageProcessor.kt`

**Constructor addition:**
```kotlin
class MessageProcessor(
    // ... existing ...
    private val messageEmbeddingService: MessageEmbeddingService,   // NEW from TASK-007a
)
```

**User message save:** Replace `messageRepository.save(...)` with:
```kotlin
val rowId = messageRepository.saveAndGetRowId(id, channel, chatId, "user", "text", content)
messageEmbeddingService.embedAsync(rowId, "user", "text", content, config.autoRag, scope)
```

**Assistant message save (after tool call loop):** Same pattern:
```kotlin
val rowId = messageRepository.saveAndGetRowId(id, channel, chatId, "assistant", type, content, metadata)
messageEmbeddingService.embedAsync(rowId, "assistant", type, content, config.autoRag, scope)
```

`scope` is the coroutine scope of `MessageProcessor` (already exists for debounce buffers).

**Thread `taskName` for subagents:**
```kotlin
// handleScheduledMessage receives a ScheduledMessage with taskName
suspend fun handleScheduledMessage(message: ScheduledMessage) {
    // ...
    contextBuilder.buildContext(session, pendingMessages, isSubagent = true, taskName = message.taskName)
}
```

Verify `ScheduledMessage` has a `taskName` field (from TASK-008 design). If not yet implemented, add it as a placeholder.

---

## TDD Approach

Tests **before** implementation. All in `engine/src/test/kotlin/`.

### ContextBuilder Auto-RAG Tests

**File:** `engine/src/test/kotlin/io/github/klaw/engine/context/ContextBuilderAutoRagTest.kt`

```kotlin
class ContextBuilderAutoRagTest {
    @Test fun `auto-RAG skipped when segment message count does not exceed slidingWindow`()
    @Test fun `auto-RAG skipped when isSubagent is true`()
    @Test fun `auto-RAG skipped when autoRag enabled=false in config`()
    @Test fun `auto-RAG results inserted between summary and sliding window`()
    @Test fun `auto-RAG block absent when autoRagService returns empty list`()
    @Test fun `sliding window budget reduced by auto-RAG token count`()
    @Test fun `slidingWindowRowIds passed to autoRagService for deduplication`()
    @Test fun `auto-RAG block starts with correct header text`()
    @Test fun `context order: system then auto-RAG then sliding window then pending`()
}
```

Use `MockAutoRagService` (MockK or hand-written). Use in-memory SQLite with seed messages.

### SubagentHistoryLoader Tests

**New file:** `engine/src/test/kotlin/io/github/klaw/engine/context/SubagentHistoryLoaderTest.kt`

```kotlin
class SubagentHistoryLoaderTest {
    @Test fun `missing scheduler directory returns empty list`()
    @Test fun `no completed runs in file returns empty list`()
    @Test fun `single complete run returned correctly`()
    @Test fun `loads last N complete runs, oldest run first in output`()
    @Test fun `incomplete final run (no assistant message) is skipped`()
    @Test fun `runs spanning multiple JSONL files merged chronologically`()
    @Test fun `malformed JSONL line skipped, rest of file parsed`()
    @Test fun `tool_call and tool_result messages preserved within run`()
    @Test fun `returns at most n runs when more exist`()
    @Test fun `empty JSONL file handled gracefully`()
}
```

Use temp directory (`@TempDir`) to create test JSONL files.

### MessageProcessor Embedding Wiring Tests

**File:** `engine/src/test/kotlin/io/github/klaw/engine/message/MessageProcessorEmbeddingTest.kt`

```kotlin
class MessageProcessorEmbeddingTest {
    @Test fun `user message save uses saveAndGetRowId`()
    @Test fun `embedAsync called with user role and correct rowId after user save`()
    @Test fun `assistant message save uses saveAndGetRowId`()
    @Test fun `embedAsync called with assistant role after tool call loop completes`()
    @Test fun `taskName passed to buildContext for scheduled message`()
    @Test fun `taskName null for interactive message`()
}
```

Use MockK to verify `saveAndGetRowId` called (not `save`) and `embedAsync` called with correct args.

### ContextBuilder Subagent Tests

**Add to existing `ContextBuilderTest` or `ContextBuilderAutoRagTest`:**

```kotlin
@Test fun `subagent context uses SubagentHistoryLoader not messageRepository.getWindowMessages`()
@Test fun `subagent context has no auto-RAG block`()
@Test fun `subagent context includes history messages from loader`()
@Test fun `subagent context with empty history has only system and pending messages`()
```

---

## Acceptance Criteria

- [ ] Auto-RAG guard: segment ≤ slidingWindow → auto-RAG not called, full budget to sliding window
- [ ] Auto-RAG guard: `isSubagent=true` → auto-RAG not called
- [ ] Auto-RAG guard: `autoRag.enabled=false` → auto-RAG not called
- [ ] Auto-RAG results appear between summary and sliding window in final context
- [ ] Sliding window token budget reduced by actual auto-RAG tokens consumed
- [ ] `slidingWindowRowIds` (from `MessageRow.rowId`) passed to `AutoRagService.search()` for deduplication
- [ ] Subagent context: `SubagentHistoryLoader.loadHistory()` called with correct `taskName` and `subagentHistory`
- [ ] Subagent context: no auto-RAG block
- [ ] `SubagentHistoryLoader`: missing directory → empty list, no exception
- [ ] `SubagentHistoryLoader`: incomplete last run skipped
- [ ] `SubagentHistoryLoader`: malformed JSONL line → WARN log, parsing continues
- [ ] `MessageProcessor`: `saveAndGetRowId()` used for user and assistant messages
- [ ] `MessageProcessor`: `embedAsync()` called immediately after each save
- [ ] `taskName` threaded from `handleScheduledMessage` → `buildContext`
- [ ] All pre-existing `ContextBuilderTest` tests pass (no regression)

---

## Constraints

- `SubagentHistoryLoader` reads JSONL files — **NOT** the `messages` DB table. Design invariant: scheduler JSONL = source of truth for subagent task history.
- `ContextBuilder.buildContext()` signature: add `taskName: String? = null` with default to avoid breaking existing call sites.
- `countInSegment()` uses the new SqlDelight query added to `Messages.sq` (not raw SQL in Kotlin).
- `approximateTokenCount()` from common module for auto-RAG budget accounting.
- `scope` for `embedAsync`: use the `CoroutineScope` already present in `MessageProcessor` (same scope used for debounce buffers).
- `SubagentHistoryLoader` JSONL format: each line is a serialized `LlmMessage` (same format as other conversation JSONL files in the project).
- If `ScheduledMessage.taskName` does not yet exist (TASK-008 not done): add the field to `ScheduledMessage` in common module as part of this task (or create a placeholder).

---

## Documentation Updates

Update the following doc files (created in TASK-005 documentation subtask):

**`doc/memory/how-memory-works.md`:**
- Update "Context assembly for every LLM call" to reflect 6 layers (add auto-RAG at position 4)
- Add section: "Auto-RAG: automatic retrieval from conversation history"
  - Explain segment scope and the guard condition
  - Note that "ok" and "thanks" do not trigger retrieval (relevance threshold)
  - Explain deduplication with sliding window

**`doc/getting-started.md`:**
- Update "What is in my context right now" to list 6 layers including auto-RAG
- Update "What resets with /new" to note auto-RAG is also reset (segment-scoped)

---

## Quality Check

```bash
./gradlew :engine:ktlintCheck :engine:detekt :engine:test
```

Run lang-tools dead code scan on whole project (via MCP). Run code-reviewer subagent after implementation.

Expected false positives to verify are resolved after TASK-007a+b:
- `AutoRagService.search` — now used by `ContextBuilder`
- `MessageEmbeddingService.embedAsync` — now used by `MessageProcessor`
- `MessageRepository.saveAndGetRowId` — now used by `MessageProcessor`
