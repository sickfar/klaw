# Database Schema

Klaw uses two SQLite databases. All data is cache/index and can be rebuilt from JSONL via `klaw reindex`.

## klaw.db

### messages

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT PK | Message UUID |
| channel | TEXT | Source channel (telegram, cli, scheduler) |
| chat_id | TEXT | Conversation identifier |
| role | TEXT | user, assistant, system, tool |
| type | TEXT | Message type |
| content | TEXT | Message body |
| metadata | TEXT | JSON blob (nullable) |
| created_at | TEXT | ISO-8601 timestamp |

Index: `idx_messages_chat_id(chat_id)`.

### sessions

| Column | Type | Description |
|--------|------|-------------|
| chat_id | TEXT PK | Conversation identifier |
| model | TEXT | Current LLM model ID |
| segment_start | TEXT | ISO-8601 start of current context window |
| created_at | TEXT | Session creation timestamp |

### memory_chunks

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK AUTO | Chunk row ID |
| source | TEXT | Origin label (manual, reindex, etc.) |
| chat_id | TEXT | Associated conversation (nullable) |
| content | TEXT | Chunk text |
| created_at | TEXT | ISO-8601 |
| updated_at | TEXT | ISO-8601 |

### doc_chunks

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK AUTO | Chunk row ID |
| file | TEXT | Source file path |
| section | TEXT | Section header (nullable) |
| content | TEXT | Chunk text |
| version | TEXT | File version hash (nullable) |

### summaries

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK AUTO | Summary row ID |
| chat_id | TEXT | Conversation identifier |
| from_message_id | TEXT | First message in summarised range |
| to_message_id | TEXT | Last message in summarised range |
| file_path | TEXT | Path to summary JSONL |
| created_at | TEXT | ISO-8601 |

### Virtual tables

- **messages_fts** — FTS5 virtual table over messages for full-text search.
- **vec_memory** — sqlite-vec virtual table, 384-dimensional float embeddings for memory chunks. Joined to `memory_chunks` on rowid.
- **vec_docs** — sqlite-vec virtual table, 384-dimensional float embeddings for doc chunks. Joined to `doc_chunks` on rowid.

## scheduler.db

Contains Quartz QRTZ_* tables (QRTZ_TRIGGERS, QRTZ_JOB_DETAILS, QRTZ_CRON_TRIGGERS, etc.) managed by the Quartz JDBC store with a custom `SQLiteDelegate`. Schema is created by Quartz on first run.
