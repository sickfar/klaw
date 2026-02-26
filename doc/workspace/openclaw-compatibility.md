# OpenClaw Compatibility

Klaw is designed to be drop-in compatible with OpenClaw workspace directories.

## Drop-in usage

Set `KLAW_WORKSPACE` to an existing OpenClaw workspace directory. Klaw reads all standard workspace files without modification:

- `SOUL.md` → system prompt
- `IDENTITY.md` → system prompt
- `AGENTS.md` → system prompt
- `TOOLS.md` → system prompt
- `USER.md` → core memory (first run only)
- `MEMORY.md` → sqlite-vec index
- `HEARTBEAT.md` → Quartz scheduled jobs

No migration or file conversion required.

---

## Key differences from OpenClaw

### USER.md is imported once

OpenClaw uses `USER.md` as a live document loaded into context on every request. Klaw imports it once into `core_memory.json["user"]["notes"]` on first startup.

After the first run, `core_memory.json` is authoritative. Runtime updates via `memory_core_update` are stored there — not written back to `USER.md`. If you edit `USER.md` after first startup, the changes are ignored unless you clear the user section of `core_memory.json`.

### MEMORY.md is chunked and indexed

OpenClaw loads `MEMORY.md` as a single text block into the system prompt. Klaw chunks it and indexes it in sqlite-vec for semantic search via `memory_search`.

This means:
- Memory is not always in context (only relevant chunks are retrieved)
- Memory scales beyond what fits in a context window
- Search quality depends on the embedding model

### HEARTBEAT tasks run as isolated subagents

OpenClaw runs heartbeat tasks in the main session context, consuming 170k–210k tokens per run. Klaw runs heartbeat tasks as isolated subagents using ~2–3k tokens each.

The tradeoff: heartbeat subagents do **not** see the main chat's recent messages. They have access to core memory and their own task log, but not the conversation history. This is intentional — heartbeat tasks should be self-contained.

### Skills override order

Workspace skills (`$KLAW_WORKSPACE/skills/`) take precedence over global skills (`$XDG_DATA_HOME/klaw/skills/`) with the same name. Same as OpenClaw behavior.

---

## Core memory vs USER.md after migration

If you migrate from OpenClaw and want `USER.md` changes to take effect:

1. Delete or clear the `user` section of `core_memory.json` (located at `$KLAW_WORKSPACE/core_memory.json` or `$XDG_DATA_HOME/klaw/core_memory.json`)
2. Restart the engine — `USER.md` will be re-imported

Or use `memory_core_update("user", "notes", new_content)` to update it at runtime without touching files.

---

## What is NOT supported

- OpenClaw's live `USER.md` context injection (replaced by core memory)
- OpenClaw's full-context MEMORY.md loading (replaced by chunked indexing)
- OpenClaw's main-session heartbeat execution (replaced by isolated subagents)

---

## See also

- `doc/workspace/workspace-files.md` — full reference for all workspace files
- `doc/scheduling/heartbeat.md` — HEARTBEAT.md format
- `doc/memory/` — memory system documentation
