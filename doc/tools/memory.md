# Memory Tools

## memory_search

Search long-term memory using hybrid vector + FTS5 retrieval.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `query` | string | yes | Search query text |
| `topK` | integer | no | Number of results to return (default: 10) |

**Returns:** Matching memory entries ranked by relevance (RRF fusion, k=60). Each result includes its category and source.

## memory_save

Save a fact to long-term memory with a category.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `content` | string | yes | Text content to save |
| `category` | string | yes | Memory category (existing or new) |
| `source` | string | no | Source label (default: "manual") |

**Returns:** Confirmation message.

Categories are case-insensitive. If the category already exists, the fact is added to it. If not, a new category is created. Each save increments the category's access count.

## Category Management Tools

### memory_rename_category
Rename an existing category.

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `oldName` | string | yes | Current category name |
| `newName` | string | yes | New category name |

### memory_merge_categories
Merge multiple categories into one.

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `sourceNames` | string[] | yes | List of category names to merge |
| `targetName` | string | yes | Target category name |

### memory_delete_category
Delete a category and optionally its facts.

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `name` | string | yes | Category name to delete |
| `deleteFacts` | boolean | no | Whether to delete associated facts (default: true) |

## Hybrid Search Internals

`memory_search` combines two retrieval strategies and merges results:

1. **Semantic search (KNN):** The query is embedded via all-MiniLM-L6-v2 (384d, ONNX or Ollama fallback) and matched against `vec_memory` (sqlite-vec virtual table) using cosine distance. Skipped if sqlite-vec is unavailable.
2. **Full-text search (FTS5):** The query is matched against `memory_facts_fts` using SQLite FTS5 BM25 ranking.
3. **Reciprocal Rank Fusion (RRF):** Both result lists are merged with k=60. Each result scores `sum(1/(k + rank + 1))` across lists. Top-K returned.

### Embedding model

- **all-MiniLM-L6-v2** — 384-dimensional vectors, ONNX Runtime (local).
- Falls back to Ollama API if ONNX is unavailable.
- If neither works, only FTS search is used (graceful degradation).

## Memory Map

When `memory.injectMemoryMap` is enabled in `engine.json`, the engine builds a Memory Map from the database and injects it into the system prompt. The map shows the top N categories (by access count) with entry counts, helping the agent know what topics are in memory and use `memory_search` to retrieve details.

The map is cached and refreshed when `memory_save` is called. Access counts are incremented by both `memory_save` and `memory_search` (but not by auto-RAG).

## Initial Indexation

On first engine start, `MEMORY.md` and `memory/*.md` files are parsed:
- Markdown headers (`#`, `##`, `###`) become category names
- Each non-empty line under a header becomes a fact in that category
- Lines before the first header are skipped
- After successful indexation, files are archived to `memory-archive.zip` and deleted
- On subsequent starts, indexation is skipped if categories already exist in the database

## CLI Commands

```
klaw memory categories list
klaw memory categories rename <old> <new>
klaw memory categories merge <sources> <target>
klaw memory categories delete <name> [--keep-facts]
klaw memory facts add <category> <content>
klaw memory search <query>
```

## Usage Guidance

- Use `memory_save` with descriptive category names for organized long-term storage.
- Use `memory_search` before answering questions that may require recalled context.
- Categories are self-documenting — use descriptive names like "User preferences and habits" instead of "Preferences".
