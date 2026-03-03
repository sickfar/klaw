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
- `HEARTBEAT.md` → periodic autonomous heartbeat (configured in `engine.json`)

No migration or file conversion required.

---

## Key differences from OpenClaw

### MEMORY.md is chunked and indexed

OpenClaw loads `MEMORY.md` as a single text block into the system prompt. Klaw chunks it and indexes it in sqlite-vec for semantic search via `memory_search`.

This means:
- Memory is not always in context (only relevant chunks are retrieved)
- Memory scales beyond what fits in a context window
- Search quality depends on the embedding model

### HEARTBEAT.md is compatible

Klaw reads `HEARTBEAT.md` from the workspace. Unlike OpenClaw, Klaw does not create a stub `HEARTBEAT.md` during init — create it manually when ready. The heartbeat delivery target is set via the `/use-for-heartbeat` command. See `doc/scheduling/heartbeat.md` for details.

### Skills override order

Workspace skills (`$KLAW_WORKSPACE/skills/`) take precedence over global skills (`$XDG_DATA_HOME/klaw/skills/`) with the same name. Same as OpenClaw behavior.

---

## What is NOT supported

- OpenClaw's full-context MEMORY.md loading (replaced by chunked indexing)

---

## See also

- `doc/workspace/workspace-files.md` — full reference for all workspace files
- `doc/scheduling/heartbeat.md` — HEARTBEAT.md format
- `doc/memory/` — memory system documentation
