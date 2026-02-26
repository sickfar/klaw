# Core Memory

Core memory holds persistent key-value facts that are injected into every LLM system prompt. It gives the agent stable knowledge about the user and itself across sessions.

## Structure

The file `core_memory.json` contains two top-level sections:

```json
{
  "user": { "name": "Alice", "lang": "en", "timezone": "Asia/Shanghai" },
  "agent": { "personality": "concise", "goal": "help with Raspberry Pi projects" }
}
```

- **user** — facts about the user (preferences, name, language).
- **agent** — self-knowledge the agent maintains about its own role and behaviour.

## Storage

- **Path:** `$XDG_DATA_HOME/klaw/memory/core_memory.json`
- Plain JSON file on disk. NOT stored in SQLite.
- Survives `klaw reindex` — it is the source of truth, not a cache.

## Tools

| Tool | Description |
|------|-------------|
| `memory_core_get` | Return full core memory as JSON |
| `memory_core_update` | Set `section.key = value` |
| `memory_core_delete` | Remove a key from a section |

Section must be `user` or `agent`. Any other value is rejected.

## Loading

At context-build time the engine calls `CoreMemoryService.load()`, which reads the file and formats it as:

```
[user]
name: Alice
lang: en

[agent]
personality: concise
```

This block is prepended to the system prompt so the LLM always has access to it.
