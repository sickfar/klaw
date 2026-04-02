# Multilingual Embeddings — Design Spec

**Date:** 2026-04-01  
**Status:** Approved

## Context

The current embedding model (`all-MiniLM-L6-v2`) supports only English. Klaw is a Chinese-LLM-focused assistant used by multilingual users. Memory search and auto-RAG retrieval fail for non-English content. This spec covers switching to a multilingual model and extending `klaw reindex` to rebuild all vector tables from existing DB data.

---

## Model: `multilingual-e5-small`

- **Source:** `intfloat/multilingual-e5-small` on HuggingFace
- **Dimensions:** 384 (same as current — no schema migration needed)
- **Languages:** 100+ (including RU, ZH, EN, DE, JA, and 46+ others)
- **Cross-lingual retrieval:** yes — fact indexed in Russian can be found by query in Chinese
- **ONNX size:** ~120 MB
- **Latency on Pi 5 (ARM64 CPU):** ~40 ms/request
- **Required files:**
  - `onnx/model.onnx` → `~/.cache/klaw/models/multilingual-e5-small/model.onnx`
  - `tokenizer.json` → `~/.cache/klaw/models/multilingual-e5-small/tokenizer.json`

**e5 prefix convention (mandatory for quality):**
- Indexing (passages): prepend `"passage: "` to text before tokenization
- Searching (queries): prepend `"query: "` to text before tokenization

---

## Architecture Changes

### 1. `EmbeddingService` interface

Add a second method for query embedding:

```kotlin
interface EmbeddingService {
    suspend fun embed(text: String): FloatArray        // passage — for indexing
    suspend fun embedQuery(text: String): FloatArray   // query — for search
}
```

`OllamaEmbeddingService` implements `embedQuery()` the same as `embed()` (Ollama handles prefixes internally or doesn't need them).

### 2. `OnnxEmbeddingService`

- `embed(text)`      → tokenize `"passage: $text"` → ONNX → mean pool → L2 norm
- `embedQuery(text)` → tokenize `"query: $text"`   → ONNX → mean pool → L2 norm

All other inference logic (mean pooling, L2 normalization, tensor cleanup) stays unchanged.

### 3. Call-site routing

| Location | Method | Reason |
|----------|--------|--------|
| `MessageEmbeddingService.embedAsync()` | `embed()` | indexing incoming messages |
| `MemoryServiceImpl.save()` | `embed()` | indexing memory facts |
| `ReindexService.reindexVec()` | `embed()` | bulk re-indexing |
| `MemoryServiceImpl.vectorSearch()` | `embedQuery()` | searching memory |
| `AutoRagService.vectorSearch()` | `embedQuery()` | searching messages |

### 4. Config defaults

`EngineConfig.kt`:
```kotlin
EmbeddingConfig(
    type = "onnx",
    model = "multilingual-e5-small",
    ollamaFallbackModel = "intfloat/multilingual-e5-small"
)
```

### 5. `ModelDownloader`

Update download URLs:
```
model.onnx:     https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx
tokenizer.json: https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/tokenizer.json
```

---

## Reindex Command

### `ReindexService.reindexVec()` — extended

Rebuilds `vec_messages` and `vec_memory` from existing DB data. Does NOT touch the `messages` or `memory_facts` tables. Does NOT read JSONL files.

```
reindexVec():
  1. DELETE FROM vec_messages
     INSERT INTO vec_messages — from messages (role=user/assistant, not tool_call, min tokens)
     using embed() (passage prefix)

  2. DELETE FROM vec_memory
     INSERT INTO vec_memory — from memory_facts (all facts)
     using embed() (passage prefix)
```

`vec_docs` is excluded — it is rebuilt automatically on every engine restart.

### CLI: `klaw reindex` (no flags)

Behavior unchanged at CLI level. Default (no `--from-jsonl`) now covers both `vec_messages` and `vec_memory`. Users switching from `all-MiniLM-L6-v2` must run `klaw reindex` after upgrading.

`klaw reindex --from-jsonl` remains available for full DB rebuild from JSONL conversation logs.

---

## Testing

### Unit tests

| Test class | What to verify |
|------------|---------------|
| `OnnxEmbeddingServiceTest` | `embed()` prepends `"passage: "`, `embedQuery()` prepends `"query: "` (mock tokenizer) |
| `OllamaEmbeddingServiceTest` | `embedQuery()` delegates to same HTTP call as `embed()` |
| `ReindexServiceTest` | `reindexVec()` clears and rebuilds both `vec_messages` and `vec_memory` |
| `EmbeddingServiceFactoryTest` | factory creates correct service for `multilingual-e5-small` config |
| `ModelDownloaderTest` | correct HuggingFace URLs for `multilingual-e5-small` |

### E2E tests

**`MultilingualEmbeddingE2eTest`** (new):
- Send messages/save facts in 5 languages: RU, ZH, EN, DE, JA
- Query in a **different language** from the indexed language (cross-lingual)
- Assert auto-RAG injects semantically relevant content from the other-language fact
- Verify via WireMock LLM request capture (inspect injected context in system message)

Existing E2E tests (`SlidingWindowE2eTest`, `CompactionE2eTest`, etc.) must pass unchanged.

---

## Migration Note

Old embeddings (from `all-MiniLM-L6-v2`) are incompatible with the new model. After upgrading, users must run:

```bash
klaw reindex
```

This is noted in the upgrade guide in `doc/`.

---

## Files to Change

| File | Change |
|------|--------|
| `common/.../config/EngineConfig.kt` | default model name |
| `engine/.../memory/EmbeddingService.kt` | add `embedQuery()` |
| `engine/.../memory/OnnxEmbeddingService.kt` | prefix logic for both methods |
| `engine/.../memory/OllamaEmbeddingService.kt` | add `embedQuery()` (same impl) |
| `engine/.../memory/ModelDownloader.kt` | update URLs |
| `engine/.../memory/MemoryServiceImpl.kt` | `vectorSearch()` → `embedQuery()` |
| `engine/.../message/MessageEmbeddingService.kt` | stays `embed()` |
| `engine/.../memory/AutoRagService.kt` | `vectorSearch()` → `embedQuery()` |
| `engine/.../maintenance/ReindexService.kt` | extend to cover `vec_memory` |
| `e2e/.../MultilingualEmbeddingE2eTest.kt` | new test (cross-lingual) |
| `doc/` | upgrade note about running `klaw reindex` |
