# JSONL Conversation Format

Conversations are stored as JSONL (one JSON object per line) files on disk. These files are the source of truth; SQLite tables are rebuilt from them during `klaw reindex`.

## Location

```
$XDG_DATA_HOME/klaw/conversations/{chatId}/{chatId}.jsonl
```

## Record format

Each line is a `ConversationMessage`:

```json
{"id":"uuid","ts":"2025-06-01T12:00:00Z","role":"user","content":"Hello","type":"text","meta":{"channel":"telegram","chatId":"12345","model":"glm-5","tokensIn":12,"tokensOut":45,"source":"gateway","taskName":null,"tool":null}}
```

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | string | yes | Message UUID |
| ts | string | yes | ISO-8601 timestamp |
| role | string | yes | user, assistant, system, tool |
| content | string | yes | Message body |
| type | string | no | Message type (text, tool_call, tool_result) |
| meta | MessageMeta | no | Metadata object |

### MessageMeta

| Field | Type | Description |
|-------|------|-------------|
| channel | string | Source channel (telegram, cli, scheduler) |
| chatId | string | Conversation ID |
| model | string | LLM model used for this response |
| tokensIn | int | Input token count |
| tokensOut | int | Output token count |
| source | string | Origin (gateway, engine, scheduler) |
| taskName | string | Scheduled task name if applicable |
| tool | string | Tool name if this is a tool message |

## Write ownership

- **Gateway** is the sole writer for interactive channels (telegram, discord).
- **Scheduler** writes its own log JSONL for scheduled task outputs.
- Engine reads JSONL for reindexing and context building but does not write interactive conversation files.
