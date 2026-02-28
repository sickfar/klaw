# OpenClaw Compatibility

Klaw is designed to be drop-in compatible with OpenClaw workspace directories.

## Drop-in usage

Set `KLAW_WORKSPACE` to an existing OpenClaw workspace directory. Klaw reads all standard workspace files without modification:

- `SOUL.md` → system prompt
- `IDENTITY.md` → system prompt
- `USER.md` → system prompt
- `AGENTS.md` → system prompt
- `TOOLS.md` → system prompt
- `MEMORY.md` → sqlite-vec index
- `HEARTBEAT.md` → Quartz scheduled jobs

No migration or file conversion required.

---

## Key differences from OpenClaw

### MEMORY.md is chunked and indexed

OpenClaw loads `MEMORY.md` as a single text block into the system prompt. Klaw chunks it and indexes it in sqlite-vec for semantic search via `memory_search`.

This means:
- Memory is not always in context (only relevant chunks are retrieved)
- Memory scales beyond what fits in a context window
- Search quality depends on the embedding model

### HEARTBEAT tasks run as isolated subagents

OpenClaw runs heartbeat tasks in the main session context, consuming 170k–210k tokens per run. Klaw runs heartbeat tasks as isolated subagents using ~2–3k tokens each.

The tradeoff: heartbeat subagents do **not** see the main chat's recent messages. They have access to archival memory and their own task log, but not the conversation history. This is intentional — heartbeat tasks should be self-contained.

### Skills override order

Workspace skills (`$KLAW_WORKSPACE/skills/`) take precedence over global skills (`$XDG_DATA_HOME/klaw/skills/`) with the same name. Same as OpenClaw behavior.

---

## What is NOT supported

- OpenClaw's full-context MEMORY.md loading (replaced by chunked indexing)
- OpenClaw's main-session heartbeat execution (replaced by isolated subagents)

---

## See also

- `doc/workspace/workspace-files.md` — full reference for all workspace files
- `doc/scheduling/heartbeat.md` — HEARTBEAT.md format
- `doc/memory/` — memory system documentation
