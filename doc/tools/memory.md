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

## memory_core_get

Retrieve the full contents of core memory, which includes `user` and `agent` sections stored as key-value pairs.

**Parameters:** None.

**Returns:** YAML-formatted core memory contents.

## memory_core_update

Update or create a key-value entry in core memory.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `section` | string | yes | Section name: `user` or `agent` |
| `key` | string | yes | Key to update |
| `value` | string | yes | New value |

**Returns:** Confirmation message.

## memory_core_delete

Delete a key from core memory.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `section` | string | yes | Section name: `user` or `agent` |
| `key` | string | yes | Key to delete |

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

- Use `memory_core_*` for persistent user preferences and agent self-knowledge that should survive across sessions.
- Use `memory_save` for episodic facts learned during conversation.
- Use `memory_search` before answering questions that may require recalled context.
