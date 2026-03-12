# JSONL Conversation Format

Conversations are stored as JSONL (one JSON object per line) files on disk. These files are the source of truth; SQLite tables are rebuilt from them during `klaw reindex`.

## Location

```
$XDG_DATA_HOME/klaw/conversations/{chatId}/YYYY-MM-DD.jsonl
```

A new file is created per day per chat (e.g. `2025-06-01.jsonl`).

## Record format

Each line is a JSON object. The Gateway writes two types of records: inbound (user messages) and outbound (assistant responses).

### Inbound (user message)

```json
{"id":"uuid","ts":"2025-06-01T12:00:00Z","role":"user","content":"Hello"}
```

### Outbound (assistant response)

```json
{"id":"uuid","ts":"2025-06-01T12:00:01Z","role":"assistant","content":"Hi there!","model":"zai/glm-5"}
```

Commands are written with a `type` field:

```json
{"id":"uuid","ts":"2025-06-01T12:00:02Z","role":"user","content":"/new","type":"command"}
```

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | string | yes | Message UUID |
| ts | string | yes | ISO-8601 timestamp |
| role | string | yes | `user` or `assistant` |
| content | string | yes | Message body |
| type | string | no | `command` for slash commands, omitted otherwise |
| model | string | no | LLM model used (outbound messages only, top-level field) |

## Write ownership

- **Gateway** is the sole writer for interactive channels (telegram, discord).
- **Scheduler** writes its own log JSONL for scheduled task outputs.
- Engine reads JSONL for reindexing and context building but does not write interactive conversation files.
