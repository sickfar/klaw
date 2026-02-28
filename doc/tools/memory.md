# Memory Tools

## memory_search

Search long-term memory using hybrid vector + FTS5 retrieval.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `query` | string | yes | Search query text |
| `topK` | integer | no | Number of results to return (default: 10) |

**Returns:** Matching memory entries ranked by relevance (RRF fusion, k=60).

## memory_save

Save a piece of information to long-term memory.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `content` | string | yes | Text content to save |
| `source` | string | no | Source label (default: "manual") |

**Returns:** Confirmation message.

## Hybrid Search Internals

`memory_search` combines two retrieval strategies and merges results:

1. **Semantic search (KNN):** The query is embedded via all-MiniLM-L6-v2 (384d, ONNX or Ollama fallback) and matched against `vec_memory` (sqlite-vec virtual table) using cosine distance. Skipped if sqlite-vec is unavailable.
2. **Full-text search (FTS5):** The query is matched against `messages_fts` using SQLite FTS5 BM25 ranking.
3. **Reciprocal Rank Fusion (RRF):** Both result lists are merged with k=60. Each result scores `sum(1/(k + rank + 1))` across lists. Top-K returned.

### Chunking

Input to `memory_save` is split by `MarkdownChunker`:
- Target chunk size: ~400 tokens, ~80 token overlap.
- Respects markdown structure: headers, paragraphs, lists, code fences.
- Code blocks are never split. Long paragraphs are force-split at sentence boundaries.

### Embedding model

- **all-MiniLM-L6-v2** — 384-dimensional vectors, ONNX Runtime (local).
- Falls back to Ollama API if ONNX is unavailable.
- If neither works, only FTS search is used (graceful degradation).

## Usage Guidance

- Edit workspace files (USER.md, IDENTITY.md) for persistent identity and preferences. Use `memory_save` for episodic facts learned during conversation.
- Use `memory_search` before answering questions that may require recalled context.
