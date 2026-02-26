# Archival (Long-Term) Memory

Archival memory stores chunks of text that the agent can save and retrieve later. It powers the `memory_save` and `memory_search` tools.

## Storage

Chunks are stored in the `memory_chunks` table in `klaw.db`. Each chunk has a source label, optional chat_id, content text, and timestamps. Embeddings are stored in the `vec_memory` sqlite-vec virtual table (384-dimensional float vectors).

All data in SQLite is cache/index only and can be rebuilt via `klaw reindex`.

## Embedding

- **Model:** all-MiniLM-L6-v2 (384 dimensions)
- **Primary backend:** ONNX Runtime (local, no network)
- **Fallback:** Ollama API (`nomic-embed-text` or configured model)
- If neither is available, vector search is skipped and FTS-only mode is used.

## Chunking

`MarkdownChunker` splits input text into chunks of ~400 tokens with ~80 token overlap:

1. Parse into blocks: headers, paragraphs, lists, code fences.
2. Accumulate blocks until the token budget is exceeded, then emit a chunk.
3. Long paragraphs (>400 tokens) are force-split at sentence boundaries.
4. Each chunk carries trailing overlap text from the previous chunk for continuity.
5. Code blocks are never split mid-fence.

## Search: Hybrid Retrieval

`memory_search` runs two parallel searches and merges results:

1. **Semantic (KNN):** Query embedding matched against `vec_memory` using sqlite-vec cosine distance. Skipped if sqlite-vec is unavailable.
2. **Full-text (FTS5):** Query matched against `messages_fts` using SQLite FTS5 ranking.
3. **Fusion:** Results merged via Reciprocal Rank Fusion (RRF, k=60). Each result's score = sum of `1/(k + rank + 1)` across both lists.

Top-K results (default 10) are returned, formatted as `[source] (timestamp)\ncontent`.

## Graceful Degradation

| sqlite-vec available | ONNX/Ollama available | Behaviour |
|---|---|---|
| yes | yes | Full hybrid search |
| yes | no | FTS-only (no embeddings stored) |
| no | yes/no | FTS-only (vector search returns empty) |
