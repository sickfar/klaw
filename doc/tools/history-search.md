# history_search

Search past conversation messages semantically. Returns matching messages from the current chat's history with timestamps and role labels.

## Parameters

| Name  | Type    | Required | Description                  |
|-------|---------|----------|------------------------------|
| query | string  | yes      | Search query                 |
| topK  | integer | no       | Number of results (default 10) |

## Behavior

- Performs hybrid search (FTS5 full-text + vector similarity when available) over all messages in the current chat
- Results are merged using Reciprocal Rank Fusion (RRF)
- Searches the entire chat history with no time-window restriction
- Scoped to the current chat only (does not search other chats)

## Output format

Each result is formatted as:

```
[2024-01-01T00:01:00Z] [user] Message content here
[2024-01-01T00:02:00Z] [assistant] Response content here
```

Returns `No matching messages found.` when no results match.

## Difference from memory_search

`history_search` searches past conversation messages in the current chat. `memory_search` searches saved knowledge chunks (documents, notes) — not conversation history.

## Example

```json
{
  "name": "history_search",
  "arguments": {
    "query": "raspberry pi setup instructions",
    "topK": 5
  }
}
```
